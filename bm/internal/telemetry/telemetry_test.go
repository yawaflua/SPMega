package telemetry

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

func TestCollectorBoundedFIFO(t *testing.T) {
	c := NewCollector()
	for i := 0; i < MaxEvents+10; i++ {
		c.Record(NewEvent("event", map[string]any{"i": i}))
	}
	got := c.Drain()
	if len(got) != MaxEvents {
		t.Fatalf("got %d events", len(got))
	}
	if got[0].Payload["i"] != 10 || got[len(got)-1].Payload["i"] != MaxEvents+9 {
		t.Fatalf("FIFO bounds wrong: first=%v last=%v", got[0].Payload["i"], got[len(got)-1].Payload["i"])
	}
}

func TestCollectorConcurrent(t *testing.T) {
	c := NewCollector()
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			for j := 0; j < 100; j++ {
				c.Record(NewEvent("x", map[string]any{"i": i, "j": j}))
			}
		}(i)
	}
	wg.Wait()
	if got := c.Size(); got != MaxEvents {
		t.Fatalf("got %d", got)
	}
}

func TestSenderBatchAuthAndShape(t *testing.T) {
	var body map[string]any
	var auth string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/telemetry" {
			t.Errorf("path %s", r.URL.Path)
		}
		auth = r.Header.Get("Authorization")
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Error(err)
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()
	c := NewCollector()
	c.Record(NewEvent("lifecycle", map[string]any{"phase": "client_init"}))
	s := NewSender(Config{BackendURL: srv.URL, Token: "secret", ClientVersion: "v", Collector: c})
	if err := s.Flush(); err != nil {
		t.Fatal(err)
	}
	if auth != "Bearer secret" {
		t.Fatalf("auth=%q", auth)
	}
	for _, key := range []string{"clientVersion", "sessionId", "sentAt", "events"} {
		if _, ok := body[key]; !ok {
			t.Errorf("missing %s", key)
		}
	}
}

func TestSenderOmitsDefaultToken(t *testing.T) {
	var auth string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		auth = r.Header.Get("Authorization")
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()
	c := NewCollector()
	c.Record(NewEvent("x", nil))
	s := NewSender(Config{BackendURL: srv.URL, Token: DefaultToken, Collector: c})
	if err := s.Flush(); err != nil {
		t.Fatal(err)
	}
	if auth != "" {
		t.Fatalf("auth=%q", auth)
	}
}

func TestLifecycleSafeStartStop(t *testing.T) {
	s := NewSender(Config{BackendURL: "http://127.0.0.1:1", Interval: time.Millisecond})
	if err := s.Start(); err != nil {
		t.Fatal(err)
	}
	if err := s.Start(); err != nil {
		t.Fatal(err)
	}
	if err := s.Stop(); err != nil {
		t.Fatal(err)
	}
	if err := s.Stop(); err != nil {
		t.Fatal(err)
	}
}

func TestSanitizerAndTarget(t *testing.T) {
	got := SanitizeURI("https://spworlds.ru/pay/123e4567-e89b-12d3-a456-426614174000?token=jwt&x=y")
	if got != "spworlds.ru/pay/<uuid>?token=<redacted>&x=y" {
		t.Fatalf("%s", got)
	}
	if ClassifyTarget("https://api.mojang.com/x") != "mojang" {
		t.Fatal("target")
	}
}

func TestSystemInfoAndPublicIPOptIn(t *testing.T) {
	c := NewCollector()
	var seen []Event
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			Events []Event `json:"events"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		seen = b.Events
		w.WriteHeader(200)
	}))
	defer srv.Close()
	s := NewSender(Config{BackendURL: srv.URL, Collector: c, CollectSystemInfo: true, CollectPublicIP: true, PublicIPCollector: func(_ context.Context) string { return "1.2.3.4" }})
	c.Record(NewEvent("x", nil))
	if err := s.Flush(); err != nil {
		t.Fatal(err)
	}
	if len(seen) != 2 {
		t.Fatalf("events=%d", len(seen))
	}
	if seen[1].Payload["publicIp"] != "1.2.3.4" {
		t.Fatalf("public ip=%v", seen[1].Payload["publicIp"])
	}
}
