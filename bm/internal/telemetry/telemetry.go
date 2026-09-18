// Package telemetry provides bounded event collection and opt-in delivery of SPMega client telemetry.
package telemetry

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"runtime"
	"strings"
	"sync"
	"time"
)

const (
	MaxEvents          = 1000
	DefaultBackendURL  = "https://spmega.yawaflua.tech"
	DefaultToken       = "-"
	DefaultInterval    = 60 * time.Second
	telemetryPath      = "/api/v1/telemetry"
	requestTimeout     = 10 * time.Second
	collectorTimeout   = 2 * time.Second
	maxStringValueSize = 4096
)

type Event struct {
	EventType string         `json:"eventType"`
	Timestamp time.Time      `json:"timestamp"`
	Payload   map[string]any `json:"payload"`
}

func NewEvent(eventType string, payload map[string]any) Event {
	if payload == nil {
		payload = map[string]any{}
	}
	return Event{EventType: eventType, Timestamp: time.Now().UTC(), Payload: cloneMap(payload)}
}

// Collector is bounded and thread-safe. Oldest events are discarded when full.
type Collector struct {
	mu     sync.Mutex
	events []Event
	limit  int
}

func NewCollector() *Collector { return &Collector{limit: MaxEvents} }
func (c *Collector) Record(event Event) {
	if c == nil || event.EventType == "" {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	event = sanitizedEvent(event)
	if len(c.events) >= c.limit {
		copy(c.events, c.events[1:])
		c.events[len(c.events)-1] = event
		return
	}
	c.events = append(c.events, event)
}
func (c *Collector) Size() int {
	if c == nil {
		return 0
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.events)
}
func (c *Collector) Len() int { return c.Size() }
func (c *Collector) Drain() []Event {
	if c == nil {
		return nil
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	result := make([]Event, len(c.events))
	copy(result, c.events)
	c.events = c.events[:0]
	return result
}

var defaultCollector = NewCollector()

func DefaultCollector() *Collector { return defaultCollector }
func Record(event Event)           { defaultCollector.Record(event) }

type Config struct {
	BackendURL          string
	Token               string
	ClientVersion       string
	Interval            time.Duration
	CollectSystemInfo   bool
	CollectPublicIP     bool
	HTTPClient          *http.Client
	Collector           *Collector
	SystemInfoCollector func(context.Context) map[string]any
	PublicIPCollector   func(context.Context) string
}

type Sender struct {
	backendURL, token, clientVersion   string
	interval                           time.Duration
	collectSystemInfo, collectPublicIP bool
	client                             *http.Client
	collector                          *Collector
	systemInfo                         func(context.Context) map[string]any
	publicIP                           func(context.Context) string
	lifecycleMu                        sync.Mutex
	cancel                             context.CancelFunc
	stopped                            chan struct{}
	flushMu                            sync.Mutex
	tokenMu                            sync.RWMutex
	sessionID                          string
}

func NewSender(config Config) *Sender {
	if strings.TrimSpace(config.BackendURL) == "" {
		config.BackendURL = DefaultBackendURL
	}
	if config.Interval <= 0 {
		config.Interval = DefaultInterval
	}
	if config.ClientVersion == "" {
		config.ClientVersion = "unknown"
	}
	if config.Collector == nil {
		config.Collector = defaultCollector
	}
	if config.HTTPClient == nil {
		config.HTTPClient = &http.Client{}
	}
	return &Sender{backendURL: strings.TrimRight(strings.TrimSpace(config.BackendURL), "/"), token: config.Token, clientVersion: config.ClientVersion, interval: config.Interval, collectSystemInfo: config.CollectSystemInfo, collectPublicIP: config.CollectPublicIP, client: config.HTTPClient, collector: config.Collector, systemInfo: config.SystemInfoCollector, publicIP: config.PublicIPCollector, sessionID: newUUID()}
}
func (s *Sender) SessionID() string {
	if s == nil {
		return ""
	}
	return s.sessionID
}

// SetToken updates authorization used by later flushes.
func (s *Sender) SetToken(token string) {
	if s == nil {
		return
	}
	s.tokenMu.Lock()
	s.token = token
	s.tokenMu.Unlock()
}

func (s *Sender) tokenValue() string {
	s.tokenMu.RLock()
	defer s.tokenMu.RUnlock()
	return s.token
}

// Start starts (or restarts) periodic delivery.
func (s *Sender) Start() error {
	if s == nil {
		return errors.New("nil telemetry sender")
	}
	s.lifecycleMu.Lock()
	if s.cancel != nil {
		cancel, stopped := s.cancel, s.stopped
		s.cancel, s.stopped = nil, nil
		cancel()
		s.lifecycleMu.Unlock()
		<-stopped
		s.lifecycleMu.Lock()
	}
	ctx, cancel := context.WithCancel(context.Background())
	s.cancel, s.stopped = cancel, make(chan struct{})
	stopped := s.stopped
	s.lifecycleMu.Unlock()
	go s.run(ctx, stopped)
	return nil
}
func (s *Sender) run(ctx context.Context, stopped chan struct{}) {
	defer close(stopped)
	ticker := time.NewTicker(s.interval)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			_ = s.Flush()
		case <-ctx.Done():
			return
		}
	}
}

