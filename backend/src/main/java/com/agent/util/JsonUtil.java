package com.agent.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Gson utility — singleton Gson instance and helper methods for JSON operations.
 */
public class JsonUtil {

    private static final Gson GSON = new GsonBuilder()
            .setDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .serializeNulls()
            .create();

    private static final Gson GSON_PRETTY = new GsonBuilder()
            .setDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .serializeNulls()
            .setPrettyPrinting()
            .create();

    /**
     * Returns the singleton Gson instance.
     */
    public static Gson getGson() {
        return GSON;
    }

    /**
     * Serialize an object to JSON string.
     */
    public static String toJson(Object obj) {
        return GSON.toJson(obj);
    }

    /**
     * Deserialize a JSON string to an object of the given type.
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        return GSON.fromJson(json, clazz);
    }

    /**
     * Parse a JSON string into a JsonObject for manual field access.
     */
    public static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /**
     * Pretty-print a JSON string (useful for logging/debugging).
     */
    public static String toPrettyJson(Object obj) {
        return GSON_PRETTY.toJson(obj);
    }
}
