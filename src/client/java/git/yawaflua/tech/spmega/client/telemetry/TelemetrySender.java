package git.yawaflua.tech.spmega.client.telemetry;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import git.yawaflua.tech.spmega.ModConfig;
import git.yawaflua.tech.spmega.SPMega;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class TelemetrySender {
    private static final TelemetrySender INSTANCE = new TelemetrySender();
    private static final Gson GSON = new Gson();

    private final String sessionId = UUID.randomUUID().toString();
    private final HttpClient httpClient;
    private ScheduledExecutorService scheduler;

    private TelemetrySender() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public static TelemetrySender instance() {
        return INSTANCE;
    }

    public String sessionId() {
        return sessionId;
    }

    public void start(int intervalSeconds) {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SPMega-Telemetry-Sender");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::flush, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    public void flush() {
        try {
            ModConfig config = SPMega.getConfig();
            if (config == null) return;

            // Performance snapshot при каждом flush
            PerformanceSampler.instance().emitSnapshot();

            // System info (only if telemetryCollectSystemInfo enabled)
            if (config.telemetryCollectSystemInfo()) {
                JsonObject sysInfo = SystemInfoCollector.instance().collect();
                TelemetryCollector.instance().record(TelemetryEvent.now("system_info", sysInfo));
            }

            List<TelemetryEvent> events = TelemetryCollector.instance().drain();
            if (events.isEmpty()) return;

            JsonObject batch = new JsonObject();
            batch.addProperty("clientVersion", getModVersion());
            batch.addProperty("sessionId", sessionId);
            batch.addProperty("sentAt", Instant.now().toString());

            JsonArray eventsArray = new JsonArray();
            for (TelemetryEvent event : events) {
                JsonObject ev = new JsonObject();
                ev.addProperty("eventType", event.eventType());
                ev.addProperty("timestamp", event.timestamp().toString());
                ev.add("payload", event.payload());
                eventsArray.add(ev);
            }
            batch.add("events", eventsArray);

            String body = GSON.toJson(batch);
            String apiDomain = config.apiDomain();
            if (apiDomain == null || apiDomain.isEmpty()) apiDomain = ModConfig.DEFAULT_API_DOMAIN;

            String token = config.apiToken();
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(apiDomain + "/api/v1/telemetry"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body));

            if (token != null && !token.equals(ModConfig.DEFAULT_API_TOKEN)) {
                reqBuilder.header("Authorization", "Bearer " + token);
            }

            httpClient.sendAsync(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            System.err.println("[SPMEGA] Telemetry flush error: " + e.getMessage());
        }
    }

    private String getModVersion() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getModContainer("spmega")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElse("unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }
}
