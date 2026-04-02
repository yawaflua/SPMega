package git.yawaflua.tech.spmega.client.ui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.net.URI;

public class QRcodeAcceptScreen extends Screen {
    private final String url;
    private final Screen parent;

    public QRcodeAcceptScreen(String url, Screen parent) {
        super(Text.translatable("screen.spmega.qr.confirm_title"));
        this.url = url;
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("button.spmega.qr.cancel"), button -> {
            if (this.client != null) {
                this.client.setScreen(parent);
            }
        }).dimensions(this.width / 2 - 155, this.height / 2 + 30, 150, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.translatable("button.spmega.qr.open_link"), button -> {
            try {
                Util.getOperatingSystem().open(new URI(url));
            } catch (Exception exception) {
                if (this.client != null && this.client.player != null) {
                    this.client.player.sendMessage(Text.translatable("message.spmega.qr.failed_open"), false);
                }
            }
            if (this.client != null) {
                this.client.setScreen(parent);
            }
        }).dimensions(this.width / 2 + 5, this.height / 2 + 30, 150, 20).build());
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(
                this.textRenderer,
                Text.translatable("screen.spmega.qr.accept_link"),
                this.width / 2,
                this.height / 2 - 30,
                0xFFFFFF
        );
        context.drawCenteredTextWithShadow(
                this.textRenderer,
                Text.literal(url),
                this.width / 2,
                this.height / 2 - 10,
                0xFFD27F
        );
    }
}

