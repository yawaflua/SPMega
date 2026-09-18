package screens

import (
	"sync"

	"github.com/yawaflua/GoMinecraftBridge/sdk"
	"github.com/yawaflua/GoMinecraftBridge/sdk/client"
)

const notificationDurationTicks = 70

type Notifications struct {
	mu        sync.Mutex
	current   string
	queued    []string
	remaining int
	anchor    sdk.HUDAnchor
}

func NewNotifications() *Notifications { return &Notifications{anchor: sdk.HUDBottomRight} }
func (notifications *Notifications) SetAnchor(anchor sdk.HUDAnchor) {
	notifications.mu.Lock()
	notifications.anchor = anchor
	notifications.mu.Unlock()
}
func (notifications *Notifications) Show(message string) {
	message = extractMessage(message)
	if message == "" {
		return
	}
	notifications.mu.Lock()
	notifications.current = message
	notifications.remaining = notificationDurationTicks
	notifications.mu.Unlock()
}
func (notifications *Notifications) ShowQueued(message string) {
	message = extractMessage(message)
	if message == "" {
		return
	}
	notifications.mu.Lock()
	defer notifications.mu.Unlock()
	if notifications.current == "" || notifications.remaining <= 0 {
		notifications.current = message
		notifications.remaining = notificationDurationTicks
	} else {
		notifications.queued = append(notifications.queued, message)
	}
}
func (notifications *Notifications) Tick(context *client.Context) {
	notifications.mu.Lock()
	if notifications.remaining > 0 {
		notifications.remaining--
	}
	if notifications.remaining <= 0 && notifications.current != "" {
		if len(notifications.queued) > 0 {
			notifications.current = notifications.queued[0]
			notifications.queued = notifications.queued[1:]
			notifications.remaining = notificationDurationTicks
		} else {
			notifications.current = ""
		}
	}
	message, remaining, anchor := notifications.current, notifications.remaining, notifications.anchor
	notifications.mu.Unlock()
	if message == "" || remaining <= 0 {
		context.RemoveHUD("spmega-notification-bg")
		context.RemoveHUD("spmega-notification-text")
		return
	}
	width := len([]rune(message))*6 + 12
	context.RenderHUD(sdk.HUDRectangle(0, 0, width, 21, 0xff555555, anchor).Named("spmega-notification-bg"))
	context.RenderHUD(sdk.HUDText(message, 6, 6, 0xffffffff, true, anchor).Named("spmega-notification-text"))
}
