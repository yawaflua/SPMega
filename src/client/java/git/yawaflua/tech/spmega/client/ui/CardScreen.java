package git.yawaflua.tech.spmega.client.ui;

import git.yawaflua.tech.spmega.client.ui.service.BankUiService;
import git.yawaflua.tech.spmega.client.ui.service.CardViewModel;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class CardScreen extends Screen {
    private final Screen parent;
    private final BankUiService bankUiService = BankUiService.instance();
    private final UiNotifications notifications = UiNotifications.instance();
    private final List<ButtonWidget> cardButtons = new ArrayList<>();

    public CardScreen(Screen parent) {
        super(Text.literal("Управление картами"));
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
            ButtonWidget cardButton = this.addDrawableChild(ButtonWidget.builder(Text.literal(card.title()), button -> {
                bankUiService.setSelectedCardIndex(index);
                notifications.show(Text.literal("Выбрана карта " + card.title()));
                updateCardButtonStates();
            }).dimensions(leftX, startY + i * 24, columnWidth, 20).build());
            cardButtons.add(cardButton);
        }

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Удалить"), button -> {
            bankUiService.removeSelectedCard();
            notifications.showMessage(bankUiService.getLastMessage());
            this.clearAndInit();
        }).dimensions(rightX, startY, columnWidth, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Изменить"), button -> {
            String playerUuid = this.client != null && this.client.player != null
                    ? this.client.player.getUuidAsString()
                    : "";
            bankUiService.refreshSelectedCard(playerUuid);
            String message = bankUiService.getLastMessage().isBlank() ? "Карта обновлена" : bankUiService.getLastMessage();
            notifications.showMessage(message);
            this.clearAndInit();
        }).dimensions(rightX, startY + 24, columnWidth, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Добавить новую"), button -> {
            if (this.client == null) {
                return;
            }
            this.client.setScreen(new AddCardScreen(this, message -> {
                notifications.showMessage(message);
                this.clearAndInit();
            }));
        }).dimensions(rightX, startY + 48, columnWidth, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Назад"), button -> this.close())
                .dimensions(rightX, startY + 120, columnWidth, 20)
                .build());

        if (cards.isEmpty()) {
            notifications.show(Text.literal("Нет карт. Добавь карту через кнопку 'Добавить новую'."));
        }

        updateCardButtonStates();
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
        int startY = this.height / 2 - 82;
        int leftX = centerX - 210;
        int rightX = centerX + 14;

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, 24, 0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Список карт"), leftX, startY - 18, 0xBFBFBF);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Действия"), rightX, startY - 18, 0xBFBFBF);
        notifications.render(context, this.textRenderer, this.width, this.height);
    }

    private void updateCardButtonStates() {
        int selectedIndex = bankUiService.getSelectedCardIndex();
        List<CardViewModel> cards = bankUiService.getCards();

        for (int i = 0; i < cardButtons.size(); i++) {
            ButtonWidget button = cardButtons.get(i);
            if (i >= cards.size()) {
                button.visible = false;
                button.active = false;
                continue;
            }

            boolean selected = i == selectedIndex;
            String prefix = selected ? ">> " : "";
            String suffix = selected ? " <<" : "";
            button.setMessage(Text.literal(prefix + cards.get(i).title() + suffix));
            button.active = !selected;
        }
    }
}
