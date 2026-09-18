package screens

import "testing"

func TestParseBackendCards(t *testing.T) {
	t.Parallel()

	cards, err := parseBackendCards([]byte(`{"$values":[{"id":"card-id","name":"Основная","spworldsID":"12345","token":"secret","webhookConnected":true}]}`))
	if err != nil {
		t.Fatal(err)
	}
	if len(cards) != 1 || cards[0].ID != "card-id" || cards[0].SPWorldsID != "12345" || !cards[0].WebhookConnected {
		t.Fatalf("unexpected cards: %#v", cards)
	}
}
