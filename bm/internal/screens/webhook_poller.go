package screens

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"time"
)

const webhookPollInterval = 15 * time.Second

type WebhookReader interface {
	HasEnabledCards() bool
	ReadNotifications(context.Context) ([]PaymentNotification, error)
}

type WebhookPoller struct {
	reader        WebhookReader
	notifications *Notifications
	poller        *Poller
	mu            sync.Mutex
	inFlight      bool
}

func NewWebhookPoller(reader WebhookReader, notifications *Notifications) *WebhookPoller {
	return &WebhookPoller{reader: reader, notifications: notifications}
}

func (poller *WebhookPoller) Start(parent context.Context) {
	if poller == nil || poller.reader == nil {
		return
	}
	poller.mu.Lock()
	if poller.poller != nil {
		poller.mu.Unlock()
		return
	}
	ctx, cancel := context.WithCancel(parent)
	active := &Poller{cancel: cancel, done: make(chan struct{})}
	poller.poller = active
	poller.mu.Unlock()
	go func() {
		defer close(active.done)
		poller.poll(ctx)
		ticker := time.NewTicker(webhookPollInterval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				poller.poll(ctx)
			}
		}
	}()
}

func (poller *WebhookPoller) Stop() {
	if poller == nil {
		return
	}
	poller.mu.Lock()
	active := poller.poller
	poller.poller = nil
	poller.mu.Unlock()
	if active != nil {
		active.Stop()
	}
}

func (poller *WebhookPoller) poll(ctx context.Context) {
	if !poller.reader.HasEnabledCards() || !poller.begin() {
		return
	}
	defer poller.end()
	requestCtx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	notifications, err := poller.reader.ReadNotifications(requestCtx)
	if err != nil {
		return
	}
	for _, notification := range notifications {
		poller.notifications.ShowQueued(formatPaymentNotification(notification))
	}
}

func (poller *WebhookPoller) begin() bool {
	poller.mu.Lock()
	defer poller.mu.Unlock()
	if poller.inFlight {
		return false
	}
	poller.inFlight = true
	return true
}

func (poller *WebhookPoller) end() {
	poller.mu.Lock()
	poller.inFlight = false
	poller.mu.Unlock()
}

func formatPaymentNotification(notification PaymentNotification) string {
	sender := strings.TrimSpace(notification.SenderName)
	if sender == "" {
		sender = strings.TrimSpace(notification.SenderNumber)
	}
	message := fmt.Sprintf("Получено %d АР от %s", notification.Amount, sender)
	if comment := strings.TrimSpace(notification.Comment); comment != "" {
		message += " — " + comment
	}
	return message
}
