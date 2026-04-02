package git.yawaflua.tech.spmega.client.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

public final class UiOpeners {
    private UiOpeners() {
    }

    public static void openMainMenu(MinecraftClient client) {
        if (client == null) {
            return;
        }

        Screen current = client.currentScreen;
        client.setScreen(new MainBankScreen(current));
    }

    public static void openPaymentMenu(MinecraftClient client, String recipient) {
        if (client == null) {
            return;
        }

        Screen current = client.currentScreen;
        client.setScreen(new PaymentScreen(current, recipient));
    }
}

