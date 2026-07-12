package git.yawaflua.tech.spmega.client.ui.service;

public record CardCredentials(String cardId, String cardToken, boolean webhookEnabled) {
    public CardCredentials(String cardId, String cardToken) {
        this(cardId, cardToken, false);
    }
}

