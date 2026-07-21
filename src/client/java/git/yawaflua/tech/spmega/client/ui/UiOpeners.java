package git.yawaflua.tech.spmega.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public final class UiOpeners {
    private UiOpeners() {
    }

    public static void openMainMenu(Minecraft client) {
        if (client == null) {
            return;
        }

        Screen current = client.gui.screen();
        client.gui.setScreen(new MainBankScreen(current));
    }

    public static void openPaymentMenu(Minecraft client, String recipient) {
        if (client == null) {
            return;
        }

        Screen current = client.gui.screen();
        client.gui.setScreen(new PaymentScreen(current, recipient));
    }
}

