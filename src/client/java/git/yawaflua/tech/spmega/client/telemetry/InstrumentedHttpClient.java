package git.yawaflua.tech.spmega.client.telemetry;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public record InstrumentedHttpClient(HttpClient delegate) {
    public InstrumentedHttpClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build());
    }

    public static void recordHttpEvent(URI uri, String method, int statusCode,
                                       long durationMs, boolean success, String errorType,
                                       int fpsBefore, int fpsAfter) {
        JsonObject payload = new JsonObject();
        payload.addProperty("target", TelemetryUriSanitizer.classifyTarget(uri));
        payload.addProperty("method", method);
        payload.addProperty("path", TelemetryUriSanitizer.sanitize(uri));
        payload.addProperty("statusCode", statusCode);
        payload.addProperty("durationMs", durationMs);
        payload.addProperty("success", success);
        if (errorType != null) {
            payload.addProperty("errorType", errorType);
        }
        payload.addProperty("fpsBefore", fpsBefore);
        payload.addProperty("fpsAfter", fpsAfter);

        TelemetryCollector.instance().record(TelemetryEvent.now("http_request", payload));
    }

    public HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        URI uri = request.uri();
        String target = TelemetryUriSanitizer.classifyTarget(uri);
        String sanitizedPath = TelemetryUriSanitizer.sanitize(uri);
        String method = request.method();

        int fpsBefore = PerformanceSampler.instance().currentFps();
        long startNs = System.nanoTime();

        try {
            HttpResponse<String> response = delegate.send(request, HttpResponse.BodyHandlers.ofString());
            long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
            int fpsAfter = PerformanceSampler.instance().currentFps();

            recordEvent(target, method, sanitizedPath, response.statusCode(), durationMs,
                    true, null, fpsBefore, fpsAfter);

            return response;
        } catch (IOException | InterruptedException e) {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
            int fpsAfter = PerformanceSampler.instance().currentFps();

            recordEvent(target, method, sanitizedPath, -1, durationMs,
                    false, e.getClass().getSimpleName(), fpsBefore, fpsAfter);
            throw e;
        }
    }

    public CompletableFuture<HttpResponse<String>> sendAsync(HttpRequest request) {
        URI uri = request.uri();
        String target = TelemetryUriSanitizer.classifyTarget(uri);
        String sanitizedPath = TelemetryUriSanitizer.sanitize(uri);
        String method = request.method();

        int fpsBefore = PerformanceSampler.instance().currentFps();
        long startNs = System.nanoTime();

        return delegate.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, throwable) -> {
                    long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
                    int fpsAfter = PerformanceSampler.instance().currentFps();

                    if (throwable != null) {
                        recordEvent(target, method, sanitizedPath, -1, durationMs,
                                false, throwable.getClass().getSimpleName(), fpsBefore, fpsAfter);
                    } else {
                        recordEvent(target, method, sanitizedPath, response.statusCode(), durationMs,
                                true, null, fpsBefore, fpsAfter);
                    }
                });
    }

    private void recordEvent(String target, String method, String path, int statusCode,
                             long durationMs, boolean success, String errorType,
                             int fpsBefore, int fpsAfter) {
        JsonObject payload = new JsonObject();
        payload.addProperty("target", target);
        payload.addProperty("method", method);
        payload.addProperty("path", path);
        payload.addProperty("statusCode", statusCode);
        payload.addProperty("durationMs", durationMs);
        payload.addProperty("success", success);
        if (errorType != null) {
            payload.addProperty("errorType", errorType);
        }
        payload.addProperty("fpsBefore", fpsBefore);
        payload.addProperty("fpsAfter", fpsAfter);

        TelemetryCollector.instance().record(TelemetryEvent.now("http_request", payload));
    }
}
