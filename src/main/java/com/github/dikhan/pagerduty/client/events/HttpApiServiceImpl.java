package com.github.dikhan.pagerduty.client.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dikhan.pagerduty.client.events.domain.ChangeEvent;
import com.github.dikhan.pagerduty.client.events.domain.EventResult;
import com.github.dikhan.pagerduty.client.events.domain.PagerDutyEvent;
import com.github.dikhan.pagerduty.client.events.exceptions.NotifyEventException;
import com.github.dikhan.pagerduty.client.events.utils.JsonUtils;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class HttpApiServiceImpl implements ApiService {

    private static final Logger log = LoggerFactory.getLogger(HttpApiServiceImpl.class);

    private static final int RATE_LIMIT_STATUS_CODE = 429;

    // The time between retries for each different result status codes.
    private static final Map<Integer, long[]> RETRY_WAIT_TIME_MILLISECONDS = new HashMap<>();
    static {
        // "quickly" retrying in case of 500s to recover from flapping errors
        RETRY_WAIT_TIME_MILLISECONDS.put(HttpStatus.SC_INTERNAL_SERVER_ERROR, new long[]{500, 1_000, 2_000});
        // "slowly" retrying from Rate Limit to give it time to recover
        RETRY_WAIT_TIME_MILLISECONDS.put(RATE_LIMIT_STATUS_CODE, new long[]{10_000, 25_000, 55_000});
    }

    private final String eventApi;
    private final String changeEventApi;
    private final boolean doRetries;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    public HttpApiServiceImpl(String eventApi, String changeEventApi, boolean doRetries) {
        this.eventApi = eventApi;
        this.changeEventApi = changeEventApi;
        this.doRetries = doRetries;
        this.httpClient = HttpClient.newHttpClient();
    }

    public HttpApiServiceImpl(String eventApi, String changeEventApi, String proxyHost, Integer proxyPort, boolean doRetries) {
        this.eventApi = eventApi;
        this.changeEventApi = changeEventApi;
        this.doRetries = doRetries;
        this.httpClient = HttpClient
                .newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)))
                .build();
    }

    public EventResult notifyEvent(PagerDutyEvent event) throws NotifyEventException {
        if (event instanceof ChangeEvent) {
            return notifyEvent(event, changeEventApi, 0);
        }
        return notifyEvent(event, eventApi, 0);
    }

    private EventResult notifyEvent(PagerDutyEvent event, String api, int retryCount) throws NotifyEventException {
        try {
            String requestBody = objectMapper.writeValueAsString(event);

            HttpRequest request = HttpRequest.newBuilder(URI.create(api))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
            JsonNode jsonResponse;

            if (log.isDebugEnabled()) {
                log.debug(response.body());
            }

            int responseStatus = response.statusCode();
            switch(responseStatus) {
                case HttpStatus.SC_OK:
                case HttpStatus.SC_CREATED:
                case HttpStatus.SC_ACCEPTED:
                    jsonResponse = objectMapper.readValue(response.body(),JsonNode.class);
                    return EventResult.successEvent(JsonUtils.getPropertyValue(jsonResponse, "status"), JsonUtils.getPropertyValue(jsonResponse, "message"), JsonUtils.getPropertyValue(jsonResponse, "dedup_key"));
                case HttpStatus.SC_BAD_REQUEST:
                    jsonResponse = objectMapper.readValue(response.body(),JsonNode.class);
                    return EventResult.errorEvent(JsonUtils.getPropertyValue(jsonResponse, "status"), JsonUtils.getPropertyValue(jsonResponse, "message"), JsonUtils.getArrayValue(jsonResponse, "errors"));
                case RATE_LIMIT_STATUS_CODE:
                case HttpStatus.SC_INTERNAL_SERVER_ERROR:
                    if (doRetries) {
                        jsonResponse = objectMapper.readValue(response.body(),JsonNode.class);
                        return handleRetries(event, api, retryCount, jsonResponse, responseStatus);
                    } else {
                        return EventResult.errorEvent(String.valueOf(responseStatus), "", response.body());
                    }
                default:
                    return EventResult.errorEvent(String.valueOf(responseStatus), "", response.body());
            }
        } catch (IOException | InterruptedException e) {
            throw new NotifyEventException(e);
        }
    }

    private EventResult handleRetries(PagerDutyEvent event, String api, int retryCount, JsonNode jsonResponse, int responseStatus) throws IOException, NotifyEventException {
        long[] retryDelays = RETRY_WAIT_TIME_MILLISECONDS.get(responseStatus);

        int maxRetries = retryDelays.length;
        if (retryCount == maxRetries) {
            log.debug("Received a {} response. Exhausted all the possibilities to retry.", responseStatus);
            return EventResult.errorEvent(String.valueOf(responseStatus), "", objectMapper.writeValueAsString(jsonResponse));
        }

        log.debug("Received a {} response. Will retry again. ({}/{})", responseStatus, retryCount, maxRetries);

        try {
            Thread.sleep(retryDelays[retryCount]);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return notifyEvent(event, api, retryCount + 1);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        HttpApiServiceImpl that = (HttpApiServiceImpl) o;

        return doRetries == that.doRetries && Objects.equals(eventApi, that.eventApi) && Objects.equals(changeEventApi, that.changeEventApi);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventApi, changeEventApi, doRetries);
    }
}