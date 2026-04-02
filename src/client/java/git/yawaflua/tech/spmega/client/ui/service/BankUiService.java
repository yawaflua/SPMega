package git.yawaflua.tech.spmega.client.ui.service;

import git.yawaflua.tech.spmega.api.SPWorldsApiClient;
import net.fabricmc.loader.api.FabricLoader;

import java.util.*;

public final class BankUiService {
    private static final BankUiService INSTANCE = new BankUiService();

    private final List<CardViewModel> cards = new ArrayList<>();
    private final BankDatabase database;
    private final SPWorldsApiClient apiClient;
    private final String apiDomain = "https://spworlds.ru";

    private int selectedCardIndex;
    private String lastMessage = "";

    private BankUiService() {
        this.database = new BankDatabase(FabricLoader.getInstance().getConfigDir().resolve("spmega.db"));
        this.database.initialize();
        this.apiClient = new SPWorldsApiClient(apiDomain);

        reloadCardsFromDb();
    }

    public static BankUiService instance() {
        return INSTANCE;
    }

    private static SPWorldsApiClient.CardAuth toApiAuth(CardCredentials credentials) {
        return new SPWorldsApiClient.CardAuth(credentials.cardId(), credentials.cardToken());
    }

    private static String trimMessage(String message) {
        if (message == null) {
            return "unknown";
        }
        return message.length() > 120 ? message.substring(0, 120) : message;
    }

    private static String normalizeUuid(String rawUuid) {
        return rawUuid == null ? "" : rawUuid.toLowerCase(Locale.ROOT).replace("-", "");
    }

    private static String extractLastDigits(String value) {
        String normalized = value == null ? "" : value.replace("-", "");
        if (normalized.length() < 5) {
            return "00000";
        }
        return normalized.substring(normalized.length() - 5);
    }

    public synchronized List<CardViewModel> getCards() {
        return Collections.unmodifiableList(cards);
    }

    public synchronized CardViewModel getSelectedCard() {
        if (cards.isEmpty()) {
            return null;
        }
        selectedCardIndex = Math.floorMod(selectedCardIndex, cards.size());
        return cards.get(selectedCardIndex);
    }

    public synchronized int getSelectedCardIndex() {
        if (cards.isEmpty()) {
            return 0;
        }
        selectedCardIndex = Math.floorMod(selectedCardIndex, cards.size());
        return selectedCardIndex;
    }

    public synchronized void setSelectedCardIndex(int index) {
        if (cards.isEmpty()) {
            selectedCardIndex = 0;
            return;
        }
        selectedCardIndex = Math.floorMod(index, cards.size());
    }

    public synchronized CardViewModel cycleSelectedCard(int direction) {
        if (cards.isEmpty()) {
            return null;
        }
        selectedCardIndex = Math.floorMod(selectedCardIndex + direction, cards.size());
        return cards.get(selectedCardIndex);
    }

    public synchronized String getLastMessage() {
        return lastMessage;
    }

    public synchronized void refreshOnServerJoin(String playerUuid) {
        List<StoredCard> storedCards = database.loadCards();
        for (StoredCard card : storedCards) {
            refreshCard(card.cardId(), card.cardToken(), playerUuid, false, false);
        }
        reloadCardsFromDb();
    }

    public synchronized List<String> loadRecipientCards(String username) {
        CardCredentials credentials = getSelectedCredentials();
        if (credentials == null || username == null || username.isBlank()) {
            return List.of();
        }

        try {
            List<SPWorldsApiClient.PlayerCard> apiCards = apiClient.getPlayerCards(username, toApiAuth(credentials));
            List<String> numbers = new ArrayList<>();
            for (SPWorldsApiClient.PlayerCard apiCard : apiCards) {
                if (apiCard.number() != null && !apiCard.number().isBlank()) {
                    numbers.add(apiCard.number());
                }
            }
            lastMessage = "";
            return numbers;
        } catch (Exception exception) {
            lastMessage = "Не удалось получить карты игрока: " + exception.getMessage();
            return List.of();
        }
    }

    public synchronized String addCard(String cardIdRaw, String cardTokenRaw, String playerUuid) {
        String cardId = cardIdRaw == null ? "" : cardIdRaw.trim();
        String cardToken = cardTokenRaw == null ? "" : cardTokenRaw.trim();

        if (cardId.isEmpty() || cardToken.isEmpty()) {
            lastMessage = "Укажи cardId и cardToken";
            return lastMessage;
        }

        try {
            UUID.fromString(cardId);
        } catch (IllegalArgumentException exception) {
            lastMessage = "cardId должен быть UUID";
            return lastMessage;
        }

        database.upsertCardCredentials(cardId, cardToken);
        boolean refreshed = refreshCard(cardId, cardToken, playerUuid, true, true);
        if (!refreshed) {
            database.deleteCard(cardId);
        }
        reloadCardsFromDb();

        if (refreshed && lastMessage.isBlank()) {
            lastMessage = "Карта добавлена";
        }
        return lastMessage;
    }

