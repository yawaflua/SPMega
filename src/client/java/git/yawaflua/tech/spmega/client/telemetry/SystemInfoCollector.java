package git.yawaflua.tech.spmega.client.telemetry;

import com.google.gson.JsonObject;
import org.lwjgl.opengl.GL11;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;

public final class SystemInfoCollector {
    private static final SystemInfoCollector INSTANCE = new SystemInfoCollector();
    private static final long MB = 1024L * 1024L;

    private final CentralProcessor cpu = new SystemInfo().getHardware().getProcessor();
    private final AtomicReference<String> cachedIp = new AtomicReference<>("<unknown>");
    private final AtomicReference<String> gpuRenderer = new AtomicReference<>("<pending>");
    private final AtomicReference<String> gpuVendor = new AtomicReference<>("<pending>");
    private volatile boolean gpuCollected = false;

    private SystemInfoCollector() {
    }

    public static SystemInfoCollector instance() {
        return INSTANCE;
    }

    public void init() {
        fetchIpAsync();
        collectGpuOnRenderThread();
    }

    private void fetchIpAsync() {
        HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .sendAsync(HttpRequest.newBuilder()
                                .uri(URI.create("https://api.ipify.org?format=text"))
                                .timeout(Duration.ofSeconds(5))
                                .GET()
                                .build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 200) {
                        cachedIp.set(response.body().trim());
                    }
                })
                .exceptionally(ignored -> null);
    }

    private void collectGpuOnRenderThread() {
        Minecraft.getInstance().execute(() -> {
            try {
                gpuRenderer.set(GL11.glGetString(GL11.GL_RENDERER));
                gpuVendor.set(GL11.glGetString(GL11.GL_VENDOR));
            } catch (Exception ignored) {
            }
            gpuCollected = true;
        });
    }

    public JsonObject collect() {
        JsonObject info = new JsonObject();

        Runtime rt = Runtime.getRuntime();
        info.addProperty("cpuCores", rt.availableProcessors());
        info.addProperty("cpuName", cpu.getProcessorIdentifier().getName());
        info.addProperty("cpuCurrentFrequencyHz", Arrays.stream(cpu.getCurrentFreq()).max().orElse(0));
        info.addProperty("cpuMaxFrequencyHz", cpu.getMaxFreq());

        try {
            var osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean ext) {
                double cpuLoad = ext.getProcessCpuLoad();
                if (cpuLoad >= 0) {
                    info.addProperty("cpuLoad", Math.round(cpuLoad * 100.0));
                }
            }
        } catch (Exception ignored) {
        }

        info.addProperty("gpuRenderer", gpuRenderer.get());
        info.addProperty("gpuVendor", gpuVendor.get());

        info.addProperty("ramTotalMb", rt.maxMemory() / MB);
        info.addProperty("ramFreeMb", rt.freeMemory() / MB);
        info.addProperty("ramUsedMb", (rt.totalMemory() - rt.freeMemory()) / MB);

        try {
            FileStore store = Files.getFileStore(Path.of("."));
            info.addProperty("storageTotalMb", store.getTotalSpace() / MB);
            info.addProperty("storageFreeMb", store.getUsableSpace() / MB);
        } catch (Exception ignored) {
        }

        info.addProperty("publicIp", cachedIp.get());

        try {
            info.addProperty("hostName", InetAddress.getLocalHost().getHostName());
        } catch (Exception ignored) {
            info.addProperty("hostName", System.getenv("COMPUTERNAME"));
        }

        info.addProperty("javaVersion", System.getProperty("java.version"));
        info.addProperty("osName", System.getProperty("os.name"));
        info.addProperty("osArch", System.getProperty("os.arch"));

        return info;
    }
}
