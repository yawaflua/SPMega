package backend

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
)

func TestAuthenticate(t *testing.T) {
	t.Parallel()

	var saved string
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		switch request.URL.Path {
		case "/api/v1/auth/start":
			var body map[string]string
			if err := json.NewDecoder(request.Body).Decode(&body); err != nil {
				t.Error(err)
			}
			if body["userName"] != "Steve" || body["userUUID"] != "uuid" {
				t.Errorf("unexpected start body: %#v", body)
			}
			_, _ = response.Write([]byte(`{"sessionId":"server-id"}`))
		case "/api/v1/auth/validate":
			_, _ = response.Write([]byte(`{"token":"jwt"}`))
		default:
			http.NotFound(response, request)
		}
	}))
	defer server.Close()

	auth := NewAuthenticator(AuthenticatorConfig{BaseURL: server.URL, SetToken: func(token string) { saved = token }})
	joined := ""
	token, err := auth.Authenticate(context.Background(), "Steve", "uuid", func(_ context.Context, sessionID string) error {
		joined = sessionID
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if token != "jwt" || saved != "jwt" || auth.Token() != "jwt" || joined != "server-id" {
		t.Fatalf("token=%q saved=%q current=%q joined=%q", token, saved, auth.Token(), joined)
	}
}

func TestEnsureCoalescesReauthentication(t *testing.T) {
	t.Parallel()

	auth := NewAuthenticator(AuthenticatorConfig{})
	started := make(chan struct{})
	release := make(chan struct{})
	var calls atomic.Int32
	reauth := func(context.Context) (string, error) {
		if calls.Add(1) == 1 {
			close(started)
		}
		<-release
		return "jwt", nil
	}

	results := make(chan string, 2)
	go func() { token, _ := auth.Ensure(context.Background(), reauth); results <- token }()
	<-started
	go func() { token, _ := auth.Ensure(context.Background(), reauth); results <- token }()
	close(release)
	if first, second := <-results, <-results; first != "jwt" || second != "jwt" {
		t.Fatalf("tokens = %q, %q", first, second)
	}
	if calls.Load() != 1 {
		t.Fatalf("reauth calls = %d, want 1", calls.Load())
	}
}