// Stop stops periodic delivery and waits for its goroutine. It does not flush.
func (s *Sender) Stop() error {
	if s == nil {
		return nil
	}
	s.lifecycleMu.Lock()
	if s.cancel == nil {
		s.lifecycleMu.Unlock()
		return nil
	}
	cancel, stopped := s.cancel, s.stopped
	s.cancel, s.stopped = nil, nil
	cancel()
	s.lifecycleMu.Unlock()
	<-stopped
	return nil
}

// Flush drains before sending. Failed sends are not requeued, matching Java behavior.
func (s *Sender) Flush() error {
	if s == nil {
		return errors.New("nil telemetry sender")
	}
	s.flushMu.Lock()
	defer s.flushMu.Unlock()
	if s.collectSystemInfo {
		ctx, cancel := context.WithTimeout(context.Background(), collectorTimeout)
		info := s.collectInfo(ctx)
		cancel()
		s.collector.Record(NewEvent("system_info", info))
	}
	events := s.collector.Drain()
	if len(events) == 0 {
		return nil
	}
	batch := struct {
		ClientVersion string    `json:"clientVersion"`
		SessionID     string    `json:"sessionId"`
		SentAt        time.Time `json:"sentAt"`
		Events        []Event   `json:"events"`
	}{s.clientVersion, s.sessionID, time.Now().UTC(), events}
	body, err := json.Marshal(batch)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, s.backendURL+telemetryPath, strings.NewReader(string(body)))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	if token := strings.TrimSpace(s.tokenValue()); token != "" && token != DefaultToken {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := s.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("telemetry endpoint returned HTTP %d", resp.StatusCode)
	}
	return nil
}
func (s *Sender) collectInfo(ctx context.Context) map[string]any {
	if s.systemInfo != nil {
		return boundedCollector(ctx, s.systemInfo)
	}
	info := safeSystemInfo()
	if s.collectPublicIP && s.publicIP != nil {
		if ip := boundedStringCollector(ctx, s.publicIP); ip != "" {
			info["publicIp"] = ip
		}
	}
	return info
}
func boundedCollector(ctx context.Context, fn func(context.Context) map[string]any) (result map[string]any) {
	result = map[string]any{}
	done := make(chan map[string]any, 1)
	go func() { done <- fn(ctx) }()
	select {
	case result = <-done:
		if result == nil {
			result = map[string]any{}
		}
	case <-ctx.Done():
	}
	return sanitizeMap(result)
}
func boundedStringCollector(ctx context.Context, fn func(context.Context) string) string {
	done := make(chan string, 1)
	go func() { done <- fn(ctx) }()
	select {
	case value := <-done:
		return value
	case <-ctx.Done():
		return ""
	}
}
func safeSystemInfo() map[string]any {
	info := map[string]any{"cpuCores": runtime.NumCPU(), "goVersion": runtime.Version(), "osName": runtime.GOOS, "osArch": runtime.GOARCH}
	if hostname, err := os.Hostname(); err == nil && hostname != "" {
		info["hostName"] = hostname
	}
	return info
}
func newUUID() string {
	var bytes [16]byte
	if _, err := rand.Read(bytes[:]); err != nil {
		return fmt.Sprintf("%d", time.Now().UnixNano())
	}
	bytes[6] = (bytes[6] & 0x0f) | 0x40
	bytes[8] = (bytes[8] & 0x3f) | 0x80
	encoded := hex.EncodeToString(bytes[:])
	return encoded[0:8] + "-" + encoded[8:12] + "-" + encoded[12:16] + "-" + encoded[16:20] + "-" + encoded[20:32]
}

