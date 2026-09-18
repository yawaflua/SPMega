package main

import (
	"context"
	"fmt"
	"sync"
	"time"

	"git.yawaflua.tech/yawaflua/SPMega/bm/internal/backend"
	"git.yawaflua.tech/yawaflua/SPMega/bm/internal/qr"
	"git.yawaflua.tech/yawaflua/SPMega/bm/internal/screens"
	"git.yawaflua.tech/yawaflua/SPMega/bm/internal/telemetry"
	"github.com/yawaflua/GoMinecraftBridge/sdk"
	"github.com/yawaflua/GoMinecraftBridge/sdk/client"
)

const pluginVersion = "v0.6-go-pre-release"

type Config struct {
	ApiDomain                  string
	ApiToken                   string
	AllowBackend               bool
	SignQuickPayEnabled        bool
	GpsEnabled                 bool
	GpsHudPosition             sdk.HUDAnchor
	NotificationPosition       sdk.HUDAnchor
	TelemetryIntervalSeconds   int
	TelemetryCollectSystemInfo bool
}

var config = &Config{
	ApiDomain:                  "https://spmega.yawaflua.tech",
	ApiToken:                   "-",
	AllowBackend:               true,
	SignQuickPayEnabled:        true,
	GpsEnabled:                 true,
	GpsHudPosition:             sdk.HUDTopCenter,
	NotificationPosition:       sdk.HUDBottomRight,
	TelemetryIntervalSeconds:   60,
	TelemetryCollectSystemInfo: true,
}

type pendingAuth struct {
	sessionID  string
	playerUUID string
	generation uint64
}

type Plugin struct {
	screens    *screens.Manager
	bank       *screens.LocalBankService
	gps        *screens.GPSHUD
	auth       *backend.Authenticator
	webhooks   *screens.WebhookPoller
	cityPoller *screens.Poller
	telemetry  *telemetry.Sender
	lifecycle  context.Context
	cancel     context.CancelFunc

	mu                sync.Mutex
	connectedUUID     string
	joinActions       map[string]pendingAuth
	authenticating    bool
	lastRefresh       time.Time
	qrDecoding        bool
	authGeneration    uint64
	fpsSamples        int
	fpsTotal          int64
	fpsMin, fpsMax    int
	lastPerformanceAt time.Time
}

func (p *Plugin) ConfigUpdated(_ *client.Context, _ sdk.ConfigUpdateEvent) error {
	if p.auth != nil {
		p.auth.SetToken(usableToken(config.ApiToken))
	}
	if p.telemetry != nil {
		p.telemetry.SetToken(usableToken(config.ApiToken))
	}
	if p.gps != nil {
		p.gps.SetEnabled(config.GpsEnabled)
		p.gps.SetAnchor(config.GpsHudPosition)
	}
	if p.screens != nil {
		p.screens.Notifications().SetAnchor(config.NotificationPosition)
	}
	return nil
}

func (p *Plugin) Tick(ui *client.Context, event sdk.ClientTickEvent) error {
	if p.screens != nil {
		p.screens.Drain(ui)
		p.screens.SetDayTime(event.DayTime)
		if event.Connected {
			p.screens.UpdatePlayer(event.PlayerName, event.PlayerUUID)
			p.screens.Notifications().Tick(ui)
			p.ensureAuthentication(ui, event.PlayerName, event.PlayerUUID)
		} else {
			p.resetAuthentication()
		}
	}
	if p.gps != nil {
		if event.Connected && event.HasPosition {
			p.gps.UpdatePosition(event.Dimension, event.X, event.Z)
		} else {
			p.gps.ClearPosition()
		}
		p.gps.Render(ui)
	}
	p.samplePerformance(event.FPS)
	return nil
}

func (p *Plugin) ClientChat(_ *client.Context, event sdk.ClientChatEvent) error {
	cardID, cardToken, ok := screens.ParseChatCard(event.Message, event.ClickValues)
	if !ok || p.bank == nil || p.screens == nil {
		return nil
	}
	p.mu.Lock()
	playerUUID := p.connectedUUID
	p.mu.Unlock()
	go func() {
		ctx, cancel := context.WithTimeout(p.lifecycle, 25*time.Second)
		defer cancel()
		message := p.bank.AddCard(ctx, cardID, cardToken, playerUUID)
		p.screens.Enqueue(func(ui *client.Context) { p.screens.Show(ui, message) })
	}()
	return nil
}

