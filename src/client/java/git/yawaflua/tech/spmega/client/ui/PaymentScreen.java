package git.yawaflua.tech.spmega.client.ui;

import git.yawaflua.tech.spmega.client.ui.service.BankUiService;
import git.yawaflua.tech.spmega.client.ui.service.CardViewModel;
import git.yawaflua.tech.spmega.client.ui.service.PaymentDraft;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class PaymentScreen extends Screen {
    private static final int MAX_RECIPIENT_OPTION_BUTTONS = 10;
    private static final long RECIPIENT_LOOKUP_DEBOUNCE_MS = 500L;
    private static final Pattern CARD_ID_PATTERN = Pattern.compile("\\d{5}");
    private final Screen parent;
    private final BankUiService bankUiService = BankUiService.instance();
    private final List<Button> recipientCardOptionButtons = new ArrayList<>();
    private final List<String> recipientCardOptions = new ArrayList<>();
    private final String initialRecipient;
    private final UiNotifications notifications = UiNotifications.instance();
    private EditBox amountField;
    private EditBox recipientField;
    private EditBox commentField;
    private Button senderLeftButton;
    private Button senderCardLabelButton;
    private Button senderRightButton;
    private Button recipientCardDropdownButton;
    private boolean recipientDropdownExpanded;
    private String selectedRecipientCard = "";
    private String lastRecipientLookup = "";
    private String pendingRecipientLookup = "";
    private long pendingRecipientLookupAt;
    private Component senderCardText = Component.literal("Карта отправителя: не выбрана");
    private Button transferButton;
    private Button backButton;
    private boolean transferInProgress;

    public PaymentScreen(Screen parent) {
        this(parent, "");
    }

    public PaymentScreen(Screen parent, String initialRecipient) {
        super(Component.literal("Оплата"));
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
        Font tr = this.font;

        amountField = new EditBox(tr, leftX, startY, leftWidth, 20, Component.literal("Сумма"));
        amountField.setMaxLength(16);
        amountField.setHint(Component.literal("Сумма перевода"));
        this.addRenderableWidget(amountField);

        recipientField = new EditBox(tr, leftX, startY + 28, leftWidth, 20, Component.literal("Получатель"));
        recipientField.setMaxLength(32);
        recipientField.setHint(Component.literal("Ник или 5 цифр карты"));
        recipientField.setResponder(value -> {
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
        this.addRenderableWidget(recipientField);

        commentField = new EditBox(tr, leftX, startY + 56, leftWidth, 20, Component.literal("Комментарий"));
        commentField.setMaxLength(64);
        commentField.setHint(Component.literal("Комментарий (необязательно)"));
        this.addRenderableWidget(commentField);

        recipientCardDropdownButton = this.addRenderableWidget(Button.builder(Component.literal("Карта игрока: -"), button -> {
            recipientDropdownExpanded = !recipientDropdownExpanded;
            updateRecipientDropdownVisibility();
        }).bounds(leftX, startY + 84, leftWidth, 20).build());

        recipientCardOptionButtons.clear();
        for (int i = 0; i < MAX_RECIPIENT_OPTION_BUTTONS; i++) {
            final int index = i;
            Button optionButton = this.addRenderableWidget(Button.builder(Component.literal("-"), button -> {
                if (index < recipientCardOptions.size()) {
                    selectedRecipientCard = recipientCardOptions.get(index);
                    recipientDropdownExpanded = false;
                    updateRecipientDropdownVisibility();
                    updateSenderCardText();
                }
            }).bounds(leftX, startY + 108 + i * 22, leftWidth, 20).build());
            recipientCardOptionButtons.add(optionButton);
        }

        if (!initialRecipient.isEmpty()) {
            recipientField.setValue(initialRecipient);
        }

        int arrowWidth = 36;
        int middleWidth = rightWidth - arrowWidth * 2 - 8;
        senderLeftButton = this.addRenderableWidget(Button.builder(Component.literal("<"), button -> {
            bankUiService.cycleSelectedCard(-1);
            updateSenderCardSelector();
            updateSenderCardText();
        }).bounds(rightX, startY, arrowWidth, 20).build());

        senderCardLabelButton = this.addRenderableWidget(Button.builder(Component.literal("00000: 0 АР"), button -> {
        }).bounds(rightX + arrowWidth + 4, startY, middleWidth, 20).build());
        senderCardLabelButton.active = false;

        senderRightButton = this.addRenderableWidget(Button.builder(Component.literal(">"), button -> {
            bankUiService.cycleSelectedCard(1);
            updateSenderCardSelector();
            updateSenderCardText();
        }).bounds(rightX + arrowWidth + 4 + middleWidth + 4, startY, arrowWidth, 20).build());

        transferButton = this.addRenderableWidget(Button.builder(Component.literal("Перевести"), button -> submit())
                .bounds(rightX, startY + 28, rightWidth, 20)
                .build());

        backButton = this.addRenderableWidget(Button.builder(Component.literal("Назад"), button -> this.onClose())
                .bounds(rightX, startY + 56, rightWidth, 20)
                .build());

        updateSenderCardSelector();
        updateRecipientDropdownVisibility();
        updateSenderCardText();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(parent);
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

        context.centeredText(this.font, this.title, centerX, 20, 0xFFFFFF);
        context.text(this.font, Component.literal("Левая колонка: ввод"), leftX, startY - 18, 0xBFBFBF);
        context.text(this.font, Component.literal("Правая колонка: действия"), rightX, startY - 18, 0xBFBFBF);
        context.text(this.font, Component.literal("Сумма:"), leftX, startY - 10, 0xCCCCCC);
        context.text(this.font, Component.literal("Получатель:"), leftX, startY + 18, 0xCCCCCC);
        context.text(this.font, Component.literal("Комментарий:"), leftX, startY + 46, 0xCCCCCC);

        if (recipientCardDropdownButton.visible) {
            context.text(this.font, Component.literal("Карта игрока"), leftX, startY + 74, 0xD7B2FF);
        }

        context.centeredText(this.font, senderCardText, centerX, this.height - 20, 0xA9E5A9);
    }

    private void submit() {
        if (transferInProgress) {
            notifications.show(Component.literal("Перевод уже выполняется"));
            return;
        }

        CardViewModel selectedCard = bankUiService.getSelectedCard();
        if (selectedCard == null) {
            notifications.show(Component.literal("Нет выбранной карты отправителя"));
            return;
        }

        long amount;
        try {
            amount = Long.parseLong(amountField.getValue().trim());
            if (amount <= 0) {
                notifications.show(Component.literal("Сумма должна быть больше 0"));
                return;
            }
        } catch (Exception exception) {
            System.out.println(exception.getMessage());
            notifications.show(Component.literal("Некорректная сумма"));
            return;
        }

        String recipientInput = recipientField.getValue().trim();
        if (!isValidRecipient(recipientInput)) {
            notifications.show(Component.literal("Укажи ник или 5 цифр карты"));
            return;
        }

        String receiver = recipientInput;
        if (isNickname(recipientInput)) {
            if (selectedRecipientCard.isEmpty()) {
                notifications.show(Component.literal("Выбери карту получателя из списка"));
                return;
            }
            receiver = selectedRecipientCard;
        }

        PaymentDraft draft = new PaymentDraft(
                selectedCard.id(),
                receiver,
                amount,
                commentField.getValue().trim()
        );

        setTransferInProgress(true);
        notifications.show(Component.literal("Отправка перевода..."));

        bankUiService.submitPaymentAsync(draft)
                .thenAccept(accepted -> {
                    if (this.minecraft == null) {
                        return;
                    }
                    this.minecraft.execute(() -> {
                        setTransferInProgress(false);

                        String serviceMessage = bankUiService.getLastMessage();
                        if (!serviceMessage.isBlank()) {
                            notifications.showMessage(serviceMessage);
                        } else {
                            notifications.show(accepted
                                    ? Component.literal("Перевод выполнен")
                                    : Component.literal("Перевод отклонен"));
                        }

                        updateSenderCardSelector();
                        updateSenderCardText();
                    });
                })
                .exceptionally(exception -> {
                    if (this.minecraft != null) {
                        this.minecraft.execute(() -> {
                            setTransferInProgress(false);
                            notifications.show(Component.literal("Ошибка перевода: " + exception.getMessage()));
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
            senderCardText = Component.literal("Карта отправителя: не выбрана");
            return;
        }

        String recipient = recipientField == null ? "" : recipientField.getValue().trim();
        String suffix;
        if (isNickname(recipient)) {
            String recipientCard = selectedRecipientCard.isEmpty() ? "карта не выбрана" : selectedRecipientCard;
            suffix = " (ник: выбранная карта -> " + recipientCard + ")";
        } else {
            suffix = " (режим: перевод по номеру карты)";
        }

        senderCardText = Component.literal(
                "Карта отправителя: " + selectedCard.title() + " | Баланс: " + selectedCard.balance() + suffix
        );
    }

    private void updateSenderCardSelector() {
        CardViewModel selectedCard = bankUiService.getSelectedCard();
        if (selectedCard == null) {
            senderCardLabelButton.setMessage(Component.literal("TEST 00000: 0 АР"));
            senderLeftButton.active = false;
            senderRightButton.active = false;
            return;
        }

        senderLeftButton.active = true;
        senderRightButton.active = true;
        String balance = Long.toString(selectedCard.balance());
        senderCardLabelButton.setMessage(Component.literal(selectedCard.title() + ": " + balance + " АР"));
        senderCardLabelButton.active = false;
    }

    private void updateRecipientDropdownVisibility() {
        if (recipientCardDropdownButton == null) {
            return;
        }

        boolean nicknameMode = isNickname(recipientField == null ? "" : recipientField.getValue().trim());

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
        recipientCardDropdownButton.setMessage(Component.literal(dropdownText));

        for (int i = 0; i < recipientCardOptionButtons.size(); i++) {
            Button optionButton = recipientCardOptionButtons.get(i);
            boolean showOption = nicknameMode && recipientDropdownExpanded && i < recipientCardOptions.size();
            optionButton.visible = showOption;
            optionButton.active = showOption;
            if (i < recipientCardOptions.size()) {
                optionButton.setMessage(Component.literal(recipientCardOptions.get(i)));
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
                    if (this.minecraft == null) {
                        return;
                    }
                    this.minecraft.execute(() -> {
                        if (recipientField == null || !username.equals(recipientField.getValue().trim())) {
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
