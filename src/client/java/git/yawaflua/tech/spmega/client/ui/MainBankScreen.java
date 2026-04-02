package git.yawaflua.tech.spmega.client.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class MainBankScreen extends Screen {
    private final Screen parent;

    public MainBankScreen(Screen parent) {
        super(Text.literal("SPMega"));
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

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Карта"), button -> {
            this.client.setScreen(new CardScreen(this));
        }).dimensions(startX, y, buttonWidth, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Оплата"), button -> {
            this.client.setScreen(new PaymentScreen(this));
        }).dimensions(startX + buttonWidth + gap, y, buttonWidth, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Закрыть"), button -> this.close())
                .dimensions(startX + (buttonWidth + gap) * 2, y, buttonWidth, 20)
                .build());
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

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 24, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(greetingLine()), this.width / 2, 48, 0xFFD27F);
    }

    private String greetingLine() {
        MinecraftClient minecraftClient = MinecraftClient.getInstance();
        String username = minecraftClient.getSession().getUsername();
        return "Доброе " + getTimeOfDay() + ", " + username;
    }

    private String getTimeOfDay() {
        MinecraftClient minecraftClient = MinecraftClient.getInstance();
        if (minecraftClient.world == null) {
            return "время суток";
        }

        long dayTime = minecraftClient.world.getTimeOfDay() % 24000L;
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
