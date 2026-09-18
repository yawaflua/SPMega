package screens

import (
	"encoding/base64"
	"testing"
)

func TestDecodeBackendCardToken(t *testing.T) {
	t.Parallel()
	const cardID = "a9f093c8-0e45-455b-8f65-146da2a06f4a"
	const token = "raw-card-token"
	encoded := base64.StdEncoding.EncodeToString([]byte(cardID + ":" + token))
	if got := decodeBackendCardToken(cardID, encoded); got != token {
		t.Fatalf("decodeBackendCardToken() = %q, want %q", got, token)
	}
	if got := decodeBackendCardToken(cardID, token); got != token {
		t.Fatalf("raw decodeBackendCardToken() = %q, want %q", got, token)
	}
}

func TestParseRemoteTransactionsAcceptsUserWideHistory(t *testing.T) {
	t.Parallel()
	raw := []byte(`[{"senderCardNumber":"12345","receiverName":"Target","amount":4,"comment":"ok","transactionDate":"2026-08-01T00:00:00Z"}]`)
	transactions, err := parseRemoteTransactions(raw, "card-uuid")
	if err != nil {
		t.Fatal(err)
	}
	if len(transactions) != 1 || transactions[0].Receiver != "Target" {
		t.Fatalf("transactions = %#v", transactions)
	}
}
