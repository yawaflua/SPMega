package git.yawaflua.tech.spmega.client.ui;

import git.yawaflua.tech.spmega.client.ui.service.BankUiService;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class AddCardScreen extends Screen {
    private final Screen parent;
    private final Consumer<String> onSubmitMessage;
    private final BankUiService bankUiService = BankUiService.instance();
    private final UiNotifications notifications = UiNotifications.instance();
    private EditBox cardIdField;
    private EditBox cardTokenField;
    private Button addButton;
    private Button cancelButton;

    public AddCardScreen(Screen parent, Consumer<String> onSubmitMessage) {
        super(Component.literal("Добавление карты"));
        this.parent = parent;
        this.onSubmitMessage = onSubmitMessage;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int startY = this.height / 2 - 50;

        cardIdField = new EditBox(this.font, centerX - 140, startY, 280, 20, Component.literal("Card ID"));
        cardIdField.setMaxLength(64);
        cardIdField.setHint(Component.literal("ID карты (UUID)"));
        this.addRenderableWidget(cardIdField);

        cardTokenField = new EditBox(this.font, centerX - 140, startY + 28, 280, 20, Component.literal("Card Token"));
        cardTokenField.setMaxLength(128);
        cardTokenField.setHint(Component.literal("Токен карты"));
        this.addRenderableWidget(cardTokenField);

        addButton = this.addRenderableWidget(Button.builder(Component.literal("Добавить"), button -> submit())
                .bounds(centerX - 140, startY + 60, 136, 20)
                .build());

        cancelButton = this.addRenderableWidget(Button.builder(Component.literal("Отмена"), button -> this.onClose())
                .bounds(centerX + 4, startY + 60, 136, 20)
                .build());
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

        int centerX = this.width / 2;
        int startY = this.height / 2 - 50;

        context.centeredText(this.font, this.title, centerX, 24, 0xFFFFFF);
        context.text(this.font, Component.literal("Card ID (UUID):"), centerX - 140, startY - 10, 0xCCCCCC);
        context.text(this.font, Component.literal("Card Token:"), centerX - 140, startY + 18, 0xCCCCCC);
    }

    private void submit() {
        String cardId = cardIdField.getValue().trim();
        String cardToken = cardTokenField.getValue().trim();
        if (cardId.isEmpty() || cardToken.isEmpty()) {
            notifications.show(Component.literal("Укажи cardId и cardToken"));
            return;
        }

        String playerUuid = this.minecraft != null && this.minecraft.player != null
                ? this.minecraft.player.getStringUUID()
                : "";

        notifications.show(Component.literal("Проверка карты..."));
        addButton.active = false;
        cancelButton.active = false;

        bankUiService.addCardAsync(cardId, cardToken, playerUuid)
                .thenAccept(message -> {
                    if (this.minecraft == null) {
                        return;
                    }
                    this.minecraft.execute(() -> {
                        notifications.showMessage(message);
                        addButton.active = true;
                        cancelButton.active = true;

                        if (onSubmitMessage != null) {
                            onSubmitMessage.accept(message);
                        }

                        if (message.startsWith("Карта добавлена") || message.startsWith("Вы не владелец карты")) {
                            this.onClose();
                        }
                    });
                })
                .exceptionally(exception -> {
                    if (this.minecraft != null) {
                        this.minecraft.execute(() -> {
                            notifications.show(Component.literal("Ошибка добавления карты: " + exception.getMessage()));
                            addButton.active = true;
                            cancelButton.active = true;
                        });
                    }
                    return null;
                });
    }
}