func (p *Plugin) Interaction(ui *client.Context, event sdk.InteractionEvent) error {
	if p.gps != nil {
		p.gps.UpdatePosition(event.Player.Dimension, event.Player.X, event.Player.Z)
	}
	if p.screens == nil {
		return nil
	}
	if config.SignQuickPayEnabled && event.Sneaking {
		if number := screens.ExtractQuickPayCardNumber(event.TargetTexts); number != "" {
			p.screens.OpenPayment(ui, number)
			return nil
		}
	}
	if event.Sneaking && event.Sprinting && event.Target != nil && event.Target.Player && event.Target.UUID != event.Player.UUID {
		p.screens.OpenPayment(ui, event.Target.Name)
	}
	return nil
}

func (p *Plugin) ScreenCaptured(_ *client.Context, capture sdk.ClientScreenCapture) error {
	if capture.Format != sdk.ClientPixelFormatRGBA8 {
		return fmt.Errorf("unsupported screen capture format %q", capture.Format)
	}
	p.mu.Lock()
	if p.qrDecoding {
		p.mu.Unlock()
		return nil
	}
	p.qrDecoding = true
	p.mu.Unlock()
	started := time.Now()
	pixels := append([]byte(nil), capture.Pixels...)
	go func() {
		defer func() {
			p.mu.Lock()
			p.qrDecoding = false
			p.mu.Unlock()
		}()
		value, err := qr.Decode(qr.RGBA8Image{Width: capture.Width, Height: capture.Height, Stride: capture.Stride, Pixels: pixels})
		duration := time.Since(started)
		if err == nil {
			err = qr.ValidateURL(value)
		}
		telemetry.RecordQR(nil, err == nil, duration > 50*time.Millisecond, value, errorText(err), duration.Milliseconds())
		if p.screens == nil {
			return
		}
		p.screens.Enqueue(func(ui *client.Context) {
			if err != nil {
				p.screens.Show(ui, "QR-код не найден")
				return
			}
			p.screens.OpenQRConfirmation(ui, value)
		})
	}()
	return nil
}

func (p *Plugin) ActionResult(_ *client.Context, result sdk.ActionResult) error {
	if result.Type != string(sdk.CapabilityClientSessionJoin) {
		return nil
	}
	p.mu.Lock()
	pending, ok := p.joinActions[result.ID]
	delete(p.joinActions, result.ID)
	currentGeneration, currentUUID := p.authGeneration, p.connectedUUID
	p.mu.Unlock()
	if !ok || pending.generation != currentGeneration || pending.playerUUID != currentUUID {
		return nil
	}
	if !result.Success {
		p.finishAuth("Ошибка авторизации: " + result.Error)
		return nil
	}
	go func() {
		ctx, cancel := context.WithTimeout(p.lifecycle, 25*time.Second)
		defer cancel()
		token, err := p.auth.ValidateToken(ctx, pending.sessionID, pending.playerUUID)
		p.mu.Lock()
		stale := pending.generation != p.authGeneration || pending.playerUUID != p.connectedUUID
		if !stale && err == nil {
			p.auth.SetToken(token)
		}
		p.mu.Unlock()
		if stale {
			return
		}
		if err == nil && p.bank != nil {
			err = p.bank.SyncBackendCards(ctx, pending.playerUUID)
		}
		if err != nil {
			p.finishAuth("Ошибка авторизации backend: " + err.Error())
			return
		}
		p.finishAuth("")
	}()
	return nil
}

func (p *Plugin) ScreenEvent(ui *client.Context, event sdk.ClientScreenEvent) error {
	if p.screens == nil {
		return nil
	}
	return p.screens.ScreenEvent(ui, event)
}

