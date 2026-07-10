package git.yawaflua.tech.spmega.client.telemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class TelemetryCollector {
    private static final TelemetryCollector INSTANCE = new TelemetryCollector();
    private static final int MAX_QUEUE_SIZE = 1000;

    private final ConcurrentLinkedQueue<TelemetryEvent> queue = new ConcurrentLinkedQueue<>();

    private TelemetryCollector() {
    }

    public static TelemetryCollector instance() {
        return INSTANCE;
    }

    public void record(TelemetryEvent event) {
        if (event == null) return;
        if (queue.size() >= MAX_QUEUE_SIZE) {
            queue.poll();
        }
        queue.add(event);
    }

    public List<TelemetryEvent> drain() {
        List<TelemetryEvent> batch = new ArrayList<>();
        TelemetryEvent event;
        while ((event = queue.poll()) != null) {
            batch.add(event);
        }
        return batch;
    }

    public int size() {
        return queue.size();
    }
}
