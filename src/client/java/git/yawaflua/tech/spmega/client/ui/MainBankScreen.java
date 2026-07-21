package git.yawaflua.tech.spmega.client.ui;

import git.yawaflua.tech.spmega.client.qr.QRCodeScanner;
import java.awt.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class MainBankScreen extends Screen {
    private final Screen parent;

    public MainBankScreen(Screen parent) {
        super(Component.literal("SPMega"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 2 + 4;
        int buttonWidth = 92;
        int gap = 8;
        int totalWidth = buttonWidth * 3 + gap * 2;
        int startX = centerX - totalWidth / 2;

        this.addRenderableWidget(Button.builder(Component.literal("Карта"), button -> {
            this.minecraft.gui.setScreen(new CardScreen(this));
        }).bounds(startX, y, buttonWidth, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Оплата"), button -> {
            this.minecraft.gui.setScreen(new PaymentScreen(this));
        }).bounds(startX + buttonWidth + gap, y, buttonWidth, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Закрыть"), button -> this.onClose())
                .bounds(startX + (buttonWidth + gap) * 2, y, buttonWidth, 20)
                .build());

        this.addRenderableWidget(Button.builder(Component.translatable("button.spmega.scan_qr"), button -> {
            this.minecraft.gui.setScreen(null);
            QRCodeScanner.scanQrCode(this.minecraft);
        }).bounds(centerX - 60, y + 28, 120, 20).build());
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(parent);
        }
    }

    @Override
    /*? if mc_26 {*/
     public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
    /*?} else {*/
    /*public void render(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
    *//*?}*/
        /*? if mc_26 {*/
         super.extractRenderState(context, mouseX, mouseY, delta);
        /*?} else {*/
        /*super.render(context, mouseX, mouseY, delta);
        *//*?}*/

        context.centeredText(this.font, this.title, this.width / 2, 24, 0xFFFFFF);
        context.centeredText(this.font, Component.literal(greetingLine()), this.width / 2, 48, Color.WHITE.getRGB());
    }

    private String greetingLine() {
        Minecraft minecraftClient = Minecraft.getInstance();
        String username = minecraftClient.getUser().getName();
        return "Доброе " + getTimeOfDay() + ", " + username;
    }

    private String getTimeOfDay() {
        Minecraft minecraftClient = Minecraft.getInstance();
        if (minecraftClient.level == null) {
            return "время суток";
        }

        long dayTime = minecraftClient.level.getOverworldClockTime() % 24000L;
        if (dayTime < 6000) {
            return "утро";
        }
        if (dayTime < 12000) {
            return "день";
        }
        if (dayTime < 18000) {
            return "вечер";
        }
        return "ночь";
    }
}