func (p *Plugin) Init(ui *client.Context, event sdk.InitEvent) error {
	started := time.Now()
	p.lifecycle, p.cancel = context.WithCancel(context.Background())
	p.joinActions = make(map[string]pendingAuth)

	bank, err := screens.NewLocalBankService(event.DataDirectory, screens.ServiceConfig{
		BackendURL: config.ApiDomain,
		BackendToken: func() string {
			if p.auth != nil {
				return p.auth.Token()
			}
			return usableToken(config.ApiToken)
		},
		AllowBackend: func() bool { return config.AllowBackend },
	})
	if err != nil {
		return fmt.Errorf("initialize bank UI: %w", err)
	}
	p.bank = bank
	p.screens = screens.NewManager(bank)
	p.screens.Notifications().SetAnchor(config.NotificationPosition)
	p.screens.SetCaptureHandler(func() { ui.CaptureScreen() })
	p.screens.SetURLHandler(func(target string) error { ui.OpenBrowser(target); return nil })
	p.screens.SetPaymentRecorder(func(success bool, draft screens.PaymentDraft, sender screens.CardViewModel, duration time.Duration, message string) {
		mode := "direct"
		if p.bank != nil && config.AllowBackend && p.auth != nil && p.auth.Token() != "" {
			mode = "backend"
		}
		last4 := sender.Number
		if len(last4) > 4 {
			last4 = last4[len(last4)-4:]
		}
		errorMessage := ""
		if !success {
			errorMessage = message
		}
		telemetry.RecordPayment(nil, success, mode, "card", duration.Milliseconds(), last4, draft.Amount, draft.Recipient, errorMessage)
	})
	p.gps = screens.NewGPSHUD(config.GpsHudPosition)
	p.gps.SetEnabled(config.GpsEnabled)
	p.auth = backend.NewAuthenticator(backend.AuthenticatorConfig{BaseURL: config.ApiDomain, Token: usableToken(config.ApiToken), SetToken: func(token string) {
		config.ApiToken = token
		if p.telemetry != nil {
			p.telemetry.SetToken(token)
		}
		if p.screens != nil {
			p.screens.Enqueue(func(ui *client.Context) { ui.SaveConfig(config) })
		}
	}})
	p.webhooks = screens.NewWebhookPoller(bank, p.screens.Notifications())
	p.webhooks.Start(p.lifecycle)

	interval := time.Duration(config.TelemetryIntervalSeconds) * time.Second
	p.telemetry = telemetry.NewSender(telemetry.Config{BackendURL: config.ApiDomain, Token: config.ApiToken, ClientVersion: pluginVersion, Interval: interval, CollectSystemInfo: config.TelemetryCollectSystemInfo, Collector: telemetry.DefaultCollector()})
	_ = p.telemetry.Start()
	telemetry.RecordLifecycle(nil, "client_init", time.Since(started).Milliseconds())
	p.cityPoller = screens.StartPoller(p.lifecycle, 5*time.Hour, func(parent context.Context) {
		ctx, cancel := context.WithTimeout(parent, 20*time.Second)
		defer cancel()
		_ = p.gps.FetchCities(ctx)
	})
	return nil
}

func (p *Plugin) Deinit(_ *client.Context, _ sdk.DeinitEvent) error {
	telemetry.RecordLifecycle(nil, "client_deinit", 0)
	if p.webhooks != nil {
		p.webhooks.Stop()
	}
	if p.cityPoller != nil {
		p.cityPoller.Stop()
	}
	if p.cancel != nil {
		p.cancel()
	}
	if p.telemetry != nil {
		_ = p.telemetry.Flush()
		return p.telemetry.Stop()
	}
	return nil
}

func (p *Plugin) samplePerformance(fps int) {
	if fps <= 0 {
		return
	}
	p.mu.Lock()
	if p.lastPerformanceAt.IsZero() {
		p.lastPerformanceAt = time.Now()
		p.fpsMin = fps
	}
	p.fpsSamples++
	p.fpsTotal += int64(fps)
	if fps < p.fpsMin {
		p.fpsMin = fps
	}
	if fps > p.fpsMax {
		p.fpsMax = fps
	}
	period := time.Since(p.lastPerformanceAt)
	if period < time.Minute {
		p.mu.Unlock()
		return
	}
	count, total, minimum, maximum := p.fpsSamples, p.fpsTotal, p.fpsMin, p.fpsMax
	p.fpsSamples, p.fpsTotal, p.fpsMin, p.fpsMax = 0, 0, 0, 0
	p.lastPerformanceAt = time.Now()
	p.mu.Unlock()
	telemetry.RecordPerformance(nil, int(total/int64(count)), minimum, maximum, count, period.Milliseconds(), 0, 0, 0)
}

func (p *Plugin) resetAuthentication() {
	p.mu.Lock()
	changed := p.connectedUUID != ""
	if changed {
		p.authGeneration++
	}
	p.connectedUUID = ""
	p.authenticating = false
	p.joinActions = make(map[string]pendingAuth)
	p.lastRefresh = time.Time{}
	p.mu.Unlock()
	if changed && p.auth != nil {
		p.auth.SetToken("")
	}
}

