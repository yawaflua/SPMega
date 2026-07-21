package git.yawaflua.tech.spmega.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
/*? if mc_1_21_11 {*/
import net.minecraft.util.Util;
/*?} else {*/
// import net.minecraft.Util;
/*?}*/

import java.awt.*;
import java.net.URI;

public class QRcodeAcceptScreen extends Screen {
    private final String url;
    private final Screen parent;

    public QRcodeAcceptScreen(String url, Screen parent) {
        super(Component.translatable("screen.spmega.qr.confirm_title"));
        this.url = url;
        this.parent = parent;
    }

    @Override
    protected void init() {


        this.addRenderableWidget(Button.builder(Component.translatable("button.spmega.qr.cancel"), button -> {
            if (this.minecraft != null) {
                this.minecraft.gui.setScreen(parent);
            }
        }).bounds(this.width / 2 - 155, this.height / 2 + 30, 150, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("button.spmega.qr.open_link"), button -> {
            try {
                Util.getPlatform().openUri(new URI(url));
            } catch (Exception exception) {
                if (this.minecraft != null && this.minecraft.player != null) {
                    /*? if mc_26 {*/
                     this.minecraft.player.sendSystemMessage(Component.translatable("message.spmega.qr.failed_open"));
                    /*?} else {*/
                    /*this.minecraft.player.displayClientMessage(Component.translatable("message.spmega.qr.failed_open"), false);
                    *//*?}*/
                }
            }
            if (this.minecraft != null) {
                this.minecraft.gui.setScreen(parent);
            }
        }).bounds(this.width / 2 + 5, this.height / 2 + 30, 150, 20).build());
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

        context.centeredText(
                this.font,
                Component.literal("Найдена ссылка: " + url),
                this.width / 2,
                this.height / 2,
                Color.WHITE.getRGB()
        );
    }
}
