package git.yawaflua.tech.spmega.client.ui.service;

public record StoredCard(String cardId, String cardToken, String cardName, String cardNumber, long balance,
                         String ownerUuid) {
}

