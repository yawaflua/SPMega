package screens

import (
	"context"
	"strconv"
	"strings"
	"time"

	"github.com/yawaflua/GoMinecraftBridge/sdk"
	"github.com/yawaflua/GoMinecraftBridge/sdk/client"
)

// ScreenEvent routes SDK button events to their Java-equivalent actions.
func (manager *Manager) ScreenEvent(ui *client.Context, event sdk.ClientScreenEvent) error {
	if event.Type != "button" {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()
	switch event.ScreenID {
	case mainScreenID:
		switch event.ButtonID {
		case "cards":
			ui.OpenScreen(manager.cardsScreen())
		case "payment":
			manager.OpenPayment(ui, "")
		case "scan_qr":
			if !manager.CaptureScreen() {
				manager.show(ui, "Сканирование QR недоступно")
			}
		}
	case cardsScreenID:
		return manager.handleCards(ctx, ui, event)
	case addCardScreenID:
		if event.ButtonID == "add" {
			message := manager.bank.AddCard(ctx, event.Values["card_id"], event.Values["card_token"], manager.PlayerUUID())
			manager.show(ui, message)
			if strings.HasPrefix(message, "Карта добавлена") || strings.HasPrefix(message, "Вы не владелец карты") {
				ui.CloseScreen(addCardScreenID)
			} else {
				ui.OpenScreen(manager.addCardScreen(event.Values))
			}
		}
	case paymentScreenID:
		return manager.handlePayment(ctx, ui, event)
	case historyScreenID:
		manager.handleHistory(ctx, ui, event)
	case webhookConfirmScreenID:
		if event.ButtonID == "confirm" {
			manager.show(ui, manager.bank.RegisterSelectedWebhook(ctx))
			ui.OpenScreen(manager.cardsScreen())
		}
	case qrConfirmScreenID:
		if strings.HasPrefix(event.ButtonID, "open-") {
			generation, err := strconv.ParseUint(strings.TrimPrefix(event.ButtonID, "open-"), 10, 64)
			if err != nil {
				return nil
			}
			manager.mu.Lock()
			handler, target, current := manager.openURL, manager.qrURL, manager.qrGeneration
			manager.mu.Unlock()
			if generation != current {
				manager.show(ui, "QR-код устарел, отсканируйте ещё раз")
			} else if handler == nil {
				manager.show(ui, "Открытие ссылок недоступно")
			} else if err := handler(target); err != nil {
				manager.show(ui, "Не удалось открыть ссылку: "+err.Error())
			}
		}
	}
	return nil
}

func (manager *Manager) handleCards(ctx context.Context, ui *client.Context, event sdk.ClientScreenEvent) error {
	switch {
	case strings.HasPrefix(event.ButtonID, "card_"):
		index, err := strconv.Atoi(strings.TrimPrefix(event.ButtonID, "card_"))
		if err != nil {
			return err
		}
		manager.bank.SelectCard(index)
		if card, ok := manager.bank.SelectedCard(); ok {
			manager.show(ui, "Выбрана карта "+card.Title)
		}
		ui.OpenScreen(manager.cardsScreen())
	case event.ButtonID == "remove":
		manager.show(ui, manager.bank.RemoveSelectedCard(ctx))
		ui.OpenScreen(manager.cardsScreen())
	case event.ButtonID == "refresh":
		manager.show(ui, manager.bank.RefreshSelectedCard(ctx, manager.PlayerUUID()))
		ui.OpenScreen(manager.cardsScreen())
	case event.ButtonID == "add":
		ui.OpenScreen(manager.addCardScreen(nil))
	case event.ButtonID == "history":
		card, ok := manager.bank.SelectedCard()
		if !ok {
			manager.show(ui, "Сначала выбери или добавь карту")
			return nil
		}
		manager.mu.Lock()
		manager.historyCard, manager.historyPage, manager.historyScroll = card, 1, 0
		manager.mu.Unlock()
		manager.openHistory(ctx, ui)
	case event.ButtonID == "webhook":
		card, ok := manager.bank.SelectedCard()
		if !ok {
			manager.show(ui, "Сначала выбери или добавь карту")
		} else if card.WebhookEnabled {
			manager.show(ui, "Вебхуки включены")
		} else {
			ui.OpenScreen(manager.webhookConfirmScreen())
		}
	}
	return nil
}

func (manager *Manager) handlePayment(ctx context.Context, ui *client.Context, event sdk.ClientScreenEvent) error {
	values := event.Values
	switch event.ButtonID {
	case "sender_left":
		manager.bank.CycleSelectedCard(-1)
		ui.OpenScreen(manager.paymentScreen(values))
	case "sender_right":
		manager.bank.CycleSelectedCard(1)
		ui.OpenScreen(manager.paymentScreen(values))
	case "lookup":
		recipient := strings.TrimSpace(values["recipient"])
		if !isNickname(recipient) {
			manager.show(ui, "Укажи ник игрока для поиска карт")
			return nil
		}
		cards, err := manager.bank.RecipientCards(ctx, recipient)
		if err != nil {
			manager.show(ui, "Не удалось получить карты игрока: "+err.Error())
			return nil
		}
		manager.mu.Lock()
		manager.recipientCards, manager.selectedRecipient = cards, ""
		if len(cards) > 0 {
			manager.selectedRecipient = cards[0]
		}
		manager.mu.Unlock()
		if len(cards) == 0 {
			manager.show(ui, "У игрока нет доступных карт")
		}
		ui.OpenScreen(manager.paymentScreen(values))
	case "transfer":
		return manager.submitPayment(ctx, ui, values)
	}
	return nil
}

func (manager *Manager) submitPayment(ctx context.Context, ui *client.Context, values map[string]string) error {
	card, ok := manager.bank.SelectedCard()
	if !ok {
		manager.show(ui, "Нет выбранной карты отправителя")
		return nil
	}
	amount, err := strconv.ParseInt(strings.TrimSpace(values["amount"]), 10, 64)
	if err != nil {
		manager.show(ui, "Некорректная сумма")
		return nil
	}
	if amount <= 0 {
		manager.show(ui, "Сумма должна быть больше 0")
		return nil
	}
	if amount > int64(^uint32(0)>>1) {
		manager.show(ui, "Сумма слишком большая")
		return nil
	}
	recipientInput := strings.TrimSpace(values["recipient"])
	if !isValidRecipient(recipientInput) {
		manager.show(ui, "Укажи ник или 5 цифр карты")
		return nil
	}
	receiver := recipientInput
	if isNickname(recipientInput) {
		receiver = strings.TrimSpace(values["recipient_card"])
		if receiver == "" {
			manager.show(ui, "Выбери карту получателя из списка")
			return nil
		}
		receiver = extractCardNumber(receiver)
	}
	manager.show(ui, "Отправка перевода...")
	draft := PaymentDraft{SenderCardID: card.ID, Recipient: receiver, Amount: amount, Comment: strings.TrimSpace(values["comment"])}
	started := time.Now()
	success, message := manager.bank.SubmitPayment(ctx, draft)
	manager.mu.Lock()
	recorder := manager.recordPayment
	manager.mu.Unlock()
	if recorder != nil {
		recorder(success, draft, card, time.Since(started), message)
	}
	manager.show(ui, message)
	ui.OpenScreen(manager.paymentScreen(values))
	return nil
}

func (manager *Manager) handleHistory(ctx context.Context, ui *client.Context, event sdk.ClientScreenEvent) {
	manager.mu.Lock()
	switch event.ButtonID {
	case "previous":
		if manager.historyPage > 1 {
			manager.historyPage--
			manager.historyScroll = 0
		}
	case "next":
		manager.historyPage++
		manager.historyScroll = 0
	case "up":
		if manager.historyScroll > 0 {
			manager.historyScroll--
		}
	case "down":
		manager.historyScroll++
	}
	manager.mu.Unlock()
	manager.openHistory(ctx, ui)
}
func (manager *Manager) openHistory(ctx context.Context, ui *client.Context) {
	manager.mu.Lock()
	card, page, scroll := manager.historyCard, manager.historyPage, manager.historyScroll
	manager.mu.Unlock()
	transactions, err := manager.bank.Transactions(ctx, card.ID, page)
	if err != nil {
		manager.show(ui, "Ошибка загрузки транзакций: "+err.Error())
		transactions = nil
	}
	maxScroll := len(transactions) - 6
	if maxScroll < 0 {
		maxScroll = 0
	}
	if scroll > maxScroll {
		scroll = maxScroll
		manager.mu.Lock()
		manager.historyScroll = scroll
		manager.mu.Unlock()
	}
	ui.OpenScreen(manager.historyScreen(card, page, scroll, transactions))
}
func (manager *Manager) show(ui *client.Context, message string) {
	message = extractMessage(message)
	if message == "" {
		return
	}
	manager.notifications.Show(message)
	ui.DisplayMessage(message)
}
