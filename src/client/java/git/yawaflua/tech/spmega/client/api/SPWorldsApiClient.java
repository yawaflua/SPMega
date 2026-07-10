package git.yawaflua.tech.spmega.client.api;

import com.google.gson.*;
import git.yawaflua.tech.spmega.client.telemetry.InstrumentedHttpClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class SPWorldsApiClient {
    private final InstrumentedHttpClient httpClient;
    private final Gson gson;
    private final String baseUrl;

    public SPWorldsApiClient(String baseUrl) {
        this.httpClient = new InstrumentedHttpClient();
        this.gson = new Gson();
        this.baseUrl = normalizeBaseUrl(baseUrl);
    }

    private static String encodeAuth(CardAuth auth) {
        String raw = auth.cardId() + ":" + auth.cardToken();
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String normalizeBaseUrl(String rawBaseUrl) {
        String fallback = "https://spworlds.ru";
        if (rawBaseUrl == null || rawBaseUrl.trim().isEmpty()) {
            return fallback;
        }

        String value = rawBaseUrl.trim();
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String getString(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        return json.get(key).getAsString();
    }

    public CardInfo getCardInfo(CardAuth auth) throws IOException, InterruptedException {
        HttpRequest request = requestBuilder("/api/public/card", auth).GET().build();
        String body = send(request);
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has("statusCode")) {
                switch (json.get("statusCode").getAsInt()) {
                    case 403:
                        throw new IOException("Апи вернула ошибку: " + json.get("message").getAsString());
                    default:
                        System.out.println("Unhandled status code in card info response: " + json.get("statusCode").getAsInt());
                        break;
                }
            }
            long balance = json.has("balance") ? json.get("balance").getAsLong() : 0L;
            String webhook = json.has("webhook") && !json.get("webhook").isJsonNull()
                    ? json.get("webhook").getAsString()
                    : "";
            return new CardInfo(balance, webhook);
        } catch (Exception e) {
            System.out.println("Failed to parse card info response: " + e.getMessage());
            System.out.println(body);

            throw new IOException("Failed to parse card info response", e);
        }
    }

    public List<PlayerCard> getPlayerCards(String username, CardAuth auth) throws IOException, InterruptedException {
        HttpRequest request = requestBuilder("/api/public/accounts/" + username + "/cards", auth).GET().build();
        String body = send(request);
        try {
            JsonArray json = JsonParser.parseString(body).getAsJsonArray();

            List<PlayerCard> cards = new ArrayList<>();
            for (JsonElement element : json) {
                JsonObject card = element.getAsJsonObject();
                String name = card.has("name") ? card.get("name").getAsString() : "";
                String number = card.has("number") ? card.get("number").getAsString() : "";
                cards.add(new PlayerCard(name, number));
            }
            return cards;
        } catch (Exception e) {
            System.out.println("Failed to parse player cards response: " + e.getMessage());
            System.out.println(body);
            throw new IOException("Failed to parse player cards response", e);
        }
    }

    public AccountMe getAccountMe(CardAuth auth) throws IOException, InterruptedException {
        HttpRequest request = requestBuilder("/api/public/accounts/me", auth).GET().build();
        String body = send(request);
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            String id = json.has("id") ? json.get("id").getAsString() : "";
            String username = json.has("username") ? json.get("username").getAsString() : "";
            String minecraftUuid = json.has("minecraftUUID") ? json.get("minecraftUUID").getAsString() : "";

            List<AccountCard> cards = new ArrayList<>();
            if (json.has("cards") && json.get("cards").isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray("cards")) {
                    JsonObject card = element.getAsJsonObject();
                    cards.add(new AccountCard(
                            getString(card, "id"),
                            getString(card, "name"),
                            getString(card, "number"),
                            card.has("color") && !card.get("color").isJsonNull() ? card.get("color").getAsInt() : 0
                    ));
                }
            }

            return new AccountMe(id, username, minecraftUuid, cards);
        } catch (Exception e) {
            System.out.println("Failed to parse account info response: " + e.getMessage());
            System.out.println(body);

            throw new IOException("Failed to parse account info response", e);
        }
    }

    public TransactionResult createTransaction(CardAuth auth, String receiver, long amount, String comment)
            throws IOException, InterruptedException {
        JsonObject payload = new JsonObject();
        payload.addProperty("receiver", receiver);
        payload.addProperty("amount", amount);
        payload.addProperty("comment", comment.isEmpty() ? "Перевод через SPMega" : comment);

        HttpRequest request = requestBuilder("/api/public/transactions", auth)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build();

        String body = send(request);
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            long balance = json.has("balance") ? json.get("balance").getAsLong() : 0L;
            return new TransactionResult(balance);
        } catch (Exception exception) {
            System.out.println("Failed to parse transaction response: " + exception.getMessage());
            System.out.println(body);

            throw new IOException("Failed to parse transaction response", exception);
        }
    }

    private HttpRequest.Builder requestBuilder(String path, CardAuth auth) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + encodeAuth(auth))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
    }

    private String send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request);
        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            System.out.println(response.body());
            JsonElement parsed = JsonParser.parseString(response.body());
            JsonObject object = parsed.isJsonArray() ? parsed.getAsJsonArray().get(0).getAsJsonObject() : parsed.getAsJsonObject();
            var message = "";
            if (object.has("error") && !object.get("error").isJsonNull()) {
                message = "Ошибка в запросе: " + object.get("error").getAsString();
            } else if (object.has("message") && !object.get("message").isJsonNull()) {
                message = object.get("message").getAsString();
            }
            throw new IOException(statusCode + ": " + message);
        }
        return response.body();
    }

    public record CardAuth(String cardId, String cardToken) {
    }

    public record CardInfo(long balance, String webhook) {
    }

    public record PlayerCard(String name, String number) {
    }

    public record AccountMe(String id, String username, String minecraftUuid, List<AccountCard> cards) {
    }

    public record AccountCard(String id, String name, String number, int color) {
    }

    public record TransactionResult(long balance) {
    }
}

