package git.yawaflua.tech.spmega;

import net.fabricmc.api.ModInitializer;

public class SPMega implements ModInitializer {
    private static ModConfig config;

    public static ModConfig getConfig() {
        return config;
    }

    @Override
    public void onInitialize() {
        config = ConfigManager.loadOrCreate();
    }
}
