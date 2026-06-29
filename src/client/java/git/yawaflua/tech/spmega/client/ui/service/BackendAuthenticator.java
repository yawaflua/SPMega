package git.yawaflua.tech.spmega.client.ui.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import git.yawaflua.tech.spmega.ModConfig;
import git.yawaflua.tech.spmega.SPMega;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.session.Session;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
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

        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method("POST", HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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

        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
                config.gpsPosition()
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

        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + config.apiToken())
                .PUT(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
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

        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + config.apiToken())
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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

        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + config.apiToken())
                .DELETE()
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
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

        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + config.apiToken())
                .PUT(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 && response.statusCode() != 201 && response.statusCode() != 204) {
            throw new IOException("Server returned status code " + response.statusCode() + ": " + response.body());
        }
        return true;
    }

    public static List<BankDatabase.LocalTransaction> fetchTransactionsFromBackend(String cardId) throws IOException, InterruptedException {
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

        String url = apiDomain + "/api/v1/transactions?cardId=" + cardId;

        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + config.apiToken())
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Server returned status code " + response.statusCode() + " on fetch transactions.");
        }

        com.google.gson.JsonArray transactionsArray = JsonParser.parseString(response.body()).getAsJsonArray();
        List<BankDatabase.LocalTransaction> list = new ArrayList<>();
        for (com.google.gson.JsonElement el : transactionsArray) {
            JsonObject json = el.getAsJsonObject();
            String receiver = json.has("receiver") ? json.get("receiver").getAsString() : (json.has("recipient") ? json.get("recipient").getAsString() : "unknown");
            long amount = json.has("amount") ? json.get("amount").getAsLong() : 0L;
            String comment = json.has("comment") && !json.get("comment").isJsonNull() ? json.get("comment").getAsString() : "";
            String status = json.has("status") ? json.get("status").getAsString() : "SUCCESS";
            String createdAt = json.has("createdAt") ? json.get("createdAt").getAsString() : (json.has("created_at") ? json.get("created_at").getAsString() : "");

            list.add(new BankDatabase.LocalTransaction(receiver, amount, comment, status, createdAt));
        }
        return list;
    }
}
