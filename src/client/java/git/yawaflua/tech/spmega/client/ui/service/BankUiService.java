package git.yawaflua.tech.spmega.client.ui.service;

import git.yawaflua.tech.spmega.ModConfig;
import git.yawaflua.tech.spmega.SPMega;
import git.yawaflua.tech.spmega.api.SPWorldsApiClient;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.MinecraftClient;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class BankUiService {
    private static final BankUiService INSTANCE = new BankUiService();

    private final List<CardViewModel> cards = new ArrayList<>();
    private final BankDatabase database;
    private final SPWorldsApiClient apiClient;
    private final String apiDomain = "https://spworlds.ru";

    private int selectedCardIndex;
    private volatile String lastMessage = "";

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

    private void runOnMainThread(Runnable runnable) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.isOnThread()) {
            runnable.run();
        } else {
            client.execute(runnable);
        }
    }

    public List<CardViewModel> getCards() {
        return Collections.unmodifiableList(cards);
    }

    public CardViewModel getSelectedCard() {
        if (cards.isEmpty()) {
            return null;
        }
        selectedCardIndex = Math.floorMod(selectedCardIndex, cards.size());
        return cards.get(selectedCardIndex);
    }

    public int getSelectedCardIndex() {
        if (cards.isEmpty()) {
            return 0;
        }
        selectedCardIndex = Math.floorMod(selectedCardIndex, cards.size());
        return selectedCardIndex;
    }

    public void setSelectedCardIndex(int index) {
        if (cards.isEmpty()) {
            selectedCardIndex = 0;
            return;
        }
        selectedCardIndex = Math.floorMod(index, cards.size());
    }

    public CardViewModel cycleSelectedCard(int direction) {
        if (cards.isEmpty()) {
            return null;
        }
        selectedCardIndex = Math.floorMod(selectedCardIndex + direction, cards.size());
        return cards.get(selectedCardIndex);
    }

    public String getLastMessage() {
        return lastMessage;
    }

    public CompletableFuture<Void> syncCardsWithBackendAsync(String playerUuid) {
        return CompletableFuture.runAsync(() -> {
            try {
                List<CardCredentials> backendCards = BackendAuthenticator.fetchCardsFromBackend();
                boolean changed = false;
                for (CardCredentials creds : backendCards) {
                    if (database.getCredentials(creds.cardId()) == null) {
                        database.upsertCardCredentials(creds.cardId(), creds.cardToken());
                        refreshCardSync(creds.cardId(), creds.cardToken(), playerUuid, false, false);
                        changed = true;
                    }
                }
                if (changed) {
                    reloadCardsFromDb();
                }
            } catch (Exception e) {
                System.err.println("[SPMEGA] Failed to sync cards with backend: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<Void> refreshOnServerJoinAsync(String playerUuid) {
        return CompletableFuture.runAsync(() -> {
            try {
                List<CardCredentials> backendCards = BackendAuthenticator.fetchCardsFromBackend();
                for (CardCredentials creds : backendCards) {
                    if (database.getCredentials(creds.cardId()) == null) {
                        database.upsertCardCredentials(creds.cardId(), creds.cardToken());
                        refreshCardSync(creds.cardId(), creds.cardToken(), playerUuid, false, false);
                    }
                }
            } catch (Exception e) {
                System.err.println("[SPMEGA] Failed to sync cards with backend: " + e.getMessage());
            }

            List<StoredCard> storedCards = database.loadCards();
            for (StoredCard card : storedCards) {
                refreshCardSync(card.cardId(), card.cardToken(), playerUuid, false, false);
            }
            reloadCardsFromDb();
        });
    }

    public CompletableFuture<List<String>> loadRecipientCardsAsync(String username) {
        CardCredentials credentials = getSelectedCredentials();
        if (credentials == null || username == null || username.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                List<SPWorldsApiClient.PlayerCard> apiCards = apiClient.getPlayerCards(username, toApiAuth(credentials));
                List<String> numbers = new ArrayList<>();
                for (SPWorldsApiClient.PlayerCard apiCard : apiCards) {
                    if (apiCard.number() != null && !apiCard.number().isBlank()) {
                        numbers.add(apiCard.name() + " : " + apiCard.number());
                    }
                }
                lastMessage = "";
                return numbers;
            } catch (Exception exception) {
                lastMessage = "Не удалось получить карты игрока: " + exception.getMessage();
                return List.of();
            }
        });
    }

    public CompletableFuture<String> addCardAsync(String cardIdRaw, String cardTokenRaw, String playerUuid) {
        String cardId = cardIdRaw == null ? "" : cardIdRaw.trim();
        String cardToken = cardTokenRaw == null ? "" : cardTokenRaw.trim();

        if (cardId.isEmpty() || cardToken.isEmpty()) {
            return CompletableFuture.completedFuture("Укажи cardId и cardToken");
        }

        try {
            UUID.fromString(cardId);
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.completedFuture("cardId должен быть UUID");
        }

        return CompletableFuture.supplyAsync(() -> {
            database.upsertCardCredentials(cardId, cardToken);
            boolean refreshed = refreshCardSync(cardId, cardToken, playerUuid, true, true);
            if (!refreshed) {
                database.deleteCard(cardId);
            }
            reloadCardsFromDb();

            if (refreshed && lastMessage.isBlank()) {
                return "Карта добавлена";
            }
            return lastMessage;
        });
    }

    public CompletableFuture<String> removeSelectedCardAsync() {
        CardViewModel selected = getSelectedCard();
        if (selected == null) {
            return CompletableFuture.completedFuture("Нет карты для удаления");
        }

        String cardId = selected.id();
        return CompletableFuture.supplyAsync(() -> {
            database.deleteCard(cardId);
            ModConfig config = SPMega.getConfig();
            if (config != null && config.allowBackend()) {
                BackendAuthenticator.deleteCardOnBackend(cardId);
            }

            reloadCardsFromDb();
            runOnMainThread(() -> {
                if (selectedCardIndex >= cards.size()) {
                    selectedCardIndex = Math.max(0, cards.size() - 1);
                }
            });
            return "Карта удалена";
        });
    }

    public CompletableFuture<String> refreshSelectedCardAsync(String playerUuid) {
        CardViewModel selected = getSelectedCard();
        if (selected == null) {
            return CompletableFuture.completedFuture("Нет карты для обновления");
        }

        return CompletableFuture.supplyAsync(() -> {
            CardCredentials credentials = database.getCredentials(selected.id());
            if (credentials == null) {
                return "Не найдены креды карты";
            }

            refreshCardSync(credentials.cardId(), credentials.cardToken(), playerUuid, false, false);
            reloadCardsFromDb();
            return lastMessage.isBlank() ? "Карта обновлена" : lastMessage;
        });
    }

    public CompletableFuture<Boolean> submitPaymentAsync(PaymentDraft draft) {
        return CompletableFuture.supplyAsync(() -> {
            CardCredentials credentials = database.getCredentials(draft.senderCardId());
            if (credentials == null) {
                lastMessage = "Не найдены креды карты отправителя";
                return false;
            }

            git.yawaflua.tech.spmega.ModConfig config = git.yawaflua.tech.spmega.SPMega.getConfig();
            boolean useBackend = config != null && config.allowBackend();

            try {
                long newBalance = 0;
                if (useBackend) {
                    BackendAuthenticator.createTransactionOnBackend(
                            draft.senderCardId(),
                            draft.recipient(),
                            draft.amount(),
                            draft.comment()
                    );
                    try {
                        refreshCardSync(credentials.cardId(), credentials.cardToken(), "", false, false);
                        StoredCard updatedCard = database.loadCards().stream()
                                .filter(c -> c.cardId().equals(credentials.cardId()))
                                .findFirst()
                                .orElse(null);
                        if (updatedCard != null) {
                            newBalance = updatedCard.balance();
                        }
                    } catch (Exception e) {
                        System.err.println("[SPMEGA] Failed to refresh card balance after transaction: " + e.getMessage());
                    }
                } else {
                    SPWorldsApiClient.TransactionResult result = apiClient.createTransaction(
                            toApiAuth(credentials),
                            draft.recipient(),
                            draft.amount(),
                            draft.comment()
                    );
                    newBalance = result.balance();
                    database.updateCardBalance(credentials.cardId(), newBalance);
                }

                database.insertTransferHistory(
                        credentials.cardId(),
                        draft.recipient(),
                        draft.amount(),
                        draft.comment(),
                        newBalance,
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
        });
    }

    private boolean refreshCardSync(
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
            git.yawaflua.tech.spmega.ModConfig config = git.yawaflua.tech.spmega.SPMega.getConfig();
            if (config != null && config.allowBackend()) {
                BackendAuthenticator.sendCardToBackend(cardId, cardToken);
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
        runOnMainThread(() -> {
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
        });
    }

    private CardCredentials getSelectedCredentials() {
        CardViewModel selected = getSelectedCard();
        if (selected == null) {
            return null;
        }
        return database.getCredentials(selected.id());
    }

    public BankDatabase getDatabase() {
        return database;
    }
}
