package screens

import (
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/yawaflua/GoMinecraftBridge/sdk/client"
)

const (
	mainScreenID           = "spmega_main"
	cardsScreenID          = "spmega_cards"
	addCardScreenID        = "spmega_add_card"
	paymentScreenID        = "spmega_payment"
	historyScreenID        = "spmega_history"
	webhookConfirmScreenID = "spmega_webhook_confirm"
	qrConfirmScreenID      = "spmega_qr_confirm"
)

var (
	cardNumberPattern       = regexp.MustCompile(`^\d{5}$`)
	cardNumberSearchPattern = regexp.MustCompile(`\d{5}`)
	nicknamePattern         = regexp.MustCompile(`^[A-Za-z0-9_]{3,16}$`)
	digitsPattern           = regexp.MustCompile(`^\d+$`)
)

type CaptureHandler func()
type URLHandler func(string) error
type PaymentRecorder func(success bool, draft PaymentDraft, sender CardViewModel, duration time.Duration, message string)

type Manager struct {
	mu                sync.Mutex
	bank              BankService
	playerName        string
	playerUUID        string
	dayTime           int64
	initialRecipient  string
	recipientCards    []string
	selectedRecipient string
	historyCard       CardViewModel
	historyPage       int
	historyScroll     int
	qrURL             string
	qrGeneration      uint64
	captureScreen     CaptureHandler
	openURL           URLHandler
	recordPayment     PaymentRecorder
	notifications     *Notifications
	pending           []func(*client.Context)
}

func NewManager(bank BankService) *Manager {
	return &Manager{bank: bank, historyPage: 1, notifications: NewNotifications()}
}
func (manager *Manager) SetCaptureHandler(handler CaptureHandler) {
	manager.mu.Lock()
	manager.captureScreen = handler
	manager.mu.Unlock()
}
func (manager *Manager) CaptureScreen() bool {
	manager.mu.Lock()
	handler := manager.captureScreen
	manager.mu.Unlock()
	if handler == nil {
		return false
	}
	handler()
	return true
}
func (manager *Manager) SetURLHandler(handler URLHandler) {
	manager.mu.Lock()
	manager.openURL = handler
	manager.mu.Unlock()
}
func (manager *Manager) SetPaymentRecorder(recorder PaymentRecorder) {
	manager.mu.Lock()
	manager.recordPayment = recorder
	manager.mu.Unlock()
}
func (manager *Manager) SetDayTime(dayTime int64) {
	manager.mu.Lock()
	manager.dayTime = dayTime
	manager.mu.Unlock()
}
func (manager *Manager) Notifications() *Notifications { return manager.notifications }
func (manager *Manager) Enqueue(callback func(*client.Context)) {
	if callback == nil {
		return
	}
	manager.mu.Lock()
	manager.pending = append(manager.pending, callback)
	manager.mu.Unlock()
}
func (manager *Manager) Drain(context *client.Context) {
	manager.mu.Lock()
	pending := manager.pending
	manager.pending = nil
	manager.mu.Unlock()
	for _, callback := range pending {
		callback(context)
	}
}
func (manager *Manager) Show(context *client.Context, message string) { manager.show(context, message) }
func (manager *Manager) UpdatePlayer(name, uuid string) {
	manager.mu.Lock()
	manager.playerName, manager.playerUUID = name, uuid
	manager.mu.Unlock()
}

func (manager *Manager) PlayerUUID() string {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	return manager.playerUUID
}
func (manager *Manager) OpenMain(context *client.Context) { context.OpenScreen(manager.mainScreen()) }
func (manager *Manager) OpenPayment(context *client.Context, recipient string) {
	manager.mu.Lock()
	manager.initialRecipient = strings.TrimSpace(recipient)
	manager.recipientCards = nil
	manager.selectedRecipient = ""
	manager.mu.Unlock()
	context.OpenScreen(manager.paymentScreen(nil))
}
func (manager *Manager) OpenQRConfirmation(context *client.Context, targetURL string) {
	manager.mu.Lock()
	manager.qrURL = targetURL
	manager.qrGeneration++
	generation := manager.qrGeneration
	manager.mu.Unlock()
	context.OpenScreen(manager.qrScreen(generation))
}

func isNickname(value string) bool {
	return nicknamePattern.MatchString(value) && !digitsPattern.MatchString(value)
}
func isValidRecipient(value string) bool {
	return cardNumberPattern.MatchString(value) || nicknamePattern.MatchString(value)
}
func extractCardNumber(value string) string {
	if match := cardNumberSearchPattern.FindString(value); match != "" {
		return match
	}
	return value
}
func ExtractQuickPayCardNumber(texts []string) string {
	return extractQuickPayCardNumberFromTexts(texts)
}
func timeOfDay(dayTime int64) string {
	dayTime = ((dayTime % 24000) + 24000) % 24000
	if dayTime < 6000 {
		return "утро"
	}
	if dayTime < 12000 {
		return "день"
	}
	if dayTime < 18000 {
		return "вечер"
	}
	return "ночь"
}
