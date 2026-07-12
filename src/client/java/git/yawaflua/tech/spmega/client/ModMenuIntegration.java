package git.yawaflua.tech.spmega.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import git.yawaflua.tech.spmega.GpsHudPosition;
import git.yawaflua.tech.spmega.ModConfig;
import git.yawaflua.tech.spmega.SPMega;
import git.yawaflua.tech.spmega.client.ui.service.BackendAuthenticator;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            ConfigBuilder builder = ConfigBuilder.create()
                    .setParentScreen(parent)
                    .setTitle(Text.translatable("title.spmega.config"));

            ConfigEntryBuilder entryBuilder = builder.entryBuilder();
            ConfigCategory general = builder.getOrCreateCategory(Text.translatable("category.spmega.general"));

            ModConfig current = SPMega.getConfig();
            if (current == null) {
                current = ModConfig.createDefault();
            }

            final String[] apiDomain = {current.apiDomain()};
            final String[] apiToken = {current.apiToken()};
            final boolean[] allowBackend = {current.allowBackend()};
            final boolean[] signQuickPayEnabled = {current.signQuickPayEnabled()};
            final GpsHudPosition[] gpsPosition = {current.gpsPosition()};
            final GpsHudPosition[] notificationPosition = {current.notificationPosition()};

            final int[] telemetryIntervalSeconds = {current.telemetryIntervalSeconds()};
            final boolean[] telemetryCollectSystemInfo = {current.telemetryCollectSystemInfo()};

            general.addEntry(entryBuilder.startStrField(Text.translatable("option.spmega.api_domain"), current.apiDomain())
                    .setDefaultValue(ModConfig.DEFAULT_API_DOMAIN)
                    .setSaveConsumer(newValue -> apiDomain[0] = newValue)
                    .build());

            general.addEntry(entryBuilder.startStrField(Text.translatable("option.spmega.api_token"), current.apiToken())
                    .setDefaultValue(ModConfig.DEFAULT_API_TOKEN)
                    .setSaveConsumer(newValue -> apiToken[0] = newValue)
                    .build());

            general.addEntry(entryBuilder.startBooleanToggle(Text.translatable("option.spmega.allow_backend"), current.allowBackend())
                    .setDefaultValue(ModConfig.ALLOW_BACKEND)
                    .setSaveConsumer(newValue -> allowBackend[0] = newValue)
                    .build());

            boolean isLoggedIn = !current.apiToken().isEmpty() && !current.apiToken().equals(ModConfig.DEFAULT_API_TOKEN);
            general.addEntry(entryBuilder.startBooleanToggle(Text.literal("Авторизоваться").formatted(Formatting.RED), isLoggedIn)
                    .setTooltip(Text.literal("Запустить процесс авторизации через Mojang и ваш бэкенд"))
                    .setSaveConsumer(newValue -> {
                        if (newValue) {
                            BackendAuthenticator.authenticateAsync(MinecraftClient.getInstance())
                                    .thenAccept(authenticated -> {
                                        if (authenticated && SPMega.getConfig() != null) {
                                            apiToken[0] = SPMega.getConfig().apiToken();
                                        }
                                    });
                        } else {
                            apiToken[0] = ModConfig.DEFAULT_API_TOKEN;
                        }
                    })
                    .build());

            general.addEntry(entryBuilder.startBooleanToggle(Text.translatable("option.spmega.sign_quick_pay"), current.signQuickPayEnabled())
                    .setDefaultValue(ModConfig.DEFAULT_SIGN_QUICK_PAY_ENABLED)
                    .setSaveConsumer(newValue -> signQuickPayEnabled[0] = newValue)
                    .build());

            general.addEntry(entryBuilder.startEnumSelector(
                            Text.translatable("option.spmega.gps_position"),
                            GpsHudPosition.class,
                            current.gpsPosition()
                    )
                    .setDefaultValue(ModConfig.DEFAULT_GPS_POSITION)
                    .setEnumNameProvider(position -> Text.translatable("option.spmega.gps_position." + position.name().toLowerCase()))
                    .setSaveConsumer(newValue -> gpsPosition[0] = newValue)
                    .build());

            general.addEntry(entryBuilder.startEnumSelector(
                            Text.translatable("option.spmega.notification_position"),
                            GpsHudPosition.class,
                            current.notificationPosition()
                    )
                    .setDefaultValue(ModConfig.DEFAULT_NOTIFICATION_POSITION)
                    .setEnumNameProvider(position -> Text.translatable(
                            "option.spmega.notification_position." + position.name().toLowerCase()))
                    .setSaveConsumer(newValue -> notificationPosition[0] = newValue)
                    .build());

            // Telemetry settings
            ConfigCategory telemetry = builder.getOrCreateCategory(Text.translatable("category.spmega.telemetry"));

            telemetry.addEntry(entryBuilder.startIntField(Text.translatable("option.spmega.telemetry_interval"), current.telemetryIntervalSeconds())
                    .setDefaultValue(ModConfig.DEFAULT_TELEMETRY_INTERVAL_SECONDS)
                    .setMin(10)
                    .setMax(600)
                    .setTooltip(Text.translatable("tooltip.spmega.telemetry_interval"))
                    .setSaveConsumer(newValue -> telemetryIntervalSeconds[0] = newValue)
                    .build());

            telemetry.addEntry(entryBuilder.startBooleanToggle(Text.translatable("option.spmega.telemetry_system_info"), current.telemetryCollectSystemInfo())
                    .setDefaultValue(ModConfig.DEFAULT_TELEMETRY_COLLECT_SYSTEM_INFO)
                    .setTooltip(Text.translatable("tooltip.spmega.telemetry_system_info"))
                    .setSaveConsumer(newValue -> telemetryCollectSystemInfo[0] = newValue)
                    .build());

            builder.setSavingRunnable(() -> {
                boolean gpsEnabledVal = true;
                if (SPMega.getConfig() != null) {
                    gpsEnabledVal = SPMega.getConfig().gpsEnabled();
                }
                ModConfig updated = new ModConfig(
                        apiDomain[0],
                        apiToken[0],
                        allowBackend[0],
                        signQuickPayEnabled[0],
                        gpsEnabledVal,
                        gpsPosition[0],
                        notificationPosition[0],
                        telemetryIntervalSeconds[0],
                        telemetryCollectSystemInfo[0]
                );
                SPMega.setConfig(updated);
            });

            return builder.build();
        };
    }
}
