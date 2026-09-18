package screens

import (
	"fmt"
	"strings"

	"github.com/yawaflua/GoMinecraftBridge/sdk"
)

func (manager *Manager) mainScreen() sdk.ClientScreen {
	manager.mu.Lock()
	name, dayTime := manager.playerName, manager.dayTime
	manager.mu.Unlock()
	if name == "" {
		name = "игрок"
	}
	return sdk.ClientScreen{ID: mainScreenID, Title: "SPMega", Body: fmt.Sprintf("Доброе %s, %s", timeOfDay(dayTime), name), Buttons: []sdk.ClientScreenButton{{ID: "cards", Label: "Карта"}, {ID: "payment", Label: "Оплата"}, {ID: "scan_qr", Label: "Сканировать QR"}, {ID: "close", Label: "Закрыть", Close: true}}}
}

func (manager *Manager) cardsScreen() sdk.ClientScreen {
	cards, selected := manager.bank.Cards(), manager.bank.SelectedCardIndex()
	buttons := make([]sdk.ClientScreenButton, 0, len(cards)+6)
	for i, card := range cards {
		label := card.Title
		if i == selected {
			label = ">> " + label + " <<"
		}
		buttons = append(buttons, sdk.ClientScreenButton{ID: fmt.Sprintf("card_%d", i), Label: label})
	}
	buttons = append(buttons, sdk.ClientScreenButton{ID: "remove", Label: "Удалить"}, sdk.ClientScreenButton{ID: "refresh", Label: "Обновить"}, sdk.ClientScreenButton{ID: "add", Label: "Добавить новую"}, sdk.ClientScreenButton{ID: "history", Label: "История"}, sdk.ClientScreenButton{ID: "webhook", Label: webhookLabel(cards, selected)}, sdk.ClientScreenButton{ID: "back", Label: "Назад", Close: true})
	body := "Список карт\nДействия"
	if len(cards) == 0 {
		body += "\n\nНет карт. Добавь карту через кнопку «Добавить новую»."
	}
	return sdk.ClientScreen{ID: cardsScreenID, Title: "Управление картами", Body: body, Buttons: buttons}
}

func (manager *Manager) addCardScreen(values map[string]string) sdk.ClientScreen {
	return sdk.ClientScreen{ID: addCardScreenID, Title: "Добавление карты", Fields: []sdk.ClientScreenField{{ID: "card_id", Type: sdk.ClientScreenFieldText, Label: "Card ID (UUID)", Placeholder: "ID карты (UUID)", Value: values["card_id"], MaxLength: 64}, {ID: "card_token", Type: sdk.ClientScreenFieldPassword, Label: "Card Token", Placeholder: "Токен карты", Value: values["card_token"], MaxLength: 128}}, Buttons: []sdk.ClientScreenButton{{ID: "add", Label: "Добавить"}, {ID: "cancel", Label: "Отмена", Close: true}}}
}

func (manager *Manager) paymentScreen(values map[string]string) sdk.ClientScreen {
	manager.mu.Lock()
	initial, options, selectedRecipient := manager.initialRecipient, append([]string(nil), manager.recipientCards...), manager.selectedRecipient
	manager.mu.Unlock()
	recipient := values["recipient"]
	if recipient == "" {
		recipient = initial
	}
	selectOptions := make([]sdk.ClientScreenOption, 0, len(options))
	for _, option := range options {
		selectOptions = append(selectOptions, sdk.ClientScreenOption{Value: option, Label: option})
	}
	if values["recipient_card"] != "" {
		selectedRecipient = values["recipient_card"]
	}
	cardText := "Карта отправителя: не выбрана"
	if card, ok := manager.bank.SelectedCard(); ok {
		cardText = fmt.Sprintf("Карта отправителя: %s | Баланс: %d АР", card.Title, card.Balance)
	}
	fields := []sdk.ClientScreenField{{ID: "amount", Type: sdk.ClientScreenFieldNumber, Label: "Сумма", Placeholder: "Сумма перевода", Value: values["amount"], MaxLength: 16}, {ID: "recipient", Type: sdk.ClientScreenFieldText, Label: "Получатель", Placeholder: "Ник или 5 цифр карты", Value: recipient, MaxLength: 32}, {ID: "comment", Type: sdk.ClientScreenFieldText, Label: "Комментарий", Placeholder: "Комментарий (необязательно)", Value: values["comment"], MaxLength: 64}}
	if isNickname(recipient) {
		fields = append(fields, sdk.ClientScreenField{ID: "recipient_card", Type: sdk.ClientScreenFieldSelect, Label: "Карта игрока", Value: selectedRecipient, Options: selectOptions})
	}
	return sdk.ClientScreen{ID: paymentScreenID, Title: "Оплата", Body: "Левая колонка: ввод\nПравая колонка: действия\n\n" + cardText, Fields: fields, Buttons: []sdk.ClientScreenButton{{ID: "lookup", Label: "Найти карты игрока"}, {ID: "sender_left", Label: "< Предыдущая карта"}, {ID: "sender_right", Label: "Следующая карта >"}, {ID: "transfer", Label: "Перевести"}, {ID: "back", Label: "Назад", Close: true}}}
}

func (manager *Manager) historyScreen(card CardViewModel, page, scroll int, transactions []Transaction) sdk.ClientScreen {
	var body strings.Builder
	fmt.Fprintf(&body, "Карта: %s\nСтр. %d\n\n", card.Title, page)
	if len(transactions) == 0 {
		body.WriteString("Транзакций не найдено")
	} else {
		end := scroll + 6
		if end > len(transactions) {
			end = len(transactions)
		}
		for _, tx := range transactions[scroll:end] {
			date := strings.ReplaceAll(tx.CreatedAt, "T", " ")
			if len(date) > 19 {
				date = date[:19]
			}
			comment := ""
			if tx.Comment != "" {
				comment = " (" + tx.Comment + ")"
			}
			fmt.Fprintf(&body, "%s -> %s | %d АР%s [%s]\n", date, tx.Receiver, tx.Amount, comment, tx.Status)
		}
	}
	return sdk.ClientScreen{ID: historyScreenID, Title: "История транзакций", Body: body.String(), Buttons: []sdk.ClientScreenButton{{ID: "up", Label: "▲"}, {ID: "down", Label: "▼"}, {ID: "previous", Label: "<"}, {ID: "next", Label: ">"}, {ID: "back", Label: "Назад", Close: true}}}
}
func (manager *Manager) webhookConfirmScreen() sdk.ClientScreen {
	return sdk.ClientScreen{ID: webhookConfirmScreenID, Title: "Включить обработку вебхуков?", Body: "Если другой вебхук уже подключен к карте — он может затереться.", Buttons: []sdk.ClientScreenButton{{ID: "confirm", Label: "Включить", Close: true}, {ID: "cancel", Label: "Отмена", Close: true}}}
}
func (manager *Manager) qrScreen(generation uint64) sdk.ClientScreen {
	manager.mu.Lock()
	target := manager.qrURL
	manager.mu.Unlock()
	return sdk.ClientScreen{ID: qrConfirmScreenID, Title: "Подтверждение QR", Body: "Найдена ссылка: " + target, Buttons: []sdk.ClientScreenButton{{ID: "cancel", Label: "Отмена", Close: true}, {ID: fmt.Sprintf("open-%d", generation), Label: "Открыть ссылку", Close: true}}}
}
func webhookLabel(cards []CardViewModel, selected int) string {
	if len(cards) > 0 && selected >= 0 && selected < len(cards) && cards[selected].WebhookEnabled {
		return "Вебхуки включены"
	}
	return "Включить вебхуки"
}
