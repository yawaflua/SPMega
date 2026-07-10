package git.yawaflua.tech.spmega;

import net.fabricmc.api.ModInitializer;

public class SPMega implements ModInitializer {
    private static ModConfig config;

    public static ModConfig getConfig() {
        return config;
    }

    public static void setConfig(ModConfig newConfig) {
        config = newConfig;
        ConfigManager.save(newConfig);
    }

    @Override
    public void onInitialize() {
        long startNs = System.nanoTime();
        config = ConfigManager.loadOrCreate();
        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
        System.out.println("[SPMEGA] Main init took " + durationMs + "ms");
    }
}
