package screens

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestLocalBankServiceLoadsPersistedSelection(t *testing.T) {
	t.Parallel()

	directory := t.TempDir()
	state := persistedState{Cards: []CardViewModel{{ID: "one"}, {ID: "two"}}, Selected: 1}
	data, err := json.Marshal(state)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(directory, "spmega-bank.json"), data, 0o600); err != nil {
		t.Fatal(err)
	}
	service, err := NewLocalBankService(directory, ServiceConfig{})
	if err != nil {
		t.Fatal(err)
	}
	if service.SelectedCardIndex() != 1 {
		t.Fatalf("selected = %d, want 1", service.SelectedCardIndex())
	}
	card, ok := service.SelectedCard()
	if !ok || card.ID != "two" {
		t.Fatalf("selected card = %#v, %v", card, ok)
	}
}

func TestLocalBankServiceMissingAndMalformedState(t *testing.T) {
	t.Parallel()

	if _, err := NewLocalBankService(t.TempDir(), ServiceConfig{}); err != nil {
		t.Fatalf("missing state: %v", err)
	}
	directory := t.TempDir()
	if err := os.WriteFile(filepath.Join(directory, "spmega-bank.json"), []byte("{"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := NewLocalBankService(directory, ServiceConfig{}); err == nil {
		t.Fatal("malformed state unexpectedly loaded")
	}
}

func TestPollerStops(t *testing.T) {
	t.Parallel()

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	poller := StartPoller(ctx, 1, func(context.Context) {})
	poller.Stop()
}
