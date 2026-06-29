package git.yawaflua.tech.spmega.client.ui;

import git.yawaflua.tech.spmega.client.ui.service.BankUiService;
import git.yawaflua.tech.spmega.client.ui.service.CardViewModel;
import git.yawaflua.tech.spmega.client.ui.service.PaymentDraft;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PaymentScreen extends Screen {
    private static final int MAX_RECIPIENT_OPTION_BUTTONS = 10;
    private static final long RECIPIENT_LOOKUP_DEBOUNCE_MS = 500L;
    private static final Pattern CARD_ID_PATTERN = Pattern.compile("\\d{5}");
    private final Screen parent;
    private final BankUiService bankUiService = BankUiService.instance();
    private final List<ButtonWidget> recipientCardOptionButtons = new ArrayList<>();
    private final List<String> recipientCardOptions = new ArrayList<>();
    private final String initialRecipient;
    private final UiNotifications notifications = UiNotifications.instance();
    private TextFieldWidget amountField;
    private TextFieldWidget recipientField;
    private TextFieldWidget commentField;
    private ButtonWidget senderLeftButton;
    private ButtonWidget senderCardLabelButton;
    private ButtonWidget senderRightButton;
    private ButtonWidget recipientCardDropdownButton;
    private boolean recipientDropdownExpanded;
    private String selectedRecipientCard = "";
    private String lastRecipientLookup = "";
    private String pendingRecipientLookup = "";
    private long pendingRecipientLookupAt;
    private Text senderCardText = Text.literal("Карта отправителя: не выбрана");
    private ButtonWidget transferButton;
    private ButtonWidget backButton;
    private boolean transferInProgress;

    public PaymentScreen(Screen parent) {
        this(parent, "");
    }

    public PaymentScreen(Screen parent, String initialRecipient) {
        super(Text.literal("Оплата"));
        this.parent = parent;
        this.initialRecipient = initialRecipient == null ? "" : initialRecipient.trim();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int startY = this.height / 2 - 82;
        int leftX = centerX - 210;
        int rightX = centerX + 14;
        int leftWidth = 196;
        int rightWidth = 196;
        TextRenderer tr = this.textRenderer;

        amountField = new TextFieldWidget(tr, leftX, startY, leftWidth, 20, Text.literal("Сумма"));
        amountField.setMaxLength(16);
        amountField.setPlaceholder(Text.literal("Сумма перевода"));
        this.addDrawableChild(amountField);

        recipientField = new TextFieldWidget(tr, leftX, startY + 28, leftWidth, 20, Text.literal("Получатель"));
        recipientField.setMaxLength(32);
        recipientField.setPlaceholder(Text.literal("Ник или 5 цифр карты"));
        recipientField.setChangedListener(value -> {
            String input = value.trim();
            if (isNickname(input)) {
                scheduleRecipientLookup(input);
            } else {
                lastRecipientLookup = "";
                pendingRecipientLookup = "";
                recipientCardOptions.clear();
                selectedRecipientCard = "";
            }
            updateRecipientDropdownVisibility();
            updateSenderCardText();
        });
        this.addDrawableChild(recipientField);

        commentField = new TextFieldWidget(tr, leftX, startY + 56, leftWidth, 20, Text.literal("Комментарий"));
        commentField.setMaxLength(64);
        commentField.setPlaceholder(Text.literal("Комментарий (необязательно)"));
        this.addDrawableChild(commentField);

        recipientCardDropdownButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Карта игрока: -"), button -> {
            recipientDropdownExpanded = !recipientDropdownExpanded;
            updateRecipientDropdownVisibility();
        }).dimensions(leftX, startY + 84, leftWidth, 20).build());

        recipientCardOptionButtons.clear();
        for (int i = 0; i < MAX_RECIPIENT_OPTION_BUTTONS; i++) {
            final int index = i;
            ButtonWidget optionButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("-"), button -> {
                if (index < recipientCardOptions.size()) {
                    selectedRecipientCard = recipientCardOptions.get(index);
                    recipientDropdownExpanded = false;
                    updateRecipientDropdownVisibility();
                    updateSenderCardText();
                }
            }).dimensions(leftX, startY + 108 + i * 22, leftWidth, 20).build());
            recipientCardOptionButtons.add(optionButton);
        }

        if (!initialRecipient.isEmpty()) {
            recipientField.setText(initialRecipient);
        }

        int arrowWidth = 36;
        int middleWidth = rightWidth - arrowWidth * 2 - 8;
        senderLeftButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("<"), button -> {
            bankUiService.cycleSelectedCard(-1);
            updateSenderCardSelector();
            updateSenderCardText();
        }).dimensions(rightX, startY, arrowWidth, 20).build());

        senderCardLabelButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("00000: 0 АР"), button -> {
        }).dimensions(rightX + arrowWidth + 4, startY, middleWidth, 20).build());
        senderCardLabelButton.active = false;

        senderRightButton = this.addDrawableChild(ButtonWidget.builder(Text.literal(">"), button -> {
            bankUiService.cycleSelectedCard(1);
            updateSenderCardSelector();
            updateSenderCardText();
        }).dimensions(rightX + arrowWidth + 4 + middleWidth + 4, startY, arrowWidth, 20).build());

        transferButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Перевести"), button -> submit())
                .dimensions(rightX, startY + 28, rightWidth, 20)
                .build());

        backButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Назад"), button -> this.close())
                .dimensions(rightX, startY + 56, rightWidth, 20)
                .build());

        updateSenderCardSelector();
        updateRecipientDropdownVisibility();
        updateSenderCardText();
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (pendingRecipientLookup.isEmpty()) {
            return;
        }

        long elapsed = System.currentTimeMillis() - pendingRecipientLookupAt;
        if (elapsed < RECIPIENT_LOOKUP_DEBOUNCE_MS) {
            return;
        }

        String lookupUsername = pendingRecipientLookup;
        pendingRecipientLookup = "";
        requestRecipientCards(lookupUsername);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        int startY = this.height / 2 - 82;
        int leftX = centerX - 210;
        int rightX = centerX + 14;

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, 20, 0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Левая колонка: ввод"), leftX, startY - 18, 0xBFBFBF);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Правая колонка: действия"), rightX, startY - 18, 0xBFBFBF);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Сумма:"), leftX, startY - 10, 0xCCCCCC);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Получатель:"), leftX, startY + 18, 0xCCCCCC);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Комментарий:"), leftX, startY + 46, 0xCCCCCC);

        if (recipientCardDropdownButton.visible) {
            context.drawTextWithShadow(this.textRenderer, Text.literal("Карта игрока"), leftX, startY + 74, 0xD7B2FF);
        }

        context.drawCenteredTextWithShadow(this.textRenderer, senderCardText, centerX, this.height - 20, 0xA9E5A9);
    }

    private void submit() {
        if (transferInProgress) {
            notifications.show(Text.literal("Перевод уже выполняется"));
            return;
        }

        CardViewModel selectedCard = bankUiService.getSelectedCard();
        if (selectedCard == null) {
            notifications.show(Text.literal("Нет выбранной карты отправителя"));
            return;
        }

        long amount;
        try {
            amount = Long.parseLong(amountField.getText().trim());
            if (amount <= 0) {
                notifications.show(Text.literal("Сумма должна быть больше 0"));
                return;
            }
        } catch (Exception exception) {
            System.out.println(exception.getMessage());
            notifications.show(Text.literal("Некорректная сумма"));
            return;
        }

        String recipientInput = recipientField.getText().trim();
        if (!isValidRecipient(recipientInput)) {
            notifications.show(Text.literal("Укажи ник или 5 цифр карты"));
            return;
        }

        String receiver = recipientInput;
        if (isNickname(recipientInput)) {
            if (selectedRecipientCard.isEmpty()) {
                notifications.show(Text.literal("Выбери карту получателя из списка"));
                return;
            }
            receiver = selectedRecipientCard;
        }

        PaymentDraft draft = new PaymentDraft(
                selectedCard.id(),
                receiver,
                amount,
                commentField.getText().trim()
        );

        setTransferInProgress(true);
        notifications.show(Text.literal("Отправка перевода..."));

        bankUiService.submitPaymentAsync(draft)
                .thenAccept(accepted -> {
                    if (this.client == null) {
                        return;
                    }
                    this.client.execute(() -> {
                        setTransferInProgress(false);

                        String serviceMessage = bankUiService.getLastMessage();
                        if (!serviceMessage.isBlank()) {
                            notifications.showMessage(serviceMessage);
                        } else {
                            notifications.show(accepted
                                    ? Text.literal("Перевод выполнен")
                                    : Text.literal("Перевод отклонен"));
                        }

                        updateSenderCardSelector();
                        updateSenderCardText();
                    });
                })
                .exceptionally(exception -> {
                    if (this.client != null) {
                        this.client.execute(() -> {
                            setTransferInProgress(false);
                            notifications.show(Text.literal("Ошибка перевода: " + exception.getMessage()));
                        });
                    }
                    return null;
                });
    }

    private void setTransferInProgress(boolean inProgress) {
        transferInProgress = inProgress;
        if (transferButton != null) {
            transferButton.active = !inProgress;
        }
    }

    private boolean isValidRecipient(String recipient) {
        if (recipient.matches("\\d{5}")) {
            return true;
        }
        return recipient.matches("[A-Za-z0-9_]{3,16}");
    }

    private void updateSenderCardText() {
        CardViewModel selectedCard = bankUiService.getSelectedCard();
        if (selectedCard == null) {
            senderCardText = Text.literal("Карта отправителя: не выбрана");
            return;
        }

        String recipient = recipientField == null ? "" : recipientField.getText().trim();
        String suffix;
        if (isNickname(recipient)) {
            String recipientCard = selectedRecipientCard.isEmpty() ? "карта не выбрана" : selectedRecipientCard;
            suffix = " (ник: выбранная карта -> " + recipientCard + ")";
        } else {
            suffix = " (режим: перевод по номеру карты)";
        }

        senderCardText = Text.literal(
                "Карта отправителя: " + selectedCard.title() + " | Баланс: " + selectedCard.balance() + suffix
        );
    }

    private void updateSenderCardSelector() {
        CardViewModel selectedCard = bankUiService.getSelectedCard();
        if (selectedCard == null) {
            senderCardLabelButton.setMessage(Text.literal("TEST 00000: 0 АР"));
            senderLeftButton.active = false;
            senderRightButton.active = false;
            return;
        }

        senderLeftButton.active = true;
        senderRightButton.active = true;
        String balance = Long.toString(selectedCard.balance());
        senderCardLabelButton.setMessage(Text.literal(selectedCard.title() + ": " + balance + " АР"));
        senderCardLabelButton.active = false;
    }

    private void updateRecipientDropdownVisibility() {
        if (recipientCardDropdownButton == null) {
            return;
        }

        boolean nicknameMode = isNickname(recipientField == null ? "" : recipientField.getText().trim());

        boolean hasRecipientCards = !recipientCardOptions.isEmpty();
        recipientCardDropdownButton.visible = nicknameMode;
        recipientCardDropdownButton.active = nicknameMode && hasRecipientCards;

        if (!nicknameMode) {
            recipientDropdownExpanded = false;
            selectedRecipientCard = "";
        } else if (hasRecipientCards && selectedRecipientCard.isEmpty()) {
            selectedRecipientCard = recipientCardOptions.get(0);
        }

        String dropdownText;
        if (!nicknameMode) {
            dropdownText = "Карта игрока: -";
        } else if (!hasRecipientCards) {
            dropdownText = "Карта игрока: нет карт";
        } else {
            dropdownText = "Карта игрока: " + selectedRecipientCard;
        }
        recipientCardDropdownButton.setMessage(Text.literal(dropdownText));

        for (int i = 0; i < recipientCardOptionButtons.size(); i++) {
            ButtonWidget optionButton = recipientCardOptionButtons.get(i);
            boolean showOption = nicknameMode && recipientDropdownExpanded && i < recipientCardOptions.size();
            optionButton.visible = showOption;
            optionButton.active = showOption;
            if (i < recipientCardOptions.size()) {
                optionButton.setMessage(Text.literal(recipientCardOptions.get(i)));
            }
        }
    }

    private boolean isNickname(String value) {
        return value.matches("[A-Za-z0-9_]{3,16}") && !value.matches("\\d+");
    }

    private String extractCardId(String source) {
        Matcher matcher = CARD_ID_PATTERN.matcher(source);
        if (matcher.find()) {
            return matcher.group();
        }
        return "00000";
    }

    private void requestRecipientCards(String username) {
        if (username.equals(lastRecipientLookup)) {
            return;
        }
        lastRecipientLookup = username;

        bankUiService.loadRecipientCardsAsync(username)
                .thenAccept(cards -> {
                    if (this.client == null) {
                        return;
                    }
                    this.client.execute(() -> {
                        if (recipientField == null || !username.equals(recipientField.getText().trim())) {
                            return;
                        }

                        recipientCardOptions.clear();
                        recipientCardOptions.addAll(cards);
                        selectedRecipientCard = recipientCardOptions.isEmpty() ? "" : recipientCardOptions.get(0);
                        recipientDropdownExpanded = false;

                        String serviceMessage = bankUiService.getLastMessage();
                        if (!serviceMessage.isBlank()) {
                            notifications.showMessage(serviceMessage);
                        }

                        updateRecipientDropdownVisibility();
                        updateSenderCardText();
                    });
                });
    }

    private void scheduleRecipientLookup(String username) {
        pendingRecipientLookup = username;
        pendingRecipientLookupAt = System.currentTimeMillis();
    }

}
