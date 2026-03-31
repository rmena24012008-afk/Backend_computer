package com.agent.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.sql.Timestamp;

/**
 * Gson utility — singleton Gson instance and helper methods for JSON operations.
 *
 * <p>All {@link java.sql.Timestamp} values are serialized as IST
 * (Asia/Kolkata, UTC+05:30) strings.  The database stores everything in UTC;
 * the conversion happens here at serialization time via {@link TimeUtil#toIST}.
 */
public class JsonUtil {

    /**
     * Custom Gson {@link TypeAdapter} for {@link java.sql.Timestamp}.
     * Writes: converts the UTC database timestamp to an IST ISO-8601 string.
     * Reads:  parses an ISO-8601 string back to a Timestamp (best-effort).
     */
    private static final TypeAdapter<Timestamp> TIMESTAMP_ADAPTER = new TypeAdapter<Timestamp>() {
        @Override
        public void write(JsonWriter out, Timestamp value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(TimeUtil.toIST(value));
            }
        }

        @Override
        public Timestamp read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            String s = in.nextString();
            try {
                return Timestamp.valueOf(s.replace("T", " ").replaceAll("[+-]\\d{2}:\\d{2}$", ""));
            } catch (Exception e) {
                return null;
            }
        }
    };

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Timestamp.class, TIMESTAMP_ADAPTER)
            .registerTypeAdapter(java.util.Date.class, TIMESTAMP_ADAPTER)
            .serializeNulls()
            .create();

    private static final Gson GSON_PRETTY = new GsonBuilder()
            .registerTypeAdapter(Timestamp.class, TIMESTAMP_ADAPTER)
            .registerTypeAdapter(java.util.Date.class, TIMESTAMP_ADAPTER)
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
