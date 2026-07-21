package git.yawaflua.tech.spmega.client;

import com.mojang.blaze3d.platform.InputConstants;
import git.yawaflua.tech.spmega.ModConfig;
import git.yawaflua.tech.spmega.SPMega;
import git.yawaflua.tech.spmega.client.qr.QRCodeScanner;
import git.yawaflua.tech.spmega.client.telemetry.*;
import git.yawaflua.tech.spmega.client.ui.GpsHudRenderer;
import git.yawaflua.tech.spmega.client.ui.UiNotifications;
import git.yawaflua.tech.spmega.client.ui.UiOpeners;
import git.yawaflua.tech.spmega.client.ui.service.BackendAuthenticator;
import git.yawaflua.tech.spmega.client.ui.service.BankUiService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
/*? if mc_26 {*/
 import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
 import net.minecraft.resources.Identifier;
/*?} else {*/
/*import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
*//*?}*/
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.lwjgl.glfw.GLFW;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SPMegaClient implements ClientModInitializer {
    private static final Pattern ISOLATED_FIVE_DIGITS = Pattern.compile("(?<!\\d)(\\d{5})(?!\\d)");
    private static KeyMapping openBankMenuKeyBinding;
    private static KeyMapping scanQrKeyBinding;
    private static KeyMapping toggleGpsKeyBinding;
    private static boolean wasPaymentShortcutPressed = false;


    private static String extractCardNumber(SignBlockEntity signBlockEntity) {
        String candidate = findFiveDigits(signBlockEntity.getFrontText().getMessages(false));
        if (candidate != null) {
            return candidate;
        }
        return findFiveDigits(signBlockEntity.getBackText().getMessages(false));
    }

    private static String extractCardNumber(ItemFrame itemFrameEntity) {
        if (itemFrameEntity.getItem().isEmpty()) {
            return null;
        }
        return findFiveDigits(itemFrameEntity.getItem().getHoverName().getString());
    }

    private static String findFiveDigits(Component[] lines) {
        for (Component line : lines) {
            String candidate = findFiveDigits(line.getString());
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static String findFiveDigits(String text) {
        Matcher matcher = ISOLATED_FIVE_DIGITS.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    @Override
    public void onInitializeClient() {
        GpsHudRenderer.instance();

        UiNotifications.instance().showMessage("Этот мод собирает телеметрию. Проверьте настройки.");


        openBankMenuKeyBinding = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.spmega.open_menu",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                /*? if mc_1_21_11 {*/
                KeyMapping.Category.GAMEPLAY
                /*?} else {*/
                // "key.categories.gameplay"
                /*?}*/
        ));

        scanQrKeyBinding = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.spmega.scan_qr",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                /*? if mc_1_21_11 {*/
                KeyMapping.Category.GAMEPLAY
                /*?} else {*/
                // "key.categories.gameplay"
                /*?}*/
        ));

        toggleGpsKeyBinding = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.spmega.toggle_gps",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                /*? if mc_1_21_11 {*/
                KeyMapping.Category.GAMEPLAY
                /*?} else {*/
                // "key.categories.gameplay"
                /*?}*/
        ));

        if (SPMega.getConfig() != null) {
            GpsHudRenderer.instance().setEnabled(SPMega.getConfig().gpsEnabled());
        }

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            PerformanceSampler.instance().tick();
            UiNotifications.instance().tick();
            BackendAuthenticator.tickTokenRefresh(client);
            while (openBankMenuKeyBinding.consumeClick()) {
                UiOpeners.openMainMenu(client);
            }
            while (scanQrKeyBinding.consumeClick()) {
                QRCodeScanner.scanQrCode(client);
            }
            while (toggleGpsKeyBinding.consumeClick()) {
                GpsHudRenderer.instance().toggle();
                if (SPMega.getConfig() != null) {
                    ModConfig current = SPMega.getConfig();
                    ModConfig updated = new ModConfig(
                            current.apiDomain(),
                            current.apiToken(),
                            current.allowBackend(),
                            current.signQuickPayEnabled(),
                            GpsHudRenderer.instance().isEnabled(),
                            current.gpsPosition(),
                            current.notificationPosition(),
                            current.telemetryIntervalSeconds(),
                            current.telemetryCollectSystemInfo()
                    );
                    SPMega.setConfig(updated);
                }
                String status = GpsHudRenderer.instance().isEnabled() ? "включен" : "выключен";
                UiNotifications.instance().show(Component.literal("GPS Ада " + status));
            }

            if (client.player != null && client.options != null) {
                boolean isCombinationPressed = client.options.keySprint.isDown() && client.options.keyShift.isDown() && client.options.keyLeft.isDown();
                if (isCombinationPressed) {
                    if (!wasPaymentShortcutPressed && client.gui.screen() == null) {
                        if (client.crosshairPickEntity instanceof Player targetedPlayer && targetedPlayer != client.player) {
                            String recipient = targetedPlayer.getScoreboardName();
                            UiOpeners.openPaymentMenu(client, recipient);
                        }
                    }
                    wasPaymentShortcutPressed = true;
                } else {
                    wasPaymentShortcutPressed = false;
                }
            }
        });

        /*? if mc_26 {*/
         HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("spmega", "main_hud"), (drawContext, tickDeltaManager) -> {
        /*?} else {*/
        /*HudRenderCallback.EVENT.register((drawContext, tickDeltaManager) -> {
        *//*?}*/
            Minecraft client = Minecraft.getInstance();
            if (client.gui.screen() != null || client.font == null) {
                return;
            }
            UiNotifications.instance().render(
                    drawContext,
                    client.font,
                    client.getWindow().getGuiScaledWidth(),
                    client.getWindow().getGuiScaledHeight()
            );
            GpsHudRenderer.instance().render(
                    drawContext,
                    client.font,
                    client.getWindow().getGuiScaledWidth(),
                    client.getWindow().getGuiScaledHeight()
            );
        });

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
                    ScreenEvents.afterExtract(screen).register((screenInstance, drawContext, mouseX, mouseY, tickDelta) -> {
                        if (client.font == null) {
                            return;
                        }
                        UiNotifications.instance().render(drawContext, client.font, scaledWidth, scaledHeight);
                    });

                }
        );

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (client.player != null) {
                BankUiService.instance().refreshOnServerJoinAsync(client.player.getStringUUID());
            }
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide()) {
                return InteractionResult.PASS;
            }
            if (!player.isShiftKeyDown()) {
                return InteractionResult.PASS;
            }
            if (SPMega.getConfig() == null || !SPMega.getConfig().signQuickPayEnabled()) {
                return InteractionResult.PASS;
            }

            if (world.getBlockEntity(hitResult.getBlockPos()) instanceof SignBlockEntity signBlockEntity) {
                String cardNumber = extractCardNumber(signBlockEntity);
                if (cardNumber == null) {
                    return InteractionResult.PASS;
                }

                UiOpeners.openPaymentMenu(Minecraft.getInstance(), cardNumber);
                return InteractionResult.SUCCESS;
            } else {
                return InteractionResult.PASS;
            }

        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClientSide()) {
                return InteractionResult.PASS;
            }
            if (!player.isShiftKeyDown()) {
                return InteractionResult.PASS;
            }
            if (SPMega.getConfig() == null || !SPMega.getConfig().signQuickPayEnabled()) {
                return InteractionResult.PASS;
            }
            if (entity instanceof ItemFrame itemFrameEntity) {
                String cardNumber = extractCardNumber(itemFrameEntity);
                if (cardNumber == null) {
                    return InteractionResult.PASS;
                }

                UiOpeners.openPaymentMenu(Minecraft.getInstance(), cardNumber);
                return InteractionResult.SUCCESS;
            } else {
                return InteractionResult.PASS;
            }

        });

        new ChatListener().register();
        WebhookNotificationPoller.instance().start();

        // Telemetry init
        long clientInitStart = System.nanoTime();
        ModConfig cfg = SPMega.getConfig();
        int interval = (cfg != null) ? cfg.telemetryIntervalSeconds() : ModConfig.DEFAULT_TELEMETRY_INTERVAL_SECONDS;
        SystemInfoCollector.instance().init();
        TelemetrySender.instance().start(interval);
        long clientInitMs = (System.nanoTime() - clientInitStart) / 1_000_000L;
        com.google.gson.JsonObject initPayload = new com.google.gson.JsonObject();
        initPayload.addProperty("phase", "client_init");
        initPayload.addProperty("durationMs", clientInitMs);
        TelemetryCollector.instance().record(TelemetryEvent.now("lifecycle", initPayload));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            com.google.gson.JsonObject deinitPayload = new com.google.gson.JsonObject();
            deinitPayload.addProperty("phase", "client_deinit");
            TelemetryCollector.instance().record(TelemetryEvent.now("lifecycle", deinitPayload));
            TelemetrySender.instance().flush();
            TelemetrySender.instance().stop();
        }, "SPMega-Telemetry-Shutdown"));

        System.out.println("Author of SPMega make it with 4 cans of monster");
        System.out.println("If u want to see more updates - give me like 10 shekels for monster plzzz");
        System.out.println("Initialized beshalom! Tieie tovim!");
    }
}
