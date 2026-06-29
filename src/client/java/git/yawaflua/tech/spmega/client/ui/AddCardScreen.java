package git.yawaflua.tech.spmega.client.ui;

import git.yawaflua.tech.spmega.client.ui.service.BankUiService;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.function.Consumer;

public class AddCardScreen extends Screen {
    private final Screen parent;
    private final Consumer<String> onSubmitMessage;
    private final BankUiService bankUiService = BankUiService.instance();
    private final UiNotifications notifications = UiNotifications.instance();
    private TextFieldWidget cardIdField;
    private TextFieldWidget cardTokenField;
    private ButtonWidget addButton;
    private ButtonWidget cancelButton;

    public AddCardScreen(Screen parent, Consumer<String> onSubmitMessage) {
        super(Text.literal("Добавление карты"));
        this.parent = parent;
        this.onSubmitMessage = onSubmitMessage;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int startY = this.height / 2 - 50;

        cardIdField = new TextFieldWidget(this.textRenderer, centerX - 140, startY, 280, 20, Text.literal("Card ID"));
        cardIdField.setMaxLength(64);
        cardIdField.setPlaceholder(Text.literal("ID карты (UUID)"));
        this.addDrawableChild(cardIdField);

        cardTokenField = new TextFieldWidget(this.textRenderer, centerX - 140, startY + 28, 280, 20, Text.literal("Card Token"));
        cardTokenField.setMaxLength(128);
        cardTokenField.setPlaceholder(Text.literal("Токен карты"));
        this.addDrawableChild(cardTokenField);

        addButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Добавить"), button -> submit())
                .dimensions(centerX - 140, startY + 60, 136, 20)
                .build());

        cancelButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Отмена"), button -> this.close())
                .dimensions(centerX + 4, startY + 60, 136, 20)
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

        int centerX = this.width / 2;
        int startY = this.height / 2 - 50;

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, 24, 0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Card ID (UUID):"), centerX - 140, startY - 10, 0xCCCCCC);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Card Token:"), centerX - 140, startY + 18, 0xCCCCCC);
    }

    private void submit() {
        String cardId = cardIdField.getText().trim();
        String cardToken = cardTokenField.getText().trim();
        if (cardId.isEmpty() || cardToken.isEmpty()) {
            notifications.show(Text.literal("Укажи cardId и cardToken"));
            return;
        }

        String playerUuid = this.client != null && this.client.player != null
                ? this.client.player.getUuidAsString()
                : "";

        notifications.show(Text.literal("Проверка карты..."));
        addButton.active = false;
        cancelButton.active = false;

        bankUiService.addCardAsync(cardId, cardToken, playerUuid)
                .thenAccept(message -> {
                    if (this.client == null) {
                        return;
                    }
                    this.client.execute(() -> {
                        notifications.showMessage(message);
                        addButton.active = true;
                        cancelButton.active = true;

                        if (onSubmitMessage != null) {
                            onSubmitMessage.accept(message);
                        }

                        if (message.startsWith("Карта добавлена") || message.startsWith("Вы не владелец карты")) {
                            this.close();
                        }
                    });
                })
                .exceptionally(exception -> {
                    if (this.client != null) {
                        this.client.execute(() -> {
                            notifications.show(Text.literal("Ошибка добавления карты: " + exception.getMessage()));
                            addButton.active = true;
                            cancelButton.active = true;
                        });
                    }
                    return null;
                });
    }
}

