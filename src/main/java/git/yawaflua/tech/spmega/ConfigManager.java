package git.yawaflua.tech.spmega;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

public final class ConfigManager {
    private static final String FILE_NAME = "spmega.properties";

    private ConfigManager() {
    }

    public static ModConfig loadOrCreate() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        Properties properties = new Properties();
        ModConfig defaults = ModConfig.createDefault();
        boolean shouldSave = false;

        if (Files.exists(configPath)) {
            try (InputStream inputStream = Files.newInputStream(configPath)) {
                properties.load(inputStream);
            } catch (IOException exception) {
                shouldSave = true;
            }
        } else {
            shouldSave = true;
        }

        String apiDomain = readString(properties, "api.domain", defaults.apiDomain());
        if (!apiDomain.equals(properties.getProperty("api.domain"))) {
            shouldSave = true;
        }

        String apiToken = readString(properties, "api.token", defaults.apiToken());
        if (!apiToken.equals(properties.getProperty("api.token"))) {
            shouldSave = true;
        }

        boolean allowAccess = readBoolean(properties, "allow.backend", defaults.allowBackend());
        String rawAllowAccess = properties.getProperty("allow.backend");
        if (rawAllowAccess == null || !Boolean.toString(allowAccess).equalsIgnoreCase(rawAllowAccess.trim())) {
            shouldSave = true;
        }

        boolean signQuickPayEnabled = readBoolean(properties, "sign.quickPay.enabled", defaults.signQuickPayEnabled());
        String rawQuickPay = properties.getProperty("sign.quickPay.enabled");
        if (rawQuickPay == null || !Boolean.toString(signQuickPayEnabled).equalsIgnoreCase(rawQuickPay.trim())) {
            shouldSave = true;
        }

        boolean gpsEnabled = readBoolean(properties, "gps.enabled", defaults.gpsEnabled());
        String rawGps = properties.getProperty("gps.enabled");
        if (rawGps == null || !Boolean.toString(gpsEnabled).equalsIgnoreCase(rawGps.trim())) {
            shouldSave = true;
        }

        GpsHudPosition gpsPosition = readEnum(properties, "gps.position", GpsHudPosition.class, defaults.gpsPosition());
        String rawPosition = properties.getProperty("gps.position");
        if (rawPosition == null || !gpsPosition.name().equalsIgnoreCase(rawPosition.trim())) {
            shouldSave = true;
        }

        GpsHudPosition notificationPosition = readEnum(
                properties, "notifications.position", GpsHudPosition.class, defaults.notificationPosition());
        String rawNotificationPosition = properties.getProperty("notifications.position");
        if (rawNotificationPosition == null
                || !notificationPosition.name().equalsIgnoreCase(rawNotificationPosition.trim())) {
            shouldSave = true;
        }

        int telemetryIntervalSeconds = readInt(properties, "telemetry.intervalSeconds", defaults.telemetryIntervalSeconds());
        String rawInterval = properties.getProperty("telemetry.intervalSeconds");
        if (rawInterval == null || !Integer.toString(telemetryIntervalSeconds).equals(rawInterval.trim())) {
            shouldSave = true;
        }

        boolean telemetryCollectSystemInfo = readBoolean(properties, "telemetry.collectSystemInfo", defaults.telemetryCollectSystemInfo());
        String rawSysInfo = properties.getProperty("telemetry.collectSystemInfo");
        if (rawSysInfo == null || !Boolean.toString(telemetryCollectSystemInfo).equalsIgnoreCase(rawSysInfo.trim())) {
            shouldSave = true;
        }

        ModConfig config = new ModConfig(apiDomain, apiToken, allowAccess, signQuickPayEnabled, gpsEnabled, gpsPosition,
                notificationPosition, telemetryIntervalSeconds, telemetryCollectSystemInfo);


        if (shouldSave) {
            save(configPath, config);
        }

        return config;

    }

    private static String readString(Properties properties, String key, String fallback) {
        String value = properties.getProperty(key);
        if (value == null) {
            return fallback;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static UUID readUuid(Properties properties, String key, UUID fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }

        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            // Self-heal broken UUID values by falling back to a valid default.
            return fallback;
        }
    }

    private static boolean readBoolean(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        if (value == null) {
            return fallback;
        }

        String normalized = value.trim().toLowerCase();
        if ("true".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized)) {
            return false;
        }
        return fallback;
    }

    public static void save(ModConfig config) {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        save(configPath, config);
    }

    private static void save(Path configPath, ModConfig config) {
        Properties properties = new Properties();
        properties.setProperty("api.domain", config.apiDomain());
        properties.setProperty("api.token", config.apiToken());
        properties.setProperty("allow.backend", Boolean.toString(config.allowBackend()));
        properties.setProperty("sign.quickPay.enabled", Boolean.toString(config.signQuickPayEnabled()));
        properties.setProperty("gps.enabled", Boolean.toString(config.gpsEnabled()));
        properties.setProperty("gps.position", config.gpsPosition().name());
        properties.setProperty("notifications.position", config.notificationPosition().name());
        properties.setProperty("telemetry.intervalSeconds", Integer.toString(config.telemetryIntervalSeconds()));
        properties.setProperty("telemetry.collectSystemInfo", Boolean.toString(config.telemetryCollectSystemInfo()));

        try {
            Files.createDirectories(configPath.getParent());
            try (OutputStream outputStream = Files.newOutputStream(configPath)) {
                properties.store(outputStream, "SPMega config");
            }
        } catch (IOException exception) {
            throw new RuntimeException("Failed to save config: " + configPath, exception);
        }
    }

    private static int readInt(Properties properties, String key, int fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static <E extends Enum<E>> E readEnum(Properties properties, String key, Class<E> enumClass, E fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Enum.valueOf(enumClass, value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }
}