func (p *Plugin) ensureAuthentication(ui *client.Context, playerName, playerUUID string) {
	if !config.AllowBackend || p.auth == nil || playerUUID == "" {
		return
	}
	p.mu.Lock()
	changedPlayer := p.connectedUUID != playerUUID
	if changedPlayer {
		p.authGeneration++
		p.connectedUUID = playerUUID
		p.authenticating = false
		p.joinActions = make(map[string]pendingAuth)
		p.lastRefresh = time.Time{}
	}
	generation := p.authGeneration
	p.mu.Unlock()
	if changedPlayer {
		p.auth.SetToken("")
	}
	p.mu.Lock()
	if p.authenticating {
		p.mu.Unlock()
		return
	}
	if p.auth.Token() != "" {
		if time.Since(p.lastRefresh) < time.Hour {
			p.mu.Unlock()
			return
		}
		p.lastRefresh = time.Now()
		p.mu.Unlock()
		go func() {
			ctx, cancel := context.WithTimeout(p.lifecycle, 20*time.Second)
			defer cancel()
			if _, err := p.auth.Refresh(ctx); err != nil {
				p.auth.SetToken("")
			}
		}()
		return
	}
	p.authenticating = true
	p.mu.Unlock()
	go func() {
		ctx, cancel := context.WithTimeout(p.lifecycle, 20*time.Second)
		defer cancel()
		sessionID, err := p.auth.Start(ctx, playerName, playerUUID)
		if err != nil {
			p.finishAuth("Ошибка запуска авторизации: " + err.Error())
			return
		}
		p.screens.Enqueue(func(ui *client.Context) {
			p.mu.Lock()
			if generation != p.authGeneration || playerUUID != p.connectedUUID {
				p.mu.Unlock()
				return
			}
			p.mu.Unlock()
			actionID := ui.JoinSession(sessionID)
			p.mu.Lock()
			p.joinActions[actionID] = pendingAuth{sessionID: sessionID, playerUUID: playerUUID, generation: generation}
			p.mu.Unlock()
		})
	}()
}

func (p *Plugin) finishAuth(message string) {
	p.mu.Lock()
	p.authenticating = false
	p.mu.Unlock()
	if message != "" && p.screens != nil {
		p.screens.Enqueue(func(ui *client.Context) { p.screens.Show(ui, message) })
	}
}

func (p *Plugin) OpenMain(ui *client.Context) {
	if p.screens != nil {
		p.screens.OpenMain(ui)
	}
}

func (p *Plugin) KeyPressed(ui *client.Context, event sdk.ClientKeyEvent) error {
	switch event.ID {
	case "open-screen":
		p.OpenMain(ui)
	case "scan-qr":
		ui.CaptureScreen()
	case "toggle-gps":
		if p.gps != nil {
			p.gps.Toggle()
			config.GpsEnabled = p.gps.Enabled()
			ui.SaveConfig(config)
			p.screens.Show(ui, map[bool]string{true: "GPS Ада включен", false: "GPS Ада выключен"}[config.GpsEnabled])
		}
	}
	return nil
}

func init() {
	client.Register(sdk.Metadata{
		ID:           "spmega",
		Name:         "SPMega",
		Version:      pluginVersion,
		Description:  "Go implementation of SPMega",
		Authors:      []string{"yawaflua"},
		Website:      "https://github.com/yawaflua",
		License:      "CC BY-NC-ND 4.0",
		ConfigSchema: config,
		ConfigEnums: map[string][]any{
			"GpsHudPosition":       {sdk.HUDTopLeft, sdk.HUDTopCenter, sdk.HUDTopRight, sdk.HUDBottomLeft, sdk.HUDBottomCenter, sdk.HUDBottomRight},
			"NotificationPosition": {sdk.HUDTopLeft, sdk.HUDTopCenter, sdk.HUDTopRight, sdk.HUDBottomLeft, sdk.HUDBottomCenter, sdk.HUDBottomRight},
		},
		ConfigWritable: true,
		Environment:    sdk.PluginEnvironmentClient,
		ClientKeyBindings: []sdk.ClientKeyBinding{
			{ID: "open-screen", Name: "Open SPMega", DefaultKey: "key.keyboard.p"},
			{ID: "scan-qr", Name: "Scan QR", DefaultKey: "key.keyboard.o"},
			{ID: "toggle-gps", Name: "Toggle GPS", DefaultKey: "key.keyboard.j"},
		},
	}, &Plugin{})
}

func usableToken(token string) string {
	if token == "-" {
		return ""
	}
	return token
}

func errorText(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}

func main() {}
