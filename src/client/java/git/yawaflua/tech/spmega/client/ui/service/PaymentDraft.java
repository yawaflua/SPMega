package git.yawaflua.tech.spmega.client.ui.service;

public record PaymentDraft(String senderCardId, String recipient, long amount, String comment) {
}

