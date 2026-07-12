package git.yawaflua.tech.spmega.client.ui.service;

import git.yawaflua.tech.spmega.ModConfig;
import git.yawaflua.tech.spmega.SPMega;
import git.yawaflua.tech.spmega.client.api.SPWorldsApiClient;
import git.yawaflua.tech.spmega.client.telemetry.TelemetryCollector;
import git.yawaflua.tech.spmega.client.telemetry.TelemetryEvent;
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

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    public CompletableFuture<Void> syncCardsWithBackendAsync(String playerUuid) {
        return BackendAuthenticator.fetchCardsFromBackendAsync()
                .thenCompose(cards -> syncMissingCardsAsync(cards, playerUuid))
                .thenAccept(changed -> {
                    if (changed) {
                        reloadCardsFromDb();
                    }
                })
                .exceptionally(exception -> {
                    System.err.println("[SPMEGA] Failed to sync cards with backend: " + exception.getMessage());
                    return null;
                });
    }

    public CompletableFuture<Void> refreshOnServerJoinAsync(String playerUuid) {
        return BackendAuthenticator.fetchCardsFromBackendAsync()
                .exceptionally(exception -> {
                    System.err.println("[SPMEGA] Failed to sync cards with backend: " + exception.getMessage());
                    return List.of();
                })
                .thenCompose(cards -> syncMissingCardsAsync(cards, playerUuid))
                .thenCompose(ignored -> refreshStoredCardsAsync(playerUuid))
                .thenRun(this::reloadCardsFromDb);
    }

    public CompletableFuture<List<String>> loadRecipientCardsAsync(String username) {
        CardCredentials credentials = getSelectedCredentials();
        if (credentials == null || username == null || username.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }

        return apiClient.getPlayerCardsAsync(username, toApiAuth(credentials))
                .thenApply(apiCards -> {
                List<String> numbers = new ArrayList<>();
                for (SPWorldsApiClient.PlayerCard apiCard : apiCards) {
                    if (apiCard.number() != null && !apiCard.number().isBlank()) {
                        numbers.add(apiCard.name() + " : " + apiCard.number());
                    }
                }
                lastMessage = "";
                return numbers;
                })
                .exceptionally(exception -> {
                lastMessage = "Не удалось получить карты игрока: " + exception.getMessage();
                return List.of();
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

        return CompletableFuture.runAsync(() -> database.upsertCardCredentials(cardId, cardToken))
                .thenCompose(ignored -> refreshCardAsync(cardId, cardToken, playerUuid, true, true))
                .thenApply(refreshed -> {
                    if (!refreshed) {
                        database.deleteCard(cardId);
                    }
                    reloadCardsFromDb();
                    return refreshed && lastMessage.isBlank() ? "Карта добавлена" : lastMessage;
                });
    }

    public CompletableFuture<String> refreshSelectedCardAsync(String playerUuid) {
        CardViewModel selected = getSelectedCard();
        if (selected == null) {
            return CompletableFuture.completedFuture("Нет карты для обновления");
        }

        return CompletableFuture.supplyAsync(() -> database.getCredentials(selected.id()))
                .thenCompose(credentials -> {
            if (credentials == null) {
                return CompletableFuture.completedFuture("Не найдены креды карты");
            }
                    return refreshCardAsync(credentials.cardId(), credentials.cardToken(), playerUuid, false, false)
                            .thenApply(ignored -> {
                                reloadCardsFromDb();
                                return lastMessage.isBlank() ? "Карта обновлена" : lastMessage;
                            });
                });
    }

    private void recordPaymentEvent(PaymentDraft draft, boolean success, String mode, String destination, long durationMs, String senderLast4) {
        recordPaymentEvent(draft, success, mode, destination, durationMs, senderLast4, null);
    }

    private void recordPaymentEvent(PaymentDraft draft, boolean success, String mode, String destination, long durationMs, String senderLast4, String error) {
        com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
        payload.addProperty("success", success);
        payload.addProperty("mode", mode);
        payload.addProperty("destination", destination);
        payload.addProperty("durationMs", durationMs);
        payload.addProperty("senderCardLast4", senderLast4 != null ? senderLast4 : "unknown");
        payload.addProperty("amount", draft.amount());
        payload.addProperty("receiver", draft.recipient());
        if (error != null) {
            payload.addProperty("error", trimMessage(error));
        }
        TelemetryCollector.instance().record(TelemetryEvent.now("payment", payload));
    }

    public CompletableFuture<Boolean> submitPaymentAsync(PaymentDraft draft) {
        return CompletableFuture.supplyAsync(() -> database.getCredentials(draft.senderCardId()))
                .thenCompose(credentials -> {
            if (credentials == null) {
                lastMessage = "Не найдены креды карты отправителя";
                recordPaymentEvent(draft, false, "local", "no_credentials", 0, null);
                return CompletableFuture.completedFuture(false);
            }

                    ModConfig config = SPMega.getConfig();
            boolean useBackend = config != null && config.allowBackend();
            String mode = useBackend ? "backend" : "spworlds-direct";
            String destination = useBackend ? "backend" : "local-db";
            String senderLast4 = extractLastDigits(credentials.cardId());
            long startNs = System.nanoTime();

                    CompletableFuture<Long> transaction = useBackend
                            ? BackendAuthenticator.createTransactionOnBackendAsync(
                            draft.senderCardId(), draft.recipient(), draft.amount(), draft.comment())
                              .thenCompose(ignored -> refreshCardAsync(
                                      credentials.cardId(), credentials.cardToken(), "", false, false))
                              .thenApply(ignored -> database.loadCards().stream()
                                                    .filter(card -> card.cardId().equals(credentials.cardId()))
                                                    .findFirst()
                                                    .map(StoredCard::balance)
                                                    .orElse(0L))
                            : apiClient.createTransactionAsync(
                            toApiAuth(credentials), draft.recipient(), draft.amount(), draft.comment())
                              .thenApply(result -> {
                                  database.updateCardBalance(credentials.cardId(), result.balance());
                                  return result.balance();
                              });

                    return transaction
                            .thenApply(newBalance -> {
                                database.insertTransferHistory(
                                        credentials.cardId(), draft.recipient(), draft.amount(), draft.comment(),
                                        newBalance, "SUCCESS");
                                reloadCardsFromDb();
                                lastMessage = "Перевод выполнен";
                                recordPaymentEvent(draft, true, mode, destination,
                                        (System.nanoTime() - startNs) / 1_000_000L, senderLast4);
                                return true;
                            })
                            .exceptionally(exception -> {
                                String error = rootMessage(exception);
                                database.insertTransferHistory(
                                        credentials.cardId(), draft.recipient(), draft.amount(), draft.comment(),
                                        null, "FAILED: " + trimMessage(error));
                                lastMessage = "Ошибка перевода: " + error;
                                recordPaymentEvent(draft, false, mode, destination,
                                        (System.nanoTime() - startNs) / 1_000_000L, senderLast4, error);
                                return false;
                            });
                });
    }

    private CompletableFuture<Boolean> refreshCardAsync(
            String cardId,
            String cardToken,
            String playerUuid,
            boolean reportOwnerWarning,
            boolean requireCardInAccount
    ) {
        SPWorldsApiClient.CardAuth auth = new SPWorldsApiClient.CardAuth(cardId, cardToken);
        return apiClient.getAccountMeAsync(auth)
                .thenCompose(me -> apiClient.getCardInfoAsync(auth).thenApply(cardInfo -> {

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
                    ModConfig config = SPMega.getConfig();
            if (config != null && config.allowBackend()) {
                BackendAuthenticator.sendCardToBackend(cardId, cardToken);
            }

            lastMessage = "";
            return true;
                }))
                .exceptionally(exception -> {
                    lastMessage = "Не удалось обновить карту: " + rootMessage(exception);
                    return false;
                });
    }

    public CompletableFuture<String> registerSelectedWebhookAsync() {
        CardViewModel selected = getSelectedCard();
        if (selected == null) {
            return CompletableFuture.completedFuture("Сначала выбери или добавь карту");
        }
        if (selected.webhookEnabled()) {
            return CompletableFuture.completedFuture("Вебхук для этой карты уже включён");
        }

        return BackendAuthenticator.registerWebhookForCardAsync(selected.id())
                .thenApply(ignored -> {
                    database.setWebhookEnabled(selected.id(), true);
                    reloadCardsFromDb();
                    return "Обработка вебхуков включена";
                })
                .exceptionally(exception -> "Не удалось включить вебхук: " + rootMessage(exception));
    }

    public boolean hasWebhookEnabledCards() {
        return database.hasWebhookEnabledCards();
    }

    private CompletableFuture<Boolean> syncMissingCardsAsync(List<CardCredentials> backendCards, String playerUuid) {
        CompletableFuture<Boolean> result = CompletableFuture.completedFuture(false);
        for (CardCredentials credentials : backendCards) {
            result = result.thenCompose(changed -> {
                if (database.getCredentials(credentials.cardId()) != null) {
                    database.setWebhookEnabled(credentials.cardId(), credentials.webhookEnabled());
                    return CompletableFuture.completedFuture(changed);
                }
                database.upsertCardCredentials(credentials.cardId(), credentials.cardToken());
                database.setWebhookEnabled(credentials.cardId(), credentials.webhookEnabled());
                return refreshCardAsync(
                        credentials.cardId(), credentials.cardToken(), playerUuid, false, false)
                        .thenApply(ignored -> true);
            });
        }
        return result;
    }

    private CompletableFuture<Void> refreshStoredCardsAsync(String playerUuid) {
        CompletableFuture<Void> result = CompletableFuture.completedFuture(null);
        for (StoredCard card : database.loadCards()) {
            result = result.thenCompose(ignored -> refreshCardAsync(
                    card.cardId(), card.cardToken(), playerUuid, false, false).thenApply(refreshed -> null));
        }
        return result;
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
                cards.add(new CardViewModel(
                        stored.cardId(), title, stored.balance(), stored.webhookEnabled()));
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