    public synchronized void removeSelectedCard() {
        CardViewModel selected = getSelectedCard();
        if (selected == null) {
            lastMessage = "Нет карты для удаления";
            return;
        }

        database.deleteCard(selected.id());
        reloadCardsFromDb();
        if (selectedCardIndex >= cards.size()) {
            selectedCardIndex = Math.max(0, cards.size() - 1);
        }
        lastMessage = "Карта удалена";
    }

    public synchronized void refreshSelectedCard(String playerUuid) {
        CardViewModel selected = getSelectedCard();
        if (selected == null) {
            lastMessage = "Нет карты для обновления";
            return;
        }

        CardCredentials credentials = database.getCredentials(selected.id());
        if (credentials == null) {
            lastMessage = "Не найдены креды карты";
            return;
        }

        refreshCard(credentials.cardId(), credentials.cardToken(), playerUuid, false, false);
        reloadCardsFromDb();
    }

    public synchronized boolean submitPayment(PaymentDraft draft) {
        CardCredentials credentials = database.getCredentials(draft.senderCardId());
        if (credentials == null) {
            lastMessage = "Не найдены креды карты отправителя";
            return false;
        }

        try {
            SPWorldsApiClient.TransactionResult result = apiClient.createTransaction(
                    toApiAuth(credentials),
                    draft.recipient(),
                    draft.amount(),
                    draft.comment()
            );

            database.updateCardBalance(credentials.cardId(), result.balance());
            database.insertTransferHistory(
                    credentials.cardId(),
                    draft.recipient(),
                    draft.amount(),
                    draft.comment(),
                    result.balance(),
                    "SUCCESS"
            );

            reloadCardsFromDb();
            lastMessage = "Перевод выполнен";
            return true;
        } catch (Exception exception) {
            database.insertTransferHistory(
                    credentials.cardId(),
                    draft.recipient(),
                    draft.amount(),
                    draft.comment(),
                    null,
                    "FAILED: " + trimMessage(exception.getMessage())
            );
            lastMessage = "Ошибка перевода: " + exception.getMessage();
            return false;
        }
    }

    private boolean refreshCard(
            String cardId,
            String cardToken,
            String playerUuid,
            boolean reportOwnerWarning,
            boolean requireCardInAccount
    ) {
        try {
            SPWorldsApiClient.CardAuth auth = new SPWorldsApiClient.CardAuth(cardId, cardToken);
            SPWorldsApiClient.AccountMe me = apiClient.getAccountMe(auth);
            SPWorldsApiClient.CardInfo cardInfo = apiClient.getCardInfo(auth);

            SPWorldsApiClient.AccountCard currentCard = me.cards().stream()
                    .filter(card -> Objects.equals(card.id(), cardId))
                    .findFirst()
                    .orElse(null);

            if (requireCardInAccount && currentCard == null) {
                lastMessage = "Карта не найдена в аккаунте SPWorlds";
                return false;
            }

            String cardName = currentCard == null || currentCard.name().isBlank() ? "Карта" : currentCard.name();
            String cardNumber = currentCard == null || currentCard.number().isBlank() ? extractLastDigits(cardId) : currentCard.number();
            String ownerUuid = normalizeUuid(me.minecraftUuid());

            database.updateCardMeta(cardId, cardName, cardNumber, cardInfo.balance(), ownerUuid);

            if (reportOwnerWarning && playerUuid != null && !playerUuid.isBlank()) {
                String normalizedPlayerUuid = normalizeUuid(playerUuid);
                if (!normalizedPlayerUuid.equals(ownerUuid)) {
                    lastMessage = "Вы не владелец карты. Часть функций может быть ограничена.";
                    return true;
                }
            }

            lastMessage = "";
            return true;
        } catch (Exception exception) {
            lastMessage = "Не удалось обновить карту: " + exception.getMessage();
            return false;
        }
    }

    private void reloadCardsFromDb() {
        List<StoredCard> storedCards = database.loadCards();
        cards.clear();

        for (StoredCard stored : storedCards) {
            String cardNumber = stored.cardNumber() == null || stored.cardNumber().isBlank()
                    ? extractLastDigits(stored.cardId())
                    : stored.cardNumber();
            String cardName = stored.cardName() == null || stored.cardName().isBlank() ? "Карта" : stored.cardName();
            String title = cardNumber + ": " + cardName;
            cards.add(new CardViewModel(stored.cardId(), title, stored.balance()));
        }

        if (cards.isEmpty()) {
            selectedCardIndex = 0;
        } else {
            selectedCardIndex = Math.floorMod(selectedCardIndex, cards.size());
        }
    }

    private CardCredentials getSelectedCredentials() {
        CardViewModel selected = getSelectedCard();
        if (selected == null) {
            return null;
        }
        return database.getCredentials(selected.id());
    }
}
