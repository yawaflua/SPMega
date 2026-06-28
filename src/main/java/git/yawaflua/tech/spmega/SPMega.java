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
        config = ConfigManager.loadOrCreate();
    }
}
