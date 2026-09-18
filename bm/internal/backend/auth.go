// Package backend contains backend authentication state and protocol helpers.
package backend

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"
)

const maxResponseBody = 1 << 20

type AuthenticatorConfig struct {
	BaseURL    string
	HTTPClient *http.Client
	Token      string
	SetToken   func(string)
}
type Authenticator struct {
	mu       sync.RWMutex
	baseURL  string
	client   *http.Client
	token    string
	setToken func(string)
	flight   *authFlight
}
type authFlight struct {
	done  chan struct{}
	token string
	err   error
}

func NewAuthenticator(config AuthenticatorConfig) *Authenticator {
	client := config.HTTPClient
	if client == nil {
		client = &http.Client{Timeout: 20 * time.Second}
	}
	return &Authenticator{baseURL: strings.TrimRight(config.BaseURL, "/"), client: client, token: config.Token, setToken: config.SetToken}
}
func (a *Authenticator) Token() string { a.mu.RLock(); defer a.mu.RUnlock(); return a.token }
func (a *Authenticator) SetToken(token string) {
	a.mu.Lock()
	a.token = token
	setter := a.setToken
	a.mu.Unlock()
	if setter != nil {
		setter(token)
	}
}
func (a *Authenticator) Start(ctx context.Context, userName, userUUID string) (string, error) {
	var r struct {
		SessionID string `json:"sessionId"`
	}
	if err := a.request(ctx, http.MethodPost, "/api/v1/auth/start", "", map[string]string{"userName": userName, "userUUID": userUUID}, &r); err != nil {
		return "", err
	}
	if strings.TrimSpace(r.SessionID) == "" {
		return "", errors.New("auth start returned no session")
	}
	return r.SessionID, nil
}
func (a *Authenticator) ValidateToken(ctx context.Context, sessionID, userUUID string) (string, error) {
	var r struct {
		Token string `json:"token"`
	}
	if err := a.request(ctx, http.MethodPost, "/api/v1/auth/validate", "", map[string]string{"sessionId": sessionID, "userUUID": userUUID}, &r); err != nil {
		return "", err
	}
	if strings.TrimSpace(r.Token) == "" {
		return "", errors.New("auth validate returned no token")
	}
	return r.Token, nil
}

func (a *Authenticator) Validate(ctx context.Context, sessionID, userUUID string) (string, error) {
	token, err := a.ValidateToken(ctx, sessionID, userUUID)
	if err == nil {
		a.SetToken(token)
	}
	return token, err
}

func (a *Authenticator) Authenticate(ctx context.Context, userName, userUUID string, join func(context.Context, string) error) (string, error) {
	sid, err := a.Start(ctx, userName, userUUID)
	if err != nil {
		return "", err
	}
	if join != nil {
		if err := join(ctx, sid); err != nil {
			return "", err
		}
	}
	return a.Validate(ctx, sid, userUUID)
}
func (a *Authenticator) Refresh(ctx context.Context) (bool, error) {
	token := a.Token()
	if token == "" {
		return false, errors.New("backend token is empty")
	}
	var r struct {
		Refreshed bool   `json:"refreshed"`
		Token     string `json:"token"`
	}
	if err := a.request(ctx, http.MethodPost, "/api/v1/auth/refresh", token, nil, &r); err != nil {
		return false, err
	}
	if r.Token != "" {
		a.SetToken(r.Token)
	}
	return r.Refreshed, nil
}

func (a *Authenticator) Ensure(ctx context.Context, reauth func(context.Context) (string, error)) (string, error) {
	if t := a.Token(); t != "" {
		return t, nil
	}
	a.mu.Lock()
	if a.token != "" {
		t := a.token
		a.mu.Unlock()
		return t, nil
	}
	if a.flight == nil {
		f := &authFlight{done: make(chan struct{})}
		a.flight = f
		a.mu.Unlock()
		if reauth == nil {
			f.err = errors.New("reauthentication callback is nil")
		} else {
			f.token, f.err = reauth(ctx)
		}
		if f.err == nil && f.token != "" {
			a.SetToken(f.token)
		} else if f.err == nil {
			f.err = errors.New("reauthentication returned no token")
		}
		a.mu.Lock()
		close(f.done)
		a.flight = nil
		a.mu.Unlock()
		return f.token, f.err
	}
	f := a.flight
	a.mu.Unlock()
	select {
	case <-ctx.Done():
		return "", ctx.Err()
	case <-f.done:
		return f.token, f.err
	}
}
func (a *Authenticator) request(ctx context.Context, method, path, bearer string, payload, target any) error {
	var body io.Reader
	if payload != nil {
		data, err := json.Marshal(payload)
		if err != nil {
			return err
		}
		body = bytes.NewReader(data)
	}
	req, err := http.NewRequestWithContext(ctx, method, a.baseURL+path, body)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/json")
	if bearer != "" {
		req.Header.Set("Authorization", "Bearer "+bearer)
	}
	if payload != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := a.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, maxResponseBody+1))
	if err != nil {
		return err
	}
	if len(raw) > maxResponseBody {
		return errors.New("backend response body too large")
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("backend %d: %s", resp.StatusCode, safeMessage(raw))
	}
	if target == nil || len(bytes.TrimSpace(raw)) == 0 {
		return nil
	}
	if err := json.Unmarshal(raw, target); err != nil {
		return fmt.Errorf("decode backend response: %w", err)
	}
	return nil
}
func safeMessage(raw []byte) string {
	var o map[string]any
	if json.Unmarshal(raw, &o) == nil {
		for _, k := range []string{"message", "error", "title", "code"} {
			if v, ok := o[k].(string); ok && strings.TrimSpace(v) != "" {
				return trim(v)
			}
		}
	}
	t := strings.TrimSpace(string(raw))
	if t == "" {
		return "request failed"
	}
	return trim(t)
}
func trim(v string) string {
	if len(v) > 256 {
		return v[:256]
	}
	return v
}
