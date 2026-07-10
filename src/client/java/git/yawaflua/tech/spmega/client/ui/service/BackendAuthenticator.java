package git.yawaflua.tech.spmega.client.ui.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import git.yawaflua.tech.spmega.ModConfig;
import git.yawaflua.tech.spmega.SPMega;
import git.yawaflua.tech.spmega.client.telemetry.InstrumentedHttpClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.session.Session;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BackendAuthenticator {

    public static boolean authenticate(MinecraftClient client) {
        Session session = client.getSession();
        if (session == null || session.getUuidOrNull() == null) {
            System.err.println("Cannot authenticate: Client has no valid session.");
            return false;
        }

        MinecraftSessionService sessionService = client.getApiServices().sessionService();

        try {
            var serverId = sendStartSessionRequestToBackend(session.getUsername(), session.getUuidOrNull());
            System.out.println("[SPMEGA] Trying to auth in mojang with serverId: " + serverId);
            sessionService.joinServer(
                    session.getUuidOrNull(),
                    session.getAccessToken(),
                    serverId
            );
            System.out.println("[SPMEGA] Sending session submitter to backend");

            return sendAuthRequestToBackend(session.getUuidOrNull(), serverId);

        } catch (AuthenticationException e) {
            System.err.println("I cant auth by Mojang: " + e.getMessage());
            System.err.println("Please check your credentials and try again.");
            return false;

        } catch (Exception e) {
            System.err.println("Failed to authenticate with backend: " + e.getMessage());
            return false;

        }
    }

    private static String sendStartSessionRequestToBackend(String username, UUID uuid) throws IOException, InterruptedException {
        ModConfig config = SPMega.getConfig();
        String apiDomain = (config != null) ? config.apiDomain() : ModConfig.DEFAULT_API_DOMAIN;
        if (apiDomain == null || apiDomain.isBlank()) {
            apiDomain = ModConfig.DEFAULT_API_DOMAIN;
        }
        if (apiDomain.endsWith("/")) {
            apiDomain = apiDomain.substring(0, apiDomain.length() - 1);
        }

        JsonObject jsonPayload = new JsonObject();
        jsonPayload.addProperty("userName", username);
        jsonPayload.addProperty("userUUID", uuid.toString());
        String requestBody = jsonPayload.toString();

        String url = apiDomain + "/api/v1/auth/start";

        InstrumentedHttpClient httpClient = new InstrumentedHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method("POST", HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request);
        if (response.statusCode() != 200) {
            System.err.println("[SPMEGA] Server returned status code " + response.statusCode() + " on start: " + response.body());
            throw new IOException("Server returned status code " + response.statusCode() + " on start: " + response.body());
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!json.has("sessionId")) {
            System.err.println("[SPMEGA] Invalid response from start endpoint: " + response.body());
            throw new IOException("Invalid response from start endpoint: " + response.body());
        }
        return json.get("sessionId").getAsString();
    }

    private static boolean sendAuthRequestToBackend(UUID uuid, String serverId) throws IOException, InterruptedException {
        ModConfig config = SPMega.getConfig();
        String apiDomain = (config != null) ? config.apiDomain() : ModConfig.DEFAULT_API_DOMAIN;
        if (apiDomain == null || apiDomain.isBlank()) {
            apiDomain = ModConfig.DEFAULT_API_DOMAIN;
        }
        if (apiDomain.endsWith("/")) {
            apiDomain = apiDomain.substring(0, apiDomain.length() - 1);
        }

        JsonObject jsonPayload = new JsonObject();
        jsonPayload.addProperty("userUUID", uuid.toString());
        jsonPayload.addProperty("sessionId", serverId);
        String requestBody = jsonPayload.toString();

        String url = apiDomain + "/api/v1/auth/validate";

        InstrumentedHttpClient httpClient = new InstrumentedHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request);
        if (response.statusCode() != 200) {
            System.err.println("[SPMEGA] Server returned status code " + response.statusCode() + " on validate: " + response.body());
            throw new IOException("Server returned status code " + response.statusCode() + " on validate: " + response.body());
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!json.has("token")) {
            System.err.println("[SPMEGA] Invalid response from validate endpoint: " + response.body());
            throw new IOException("Invalid response from validate endpoint: " + response.body());
        }
        if (config == null) {
            System.err.println("[SPMEGA] Config is null, cannot save token.");
            throw new IOException("Config is null, cannot save token.");
        }

        String token = json.get("token").getAsString();
        ModConfig updated = new ModConfig(
                config.apiDomain(),
                token,
                config.allowBackend(),
                config.signQuickPayEnabled(),
                config.gpsEnabled(),
                config.gpsPosition(),
                config.telemetryEnabled(),
                config.telemetryIntervalSeconds(),
                config.telemetryCollectSystemInfo()
        );
        SPMega.setConfig(updated);
        System.out.println("[SPMEGA] Backend auth successful, saved token.");
        return true;
    }

    public static void sendCardToBackend(String cardId, String cardToken) {
        ModConfig config = SPMega.getConfig();
        if (config == null || !config.allowBackend()) {
            return;
        }

        String apiDomain = config.apiDomain();
        if (apiDomain == null || apiDomain.isBlank()) {
            apiDomain = ModConfig.DEFAULT_API_DOMAIN;
        }
        if (apiDomain.endsWith("/")) {
            apiDomain = apiDomain.substring(0, apiDomain.length() - 1);
        }

        String url = apiDomain + "/api/v1/auth/cards";

        JsonObject jsonPayload = new JsonObject();
        jsonPayload.addProperty("id", cardId);
        jsonPayload.addProperty("token", cardToken);
        String requestBody = jsonPayload.toString();

        InstrumentedHttpClient httpClient = new InstrumentedHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + config.apiToken())
                .PUT(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        httpClient.sendAsync(request)
                .thenAccept(response -> {
                    if (response.statusCode() != 200 && response.statusCode() != 204) {
                        System.err.println("[SPMEGA] Failed to send card to backend: status code "
                                + response.statusCode() + ", body: " + response.body());
                    } else {
                        System.out.println("[SPMEGA] Card successfully synchronized with backend.");
                    }
                })
                .exceptionally(throwable -> {
                    System.err.println("[SPMEGA] Error sending card to backend: " + throwable.getMessage());
                    return null;
                });
    }

    public static List<CardCredentials> fetchCardsFromBackend() throws IOException, InterruptedException {
        ModConfig config = SPMega.getConfig();
        if (config == null || !config.allowBackend()) {
            return List.of();
        }

        String apiDomain = config.apiDomain();
        if (apiDomain == null || apiDomain.isBlank()) {
            apiDomain = ModConfig.DEFAULT_API_DOMAIN;
        }
        if (apiDomain.endsWith("/")) {
            apiDomain = apiDomain.substring(0, apiDomain.length() - 1);
        }

        String url = apiDomain + "/api/v1/auth/cards";

        InstrumentedHttpClient httpClient = new InstrumentedHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + config.apiToken())
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request);
        if (response.statusCode() != 200) {
            throw new IOException("Server returned status code " + response.statusCode() + " on fetch cards.");
        }

        com.google.gson.JsonArray cardsArray = JsonParser.parseString(response.body()).getAsJsonArray();
        List<CardCredentials> cards = new ArrayList<>();
        for (com.google.gson.JsonElement el : cardsArray) {
            JsonObject cardJson = el.getAsJsonObject();
            String cardId = cardJson.has("cardId") && !cardJson.get("cardId").isJsonNull()
                    ? cardJson.get("cardId").getAsString()
                    : (cardJson.has("id") && !cardJson.get("id").isJsonNull() ? cardJson.get("id").getAsString() : "");

            String cardToken = cardJson.has("cardToken") && !cardJson.get("cardToken").isJsonNull()
                    ? cardJson.get("cardToken").getAsString()
                    : (cardJson.has("token") && !cardJson.get("token").isJsonNull() ? cardJson.get("token").getAsString() : "");

            if (!cardId.isEmpty() && !cardToken.isEmpty()) {
                cards.add(new CardCredentials(cardId, cardToken));
            }
        }
        return cards;
    }

    public static void deleteCardOnBackend(String cardId) {
        ModConfig config = SPMega.getConfig();
        if (config == null || !config.allowBackend()) {
            return;
        }

        String apiDomain = config.apiDomain();
        if (apiDomain == null || apiDomain.isBlank()) {
            apiDomain = ModConfig.DEFAULT_API_DOMAIN;
        }
        if (apiDomain.endsWith("/")) {
            apiDomain = apiDomain.substring(0, apiDomain.length() - 1);
        }

        String url = apiDomain + "/api/v1/auth/cards/" + cardId;

        InstrumentedHttpClient httpClient = new InstrumentedHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + config.apiToken())
                .DELETE()
                .build();

        httpClient.sendAsync(request)
                .thenAccept(response -> {
                    if (response.statusCode() != 200 && response.statusCode() != 204) {
                        System.err.println("[SPMEGA] Failed to delete card from backend: status code "
                                + response.statusCode() + ", body: " + response.body());
                    } else {
                        System.out.println("[SPMEGA] Card successfully deleted from backend.");
                    }
                })
                .exceptionally(throwable -> {
                    System.err.println("[SPMEGA] Error deleting card from backend: " + throwable.getMessage());
                    return null;
                });
    }

    public static boolean createTransactionOnBackend(String senderCardId, String receiver, long amount, String comment) throws IOException, InterruptedException {
        ModConfig config = SPMega.getConfig();
        if (config == null) {
            throw new IOException("ModConfig is null");
        }

        String apiDomain = config.apiDomain();
        if (apiDomain == null || apiDomain.isBlank()) {
            apiDomain = ModConfig.DEFAULT_API_DOMAIN;
        }
        if (apiDomain.endsWith("/")) {
            apiDomain = apiDomain.substring(0, apiDomain.length() - 1);
        }

        String url = apiDomain + "/api/v1/transactions";

        JsonObject jsonPayload = new JsonObject();
        jsonPayload.addProperty("cardToUse", senderCardId);
        jsonPayload.addProperty("cardId", senderCardId);
        jsonPayload.addProperty("receiverCard", receiver);
        jsonPayload.addProperty("receiverName", receiver);
        jsonPayload.addProperty("amount", amount);
        jsonPayload.addProperty("comment", comment);
        String requestBody = jsonPayload.toString();

        InstrumentedHttpClient httpClient = new InstrumentedHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + config.apiToken())
                .PUT(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request);
        if (response.statusCode() != 200 && response.statusCode() != 201 && response.statusCode() != 204) {
            throw new IOException("Server returned status code " + response.statusCode() + ": " + response.body());
        }
        return true;
    }

    public static List<BankDatabase.LocalTransaction> fetchTransactionsFromBackend(String cardId, int page) throws IOException, InterruptedException {
        System.out.println("[SPMEGA] fetchTransactionsFromBackend called for cardId: " + cardId + ", page: " + page);
        ModConfig config = SPMega.getConfig();
        if (config == null || !config.allowBackend()) {
            System.out.println("[SPMEGA] fetchTransactionsFromBackend aborted: config is null or backend not allowed.");
            return List.of();
        }

        String apiDomain = config.apiDomain();
        if (apiDomain == null || apiDomain.isBlank()) {
            apiDomain = ModConfig.DEFAULT_API_DOMAIN;
        }
        if (apiDomain.endsWith("/")) {
            apiDomain = apiDomain.substring(0, apiDomain.length() - 1);
        }

        String url = apiDomain + "/api/v1/transactions?p=" + page;
        System.out.println("[SPMEGA] Requesting transactions from URL: " + url);

        InstrumentedHttpClient httpClient = new InstrumentedHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + config.apiToken())
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request);
        System.out.println("[SPMEGA] Response status code: " + response.statusCode());
        if (response.statusCode() != 200) {
            System.err.println("[SPMEGA] Failed response body: " + response.body());
            throw new IOException("Server returned status code " + response.statusCode() + " on fetch transactions.");
        }

        String body = response.body();
        System.out.println("[SPMEGA] Response body: " + (body.length() > 500 ? body.substring(0, 500) + "..." : body));

        JsonElement parsed = JsonParser.parseString(body);
        com.google.gson.JsonArray transactionsArray = null;

        if (parsed.isJsonArray()) {
            transactionsArray = parsed.getAsJsonArray();
        } else if (parsed.isJsonObject()) {
            JsonObject obj = parsed.getAsJsonObject();
            if (obj.has("$values") && obj.get("$values").isJsonArray()) {
                transactionsArray = obj.getAsJsonArray("$values");
            } else if (obj.has("transactions") && obj.get("transactions").isJsonArray()) {
                transactionsArray = obj.getAsJsonArray("transactions");
            } else if (obj.has("values") && obj.get("values").isJsonArray()) {
                transactionsArray = obj.getAsJsonArray("values");
            }
        }

        if (transactionsArray == null) {
            System.err.println("[SPMEGA] Response body is not a JSON array or wrapped array: " + body);
            return List.of();
        }

        System.out.println("[SPMEGA] Total transactions returned from server: " + transactionsArray.size());
        List<BankDatabase.LocalTransaction> list = new ArrayList<>();
        for (com.google.gson.JsonElement el : transactionsArray) {
            try {
                JsonObject json = el.getAsJsonObject();

                // Check cardId filter
                String senderCardNumber = "";
                if (json.has("senderCardNumber") && !json.get("senderCardNumber").isJsonNull()) {
                    senderCardNumber = json.get("senderCardNumber").getAsString();
                }

                if (!senderCardNumber.equalsIgnoreCase(cardId)) {
                    continue;
                }

                String receiver = "unknown";
                if (json.has("receiver") && !json.get("receiver").isJsonNull()) {
                    receiver = json.get("receiver").getAsString();
                } else if (json.has("receiverName") && !json.get("receiverName").isJsonNull()) {
                    receiver = json.get("receiverName").getAsString();
                } else if (json.has("receiverCardNumber") && !json.get("receiverCardNumber").isJsonNull()) {
                    receiver = json.get("receiverCardNumber").getAsString();
                } else if (json.has("recipient") && !json.get("recipient").isJsonNull()) {
                    receiver = json.get("recipient").getAsString();
                }

                long amount = json.has("amount") ? json.get("amount").getAsLong() : 0L;
                String comment = json.has("comment") && !json.get("comment").isJsonNull() ? json.get("comment").getAsString() : "";
                String status = json.has("status") && !json.get("status").isJsonNull() ? json.get("status").getAsString() : "SUCCESS";

                String createdAt = "";
                if (json.has("transactionDate") && !json.get("transactionDate").isJsonNull()) {
                    createdAt = json.get("transactionDate").getAsString();
                } else if (json.has("createdAt") && !json.get("createdAt").isJsonNull()) {
                    createdAt = json.get("createdAt").getAsString();
                } else if (json.has("created_at") && !json.get("created_at").isJsonNull()) {
                    createdAt = json.get("created_at").getAsString();
                }

                System.out.printf("[SPMEGA] Parsed transaction: %s -> %s, amount=%d, comment=%s, status=%s%n",
                        createdAt, receiver, amount, comment, status);

                list.add(new BankDatabase.LocalTransaction(receiver, amount, comment, status, createdAt));
            } catch (Exception parseEx) {
                System.err.println("[SPMEGA] Error parsing transaction element: " + parseEx.getMessage());
            }
        }
        System.out.println("[SPMEGA] Returning " + list.size() + " filtered transactions for card " + cardId);
        return list;
    }
}
