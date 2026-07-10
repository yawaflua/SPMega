package git.yawaflua.tech.spmega.client.telemetry;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class PerformanceSampler {
    private static final PerformanceSampler INSTANCE = new PerformanceSampler();

    private final AtomicInteger fpsSum = new AtomicInteger();
    private final AtomicInteger fpsCount = new AtomicInteger();
    private final AtomicInteger fpsMin = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicInteger fpsMax = new AtomicInteger(0);
    private final AtomicLong lastSnapshotMs = new AtomicLong(System.currentTimeMillis());

    private PerformanceSampler() {
    }

    public static PerformanceSampler instance() {
        return INSTANCE;
    }

    public void tick() {
        int fps = MinecraftClient.getInstance().getCurrentFps();
        fpsSum.addAndGet(fps);
        fpsCount.incrementAndGet();
        fpsMin.updateAndGet(prev -> Math.min(prev, fps));
        fpsMax.updateAndGet(prev -> Math.max(prev, fps));
    }

    public int currentFps() {
        return MinecraftClient.getInstance().getCurrentFps();
    }

    public void emitSnapshot() {
        int count = fpsCount.getAndSet(0);
        int sum = fpsSum.getAndSet(0);
        int min = fpsMin.getAndSet(Integer.MAX_VALUE);
        int max = fpsMax.getAndSet(0);
        long now = System.currentTimeMillis();
        long periodMs = now - lastSnapshotMs.getAndSet(now);

        if (count == 0) return;

        JsonObject payload = new JsonObject();
        payload.addProperty("fpsAvg", sum / count);
        payload.addProperty("fpsMin", min == Integer.MAX_VALUE ? 0 : min);
        payload.addProperty("fpsMax", max);
        payload.addProperty("sampleCount", count);
        payload.addProperty("periodMs", periodMs);

        Runtime rt = Runtime.getRuntime();
        payload.addProperty("usedMemoryMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        payload.addProperty("totalMemoryMb", rt.totalMemory() / (1024 * 1024));
        payload.addProperty("maxMemoryMb", rt.maxMemory() / (1024 * 1024));

        TelemetryCollector.instance().record(TelemetryEvent.now("performance_snapshot", payload));
    }
}
