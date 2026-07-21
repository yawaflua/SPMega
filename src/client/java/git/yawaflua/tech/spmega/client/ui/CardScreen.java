package git.yawaflua.tech.spmega.client.ui;

import git.yawaflua.tech.spmega.client.ui.service.BankUiService;
import git.yawaflua.tech.spmega.client.ui.service.CardViewModel;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class CardScreen extends Screen {
    private final Screen parent;
    private final BankUiService bankUiService = BankUiService.instance();
    private final UiNotifications notifications = UiNotifications.instance();
    private final List<Button> cardButtons = new ArrayList<>();
    private Button historyButton;
    private Button webhookButton;

    public CardScreen(Screen parent) {
        super(Component.literal("Управление картами"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        cardButtons.clear();

        int centerX = this.width / 2;
        int startY = this.height / 2 - 82;
        int leftX = centerX - 210;
        int rightX = centerX + 14;
        int columnWidth = 196;

        List<CardViewModel> cards = bankUiService.getCards();
        for (int i = 0; i < cards.size(); i++) {
            final int index = i;
            CardViewModel card = cards.get(i);
            Button cardButton = this.addRenderableWidget(Button.builder(Component.literal(card.title()), button -> {
                bankUiService.setSelectedCardIndex(index);
                notifications.show(Component.literal("Выбрана карта " + card.title()));
                updateCardButtonStates();
            }).bounds(leftX, startY + i * 24, columnWidth, 20).build());
            cardButtons.add(cardButton);
        }

        this.addRenderableWidget(Button.builder(Component.literal("Удалить"), button -> {
            bankUiService.removeSelectedCardAsync()
                    .thenAccept(message -> {
                        if (this.minecraft != null) {
                            this.minecraft.execute(() -> {
                                notifications.showMessage(message);
                                this.rebuildWidgets();
                            });
                        }
                    });
        }).bounds(rightX, startY, columnWidth, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Обновить"), button -> {
            String playerUuid = this.minecraft != null && this.minecraft.player != null
                    ? this.minecraft.player.getStringUUID()
                    : "";
            bankUiService.refreshSelectedCardAsync(playerUuid)
                    .thenAccept(message -> {
                        if (this.minecraft != null) {
                            this.minecraft.execute(() -> {
                                notifications.showMessage(message);
                                this.rebuildWidgets();
                            });
                        }
                    });
        }).bounds(rightX, startY + 24, columnWidth, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Добавить новую"), button -> {
            if (this.minecraft == null) {
                return;
            }
            this.minecraft.gui.setScreen(new AddCardScreen(this, message -> {
                notifications.showMessage(message);
                this.rebuildWidgets();
            }));
        }).bounds(rightX, startY + 48, columnWidth, 20).build());

        historyButton = this.addRenderableWidget(Button.builder(Component.literal("История"), button -> {
            if (this.minecraft == null) {
                return;
            }
            CardViewModel selected = bankUiService.getSelectedCard();
            if (selected == null) {
                notifications.show(Component.literal("Сначала выбери или добавь карту"));
                return;
            }
            this.minecraft.gui.setScreen(new TransactionHistoryScreen(this, selected.id(), selected.title()));
        }).bounds(rightX, startY + 72, columnWidth, 20).build());

        webhookButton = this.addRenderableWidget(Button.builder(Component.literal("Включить вебхуки"), button -> {
            if (this.minecraft == null || bankUiService.getSelectedCard() == null) {
                notifications.show(Component.literal("Сначала выбери или добавь карту"));
                return;
            }
            this.minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
                if (this.minecraft == null) {
                    return;
                }
                this.minecraft.gui.setScreen(this);
                if (!confirmed) {
                    return;
                }
                bankUiService.registerSelectedWebhookAsync().thenAccept(message -> this.minecraft.execute(() -> {
                    notifications.showMessage(message);
                    this.rebuildWidgets();
                }));
            }, Component.literal("Включить обработку вебхуков?"),
                    Component.literal("Если другой вебхук уже подключен к карте — он может затереться."),
                    Component.literal("Включить"), Component.literal("Отмена")));
        }).bounds(rightX, startY + 96, columnWidth, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Назад"), button -> this.onClose())
                .bounds(rightX, startY + 144, columnWidth, 20)
                .build());

        if (cards.isEmpty()) {
            notifications.show(Component.literal("Нет карт. Добавь карту через кнопку 'Добавить новую'."));
        }

        updateCardButtonStates();
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
        int startY = this.height / 2 - 82;
        int leftX = centerX - 210;
        int rightX = centerX + 14;

        context.centeredText(this.font, this.title, centerX, 24, 0xFFFFFF);
        context.text(this.font, Component.literal("Список карт"), leftX, startY - 18, 0xBFBFBF);
        context.text(this.font, Component.literal("Действия"), rightX, startY - 18, 0xBFBFBF);
    }

    private void updateCardButtonStates() {
        int selectedIndex = bankUiService.getSelectedCardIndex();
        List<CardViewModel> cards = bankUiService.getCards();

        for (int i = 0; i < cardButtons.size(); i++) {
            Button button = cardButtons.get(i);
            if (i >= cards.size()) {
                button.visible = false;
                button.active = false;
                continue;
            }

            boolean selected = i == selectedIndex;
            String prefix = selected ? ">> " : "";
            String suffix = selected ? " <<" : "";
            button.setMessage(Component.literal(prefix + cards.get(i).title() + suffix));
            button.active = !selected;
        }

        if (historyButton != null) {
            historyButton.active = !cards.isEmpty();
        }
        if (webhookButton != null) {
            CardViewModel selected = bankUiService.getSelectedCard();
            boolean enabled = selected != null && selected.webhookEnabled();
            webhookButton.active = selected != null && !enabled;
            webhookButton.setMessage(Component.literal(enabled ? "Вебхуки включены" : "Включить вебхуки"));
        }
    }
}
