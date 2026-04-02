package git.yawaflua.tech.spmega.client;

import git.yawaflua.tech.spmega.SPMega;
import git.yawaflua.tech.spmega.client.ui.UiOpeners;
import git.yawaflua.tech.spmega.client.ui.service.BankUiService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import org.lwjgl.glfw.GLFW;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SPMegaClient implements ClientModInitializer {
    private static final Pattern ISOLATED_FIVE_DIGITS = Pattern.compile("(?<!\\d)(\\d{5})(?!\\d)");
    private static KeyBinding openBankMenuKeyBinding;

    private static String extractCardNumber(SignBlockEntity signBlockEntity) {
        String candidate = findFiveDigits(signBlockEntity.getFrontText().getMessages(false));
        if (candidate != null) {
            return candidate;
        }
        return findFiveDigits(signBlockEntity.getBackText().getMessages(false));
    }

    private static String findFiveDigits(Text[] lines) {
        for (Text line : lines) {
            Matcher matcher = ISOLATED_FIVE_DIGITS.matcher(line.getString());
            if (matcher.find()) {
                return matcher.group(1);
            }
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

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openBankMenuKeyBinding.wasPressed()) {
                UiOpeners.openMainMenu(client);
            }
        });

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

            if (!(world.getBlockEntity(hitResult.getBlockPos()) instanceof SignBlockEntity signBlockEntity)) {
                return ActionResult.PASS;
            }

            String cardNumber = extractCardNumber(signBlockEntity);
            if (cardNumber == null) {
                return ActionResult.PASS;
            }

            UiOpeners.openPaymentMenu(MinecraftClient.getInstance(), cardNumber);
            return ActionResult.SUCCESS;
        });
    }
}
