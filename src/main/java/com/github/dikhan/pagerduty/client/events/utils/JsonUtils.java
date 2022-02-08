package com.github.dikhan.pagerduty.client.events.utils;

import com.fasterxml.jackson.databind.JsonNode;

public class JsonUtils {

    public static String getPropertyValue(JsonNode jsonResponse, String key) {
        if (jsonResponse.has(key)) {
            return jsonResponse.get(key).asText();
        }

        return null;
    }

    public static String getArrayValue(JsonNode jsonResponse, String key) {
        if (jsonResponse.has(key) && jsonResponse.get(key).isArray()) {
            return jsonResponse.get(key).toString();
        }

        return null;
    }
}