var uuidPattern = regexp.MustCompile(`(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}`)
var tokenPattern = regexp.MustCompile(`(?i)(token|key|secret|password|auth|bearer)=[^&]+`)

// SanitizeURI preserves Java's host/path output while redacting query credentials.
func SanitizeURI(raw string) string {
	if strings.TrimSpace(raw) == "" {
		return "<invalid-url>"
	}
	u, err := url.Parse(raw)
	if err != nil || u.Hostname() == "" {
		return "<invalid-url>"
	}
	path := uuidPattern.ReplaceAllString(u.EscapedPath(), "<uuid>")
	result := u.Hostname() + path
	if u.RawQuery != "" {
		result += "?" + tokenPattern.ReplaceAllString(u.RawQuery, "$1=<redacted>")
	}
	return result
}
func SanitizeURL(raw string) string { return SanitizeURI(raw) }
func ClassifyTarget(raw string) string {
	u, err := url.Parse(raw)
	if err != nil || u.Hostname() == "" {
		return "unknown"
	}
	host := strings.ToLower(u.Hostname())
	switch {
	case strings.Contains(host, "spworlds.ru"):
		return "spworlds"
	case strings.Contains(host, "spmega") || strings.Contains(host, "ywfl.dev") || strings.Contains(host, "yawaflua"):
		return "spmega-backend"
	case strings.Contains(host, "mojang.com") || strings.Contains(host, "minecraft.net"):
		return "mojang"
	case strings.Contains(host, "sp-mini.ru") || strings.Contains(host, "spmap"):
		return "gps-map"
	case strings.Contains(host, "ipify.org"):
		return "ipify"
	default:
		return "other"
	}
}
func sanitizedEvent(event Event) Event {
	if event.Timestamp.IsZero() {
		event.Timestamp = time.Now().UTC()
	}
	event.Payload = sanitizeMap(event.Payload)
	return event
}
func sanitizeMap(input map[string]any) map[string]any {
	output := make(map[string]any, len(input))
	for key, value := range input {
		output[key] = sanitizeValue(key, value)
	}
	return output
}
func sanitizeValue(key string, value any) any {
	lower := strings.ToLower(key)
	if sensitiveKey(lower) {
		return "<redacted>"
	}
	switch value := value.(type) {
	case map[string]any:
		return sanitizeMap(value)
	case []any:
		result := make([]any, len(value))
		for i, item := range value {
			result[i] = sanitizeValue("", item)
		}
		return result
	case string:
		if len(value) > maxStringValueSize {
			return value[:maxStringValueSize]
		}
		if strings.HasPrefix(value, "http://") || strings.HasPrefix(value, "https://") {
			return SanitizeURI(value)
		}
	}
	return value
}
func sensitiveKey(key string) bool {
	if strings.Contains(key, "last4") {
		return false
	}
	for _, part := range []string{"token", "secret", "password", "bearer", "authorization", "cardnumber", "card_num", "privatekey"} {
		if strings.Contains(key, part) {
			return true
		}
	}
	return key == "key" || key == "auth"
}
func cloneMap(input map[string]any) map[string]any {
	if input == nil {
		return nil
	}
	return sanitizeMap(input)
}
