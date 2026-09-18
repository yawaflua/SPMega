package screens

import "testing"

func TestParseChatCard(t *testing.T) {
	t.Parallel()

	cardID, token, ok := ParseChatCard(
		"Нажмите: Управление картой",
		[]string{"token-secret", "1e8fc2ef-f58a-40df-b785-f88e2eea28f7", "ignored"},
	)
	if !ok {
		t.Fatal("expected card message to parse")
	}
	if cardID != "1e8fc2ef-f58a-40df-b785-f88e2eea28f7" || token != "token-secret" {
		t.Fatalf("got cardID=%q token=%q", cardID, token)
	}
}

func TestParseChatCardRejectsUnrelatedOrIncompleteMessage(t *testing.T) {
	t.Parallel()

	tests := []struct {
		message string
		values  []string
	}{
		{message: "обычный чат", values: []string{"token", "card"}},
		{message: "Управление картой", values: []string{"token"}},
		{message: "УПРАВЛЕНИЕ КАРТОЙ", values: []string{"", "card"}},
	}
	for _, test := range tests {
		if _, _, ok := ParseChatCard(test.message, test.values); ok {
			t.Fatalf("unexpected parse for %q with %#v", test.message, test.values)
		}
	}
}
