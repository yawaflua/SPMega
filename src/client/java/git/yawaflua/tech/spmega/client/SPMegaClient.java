package git.yawaflua.tech.spmega.client;

import git.yawaflua.tech.spmega.SPMega;
import git.yawaflua.tech.spmega.client.qr.QRCodeScanner;
import git.yawaflua.tech.spmega.client.ui.UiNotifications;
import git.yawaflua.tech.spmega.client.ui.UiOpeners;
import git.yawaflua.tech.spmega.client.ui.service.BankUiService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import org.lwjgl.glfw.GLFW;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SPMegaClient implements ClientModInitializer {
    private static final Pattern ISOLATED_FIVE_DIGITS = Pattern.compile("(?<!\\d)(\\d{5})(?!\\d)");
    private static KeyBinding openBankMenuKeyBinding;
    private static KeyBinding scanQrKeyBinding;

    private static String extractCardNumber(SignBlockEntity signBlockEntity) {
        String candidate = findFiveDigits(signBlockEntity.getFrontText().getMessages(false));
        if (candidate != null) {
            return candidate;
        }
        return findFiveDigits(signBlockEntity.getBackText().getMessages(false));
    }

    private static String extractCardNumber(ItemFrameEntity itemFrameEntity) {
        if (itemFrameEntity.getHeldItemStack().isEmpty()) {
            return null;
        }
        return findFiveDigits(itemFrameEntity.getHeldItemStack().getName().getString());
    }

    private static String findFiveDigits(Text[] lines) {
        for (Text line : lines) {
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
        openBankMenuKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.spmega.open_menu",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                KeyBinding.Category.GAMEPLAY
        ));

        scanQrKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.spmega.scan_qr",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                KeyBinding.Category.GAMEPLAY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            UiNotifications.instance().tick();
            while (openBankMenuKeyBinding.wasPressed()) {
                UiOpeners.openMainMenu(client);
            }
            while (scanQrKeyBinding.wasPressed()) {
                QRCodeScanner.ScanQrCode(client);
            }
        });

        // World HUD path (when no screen is open).
        HudRenderCallback.EVENT.register((drawContext, tickDeltaManager) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.currentScreen != null || client.textRenderer == null) {
                return;
            }
            UiNotifications.instance().render(
                    drawContext,
                    client.textRenderer,
                    client.getWindow().getScaledWidth(),
                    client.getWindow().getScaledHeight()
            );
        });

        // Screen path (for all GUI screens).
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) ->
                ScreenEvents.afterRender(screen).register((screenInstance, drawContext, mouseX, mouseY, tickDelta) -> {
                    if (client.textRenderer == null) {
                        return;
                    }
                    UiNotifications.instance().render(drawContext, client.textRenderer, scaledWidth, scaledHeight);
                })
        );

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (client.player != null) {
                BankUiService.instance().refreshOnServerJoin(client.player.getUuidAsString());
            }
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClient()) {
                return ActionResult.PASS;
            }
            if (!player.isSneaking()) {
                return ActionResult.PASS;
            }
            if (SPMega.getConfig() == null || !SPMega.getConfig().signQuickPayEnabled()) {
                return ActionResult.PASS;
            }

            if (world.getBlockEntity(hitResult.getBlockPos()) instanceof SignBlockEntity signBlockEntity) {
                String cardNumber = extractCardNumber(signBlockEntity);
                if (cardNumber == null) {
                    return ActionResult.PASS;
                }

                UiOpeners.openPaymentMenu(MinecraftClient.getInstance(), cardNumber);
                return ActionResult.SUCCESS;
            } else {
                return ActionResult.PASS;
            }

        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClient()) {
                return ActionResult.PASS;
            }
            if (!player.isSneaking()) {
                return ActionResult.PASS;
            }
            if (SPMega.getConfig() == null || !SPMega.getConfig().signQuickPayEnabled()) {
                return ActionResult.PASS;
            }
            if (entity instanceof ItemFrameEntity itemFrameEntity) {
                String cardNumber = extractCardNumber(itemFrameEntity);
                if (cardNumber == null) {
                    return ActionResult.PASS;
                }

                UiOpeners.openPaymentMenu(MinecraftClient.getInstance(), cardNumber);
                return ActionResult.SUCCESS;
            } else {
                return ActionResult.PASS;
            }

        });
    }
}
