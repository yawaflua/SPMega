package git.yawaflua.tech.spmega.client.telemetry;

import com.google.gson.JsonObject;

import java.time.Instant;

public record TelemetryEvent(String eventType, Instant timestamp, JsonObject payload) {

    public static TelemetryEvent now(String eventType, JsonObject payload) {
        return new TelemetryEvent(eventType, Instant.now(), payload);
    }

    public static TelemetryEvent now(String eventType) {
        return now(eventType, new JsonObject());
    }
}
