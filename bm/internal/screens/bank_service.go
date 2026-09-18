package screens

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const defaultSPWorldsURL = "https://spworlds.ru"

type ServiceConfig struct {
	SPWorldsURL  string
	BackendURL   string
	BackendToken func() string
	AllowBackend func() bool
	HTTPClient   *http.Client
}

type persistedState struct {
	Cards        []CardViewModel     `json:"cards"`
	Transactions []storedTransaction `json:"transactions"`
	Selected     int                 `json:"selected"`
}

type storedTransaction struct {
	CardID string `json:"cardId"`
	Transaction
}

type LocalBankService struct {
	mu           sync.RWMutex
	path         string
	spworldsURL  string
	backendURL   string
	backendToken func() string
	allowBackend func() bool
	http         *http.Client
	cards        []CardViewModel
	transactions []storedTransaction
	selected     int
}

func NewLocalBankService(dataDirectory string, config ServiceConfig) (*LocalBankService, error) {
	if config.SPWorldsURL == "" {
		config.SPWorldsURL = defaultSPWorldsURL
	}
	if config.HTTPClient == nil {
		config.HTTPClient = &http.Client{Timeout: 20 * time.Second}
	}
	if config.BackendToken == nil {
		config.BackendToken = func() string { return "" }
	}
	if config.AllowBackend == nil {
		config.AllowBackend = func() bool { return false }
	}
	service := &LocalBankService{
		path:         filepath.Join(dataDirectory, "spmega-bank.json"),
		spworldsURL:  strings.TrimRight(config.SPWorldsURL, "/"),
		backendURL:   strings.TrimRight(config.BackendURL, "/"),
		backendToken: config.BackendToken,
		allowBackend: config.AllowBackend,
		http:         config.HTTPClient,
	}
	if err := service.load(); err != nil {
		return nil, err
	}
	return service, nil
}

func (s *LocalBankService) load() error {
	data, err := os.ReadFile(s.path)
	if errors.Is(err, os.ErrNotExist) {
		s.cards, s.transactions, s.selected = nil, nil, 0
		return nil
	}
	if err != nil {
		return fmt.Errorf("read bank state: %w", err)
	}
	var state persistedState
	if err := json.Unmarshal(data, &state); err != nil {
		return fmt.Errorf("decode bank state: %w", err)
	}
	s.cards, s.transactions, s.selected = state.Cards, state.Transactions, state.Selected
	s.normalizeSelectedLocked()
	return nil
}

func (s *LocalBankService) saveLocked() error {
	if err := os.MkdirAll(filepath.Dir(s.path), 0o755); err != nil {
		return err
	}
	data, err := json.MarshalIndent(persistedState{Cards: s.cards, Transactions: s.transactions, Selected: s.selected}, "", "  ")
	if err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(s.path), ".spmega-bank-*.tmp")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName)
	if err := tmp.Chmod(0o600); err != nil {
		tmp.Close()
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmpName, s.path); err != nil {
		return err
	}
	dir, err := os.Open(filepath.Dir(s.path))
	if err != nil {
		return err
	}
	defer dir.Close()
	return dir.Sync()
}

func (s *LocalBankService) Cards() []CardViewModel {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return append([]CardViewModel(nil), s.cards...)
}
func (s *LocalBankService) SelectedCardIndex() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.normalizeSelectedLocked()
	return s.selected
}
func (s *LocalBankService) SelectedCard() (CardViewModel, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.normalizeSelectedLocked()
	if len(s.cards) == 0 {
		return CardViewModel{}, false
	}
	return s.cards[s.selected], true
}
func (s *LocalBankService) SelectCard(index int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.cards) == 0 {
		s.selected = 0
		return
	}
	s.selected = floorMod(index, len(s.cards))
}
func (s *LocalBankService) CycleSelectedCard(direction int) (CardViewModel, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.cards) == 0 {
		return CardViewModel{}, false
	}
	s.selected = floorMod(s.selected+direction, len(s.cards))
	return s.cards[s.selected], true
}
func (s *LocalBankService) normalizeSelectedLocked() {
	if len(s.cards) == 0 {
		s.selected = 0
	} else {
		s.selected = floorMod(s.selected, len(s.cards))
	}
}
func floorMod(value, modulus int) int {
	result := value % modulus
	if result < 0 {
		result += modulus
	}
	return result
}

func (s *LocalBankService) AddCard(ctx context.Context, cardID, cardToken, playerUUID string) string {
	cardID, cardToken = strings.TrimSpace(cardID), strings.TrimSpace(cardToken)
	if cardID == "" || cardToken == "" {
		return "Укажи cardId и cardToken"
	}
	if !validUUID(cardID) {
		return "cardId должен быть UUID"
	}
	card, message, err := s.fetchCard(ctx, cardID, cardToken, playerUUID, true)
	if err != nil {
		return "Не удалось обновить карту: " + err.Error()
	}
	s.mu.Lock()
	updated := false
	for i := range s.cards {
		if s.cards[i].ID == cardID {
			card.WebhookEnabled = s.cards[i].WebhookEnabled
			s.cards[i] = card
			updated = true
			break
		}
	}
	if !updated {
		s.cards = append([]CardViewModel{card}, s.cards...)
	}
	err = s.saveLocked()
	s.mu.Unlock()
	if err != nil {
		return "Не удалось сохранить карту: " + err.Error()
	}
	if s.backendReady() {
		if backendErr := s.sendCardToBackend(ctx, cardID, cardToken); backendErr != nil {
			return "Не удалось синхронизировать карту с backend: " + backendErr.Error()
		}
	}
	if message != "" {
		return message
	}
	return "Карта добавлена"
}

func validUUID(value string) bool {
	parts := strings.Split(value, "-")
	if len(parts) != 5 || len(parts[0]) != 8 || len(parts[1]) != 4 || len(parts[2]) != 4 || len(parts[3]) != 4 || len(parts[4]) != 12 {
		return false
	}
	for _, part := range parts {
		for _, char := range part {
			if !strings.ContainsRune("0123456789abcdefABCDEF", char) {
				return false
			}
		}
	}
	return true
}

func (s *LocalBankService) RemoveSelectedCard(ctx context.Context) string {
	card, ok := s.SelectedCard()
	if !ok {
		return "Нет карты для удаления"
	}
	s.mu.Lock()
	for i := range s.cards {
		if s.cards[i].ID == card.ID {
			s.cards = append(s.cards[:i], s.cards[i+1:]...)
			break
		}
	}
	s.normalizeSelectedLocked()
	err := s.saveLocked()
	s.mu.Unlock()
	if err != nil {
		return "Не удалось удалить карту: " + err.Error()
	}
	if s.backendReady() {
		if backendErr := s.backendRequest(ctx, http.MethodDelete, "/api/v1/auth/cards/"+url.PathEscape(card.ID), nil, nil); backendErr != nil {
			return "Карта удалена локально, но backend не обновлён: " + backendErr.Error()
		}
	}
	return "Карта удалена"
}

func (s *LocalBankService) RefreshSelectedCard(ctx context.Context, playerUUID string) string {
	selected, ok := s.SelectedCard()
	if !ok {
		return "Нет карты для обновления"
	}
	card, message, err := s.fetchCard(ctx, selected.ID, selected.Token, playerUUID, false)
	if err != nil {
		return "Не удалось обновить карту: " + err.Error()
	}
	card.WebhookEnabled = selected.WebhookEnabled
	s.mu.Lock()
	for i := range s.cards {
		if s.cards[i].ID == card.ID {
			s.cards[i] = card
			break
		}
	}
	err = s.saveLocked()
	s.mu.Unlock()
	if err != nil {
		return "Не удалось сохранить карту: " + err.Error()
	}
	if message != "" {
		return message
	}
	return "Карта обновлена"
}

func (s *LocalBankService) fetchCard(ctx context.Context, id, token, playerUUID string, warnOwner bool) (CardViewModel, string, error) {
	var account struct {
		MinecraftUUID string                              `json:"minecraftUUID"`
		Cards         []struct{ ID, Name, Number string } `json:"cards"`
	}
	if err := s.spworldsRequest(ctx, http.MethodGet, "/api/public/accounts/me", id, token, nil, &account); err != nil {
		return CardViewModel{}, "", err
	}
	var info struct {
		Balance int64 `json:"balance"`
	}
	if err := s.spworldsRequest(ctx, http.MethodGet, "/api/public/card", id, token, nil, &info); err != nil {
		return CardViewModel{}, "", err
	}
	name, number := "Карта", lastDigits(id, 5)
	found := false
	for _, candidate := range account.Cards {
		if candidate.ID == id {
			found = true
			if candidate.Name != "" {
				name = candidate.Name
			}
			if candidate.Number != "" {
				number = candidate.Number
			}
			break
		}
	}
	if !found {
		return CardViewModel{}, "", errors.New("карта не найдена в аккаунте SPWorlds")
	}
	card := CardViewModel{ID: id, Token: token, Number: number, Title: number + ": " + name, Balance: info.Balance, OwnerUUID: normalizeUUID(account.MinecraftUUID)}
	if warnOwner && playerUUID != "" && normalizeUUID(playerUUID) != card.OwnerUUID {
		return card, "Вы не владелец карты. Часть функций может быть ограничена.", nil
	}
	return card, "", nil
}

func (s *LocalBankService) RecipientCards(ctx context.Context, username string) ([]string, error) {
	card, ok := s.SelectedCard()
	if !ok || strings.TrimSpace(username) == "" {
		return nil, nil
	}
	var values []struct{ Name, Number string }
	path := "/api/public/accounts/" + url.PathEscape(username) + "/cards"
	if err := s.spworldsRequest(ctx, http.MethodGet, path, card.ID, card.Token, nil, &values); err != nil {
		return nil, err
	}
	result := make([]string, 0, len(values))
	for _, value := range values {
		if value.Number != "" {
			result = append(result, value.Name+" : "+value.Number)
		}
	}
	return result, nil
}

func (s *LocalBankService) SubmitPayment(ctx context.Context, draft PaymentDraft) (bool, string) {
	card, ok := s.SelectedCard()
	if !ok || card.ID != draft.SenderCardID {
		return false, "Не найдены креды карты отправителя"
	}
	var result struct {
		Balance int64 `json:"balance"`
	}
	var err error
	if s.backendReady() {
		payload := map[string]any{"cardId": card.ID, "receiverCard": draft.Recipient, "receiverName": draft.Recipient, "amount": draft.Amount, "comment": draft.Comment}
		err = s.backendRequest(ctx, http.MethodPut, "/api/v1/transactions", payload, nil)
		if err == nil {
			fresh, _, refreshErr := s.fetchCard(ctx, card.ID, card.Token, "", false)
			if refreshErr == nil {
				result.Balance = fresh.Balance
			}
		}
	} else {
		comment := draft.Comment
		if comment == "" {
			comment = "Перевод через SPMega"
		}
		err = s.spworldsRequest(ctx, http.MethodPost, "/api/public/transactions", card.ID, card.Token, map[string]any{"receiver": draft.Recipient, "amount": draft.Amount, "comment": comment}, &result)
	}
	status, message := "SUCCESS", "Перевод выполнен"
	if err != nil {
		status, message = "FAILED: "+trimMessage(err.Error()), "Ошибка перевода: "+err.Error()
	}
	s.mu.Lock()
	if err == nil {
		for i := range s.cards {
			if s.cards[i].ID == card.ID {
				s.cards[i].Balance = result.Balance
				break
			}
		}
	}
	s.transactions = append([]storedTransaction{{CardID: card.ID, Transaction: Transaction{Receiver: draft.Recipient, Amount: draft.Amount, Comment: draft.Comment, Status: status, CreatedAt: time.Now().Format(time.RFC3339)}}}, s.transactions...)
	saveErr := s.saveLocked()
	s.mu.Unlock()
	if saveErr != nil && err == nil {
		return false, "Ошибка сохранения перевода: " + saveErr.Error()
	}
	return err == nil, message
}

func (s *LocalBankService) RegisterSelectedWebhook(ctx context.Context) string {
	card, ok := s.SelectedCard()
	if !ok {
		return "Сначала выбери или добавь карту"
	}
	if card.WebhookEnabled {
		return "Вебхук для этой карты уже включён"
	}
	if !s.backendReady() {
		return "Не удалось включить вебхук: backend недоступен"
	}
	if err := s.backendRequest(ctx, http.MethodPut, "/api/v1/webhook/"+url.PathEscape(card.ID), nil, nil); err != nil {
		return "Не удалось включить вебхук: " + err.Error()
	}
	s.mu.Lock()
	for i := range s.cards {
		if s.cards[i].ID == card.ID {
			s.cards[i].WebhookEnabled = true
			break
		}
	}
	err := s.saveLocked()
	s.mu.Unlock()
	if err != nil {
		return "Не удалось сохранить состояние вебхука: " + err.Error()
	}
	return "Обработка вебхуков включена"
}

func (s *LocalBankService) Transactions(ctx context.Context, cardID string, page int) ([]Transaction, error) {
	if s.backendReady() {
		var raw json.RawMessage
		if err := s.backendRequest(ctx, http.MethodGet, fmt.Sprintf("/api/v1/transactions?p=%s", url.QueryEscape(fmt.Sprint(page))), nil, &raw); err == nil {
			if remote, parseErr := parseRemoteTransactions(raw, cardID); parseErr == nil {
				return remote, nil
			}
		}
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	result := make([]Transaction, 0)
	for _, item := range s.transactions {
		if item.CardID == cardID {
			result = append(result, item.Transaction)
		}
	}
	if page < 1 {
		page = 1
	}
	const pageSize = 10
	start := (page - 1) * pageSize
	if start >= len(result) {
		return []Transaction{}, nil
	}
	end := start + pageSize
	if end > len(result) {
		end = len(result)
	}
	return result[start:end], nil
}

func parseRemoteTransactions(data []byte, _ string) ([]Transaction, error) {
	var root any
	if err := json.Unmarshal(data, &root); err != nil {
		return nil, err
	}
	if object, ok := root.(map[string]any); ok {
		for _, key := range []string{"$values", "transactions", "values"} {
			if value, exists := object[key]; exists {
				root = value
				break
			}
		}
	}
	items, ok := root.([]any)
	if !ok {
		return nil, errors.New("список транзакций отсутствует")
	}
	result := make([]Transaction, 0)
	for _, item := range items {
		object, ok := item.(map[string]any)
		if !ok {
			continue
		}
		receiver := firstString(object, "receiver", "receiverName", "receiverCardNumber", "recipient")
		if receiver == "" {
			receiver = "unknown"
		}
		status := stringValue(object, "status")
		if status == "" {
			status = "SUCCESS"
		}
		amount, _ := object["amount"].(float64)
		result = append(result, Transaction{Receiver: receiver, Amount: int64(amount), Comment: stringValue(object, "comment"), Status: status, CreatedAt: firstString(object, "transactionDate", "createdAt", "created_at")})
	}
	return result, nil
}

func (s *LocalBankService) spworldsRequest(ctx context.Context, method, path, id, token string, payload, target any) error {
	auth := base64.StdEncoding.EncodeToString([]byte(id + ":" + token))
	return s.request(ctx, method, s.spworldsURL+path, "Bearer "+auth, payload, target)
}
func (s *LocalBankService) backendReady() bool {
	token := strings.TrimSpace(s.backendToken())
	return s.allowBackend() && s.backendURL != "" && token != "" && token != "-"
}

func (s *LocalBankService) backendRequest(ctx context.Context, method, path string, payload, target any) error {
	if s.backendURL == "" {
		return errors.New("backend URL не настроен")
	}
	return s.request(ctx, method, s.backendURL+path, "Bearer "+s.backendToken(), payload, target)
}
func (s *LocalBankService) sendCardToBackend(ctx context.Context, id, token string) error {
	return s.backendRequest(ctx, http.MethodPut, "/api/v1/auth/cards", map[string]string{"id": id, "token": token}, nil)
}

// SyncBackendCards merges backend-owned card credentials without dropping local metadata/history.
func (s *LocalBankService) SyncBackendCards(ctx context.Context, playerUUID string) error {
	if !s.backendReady() {
		return nil
	}
	var raw json.RawMessage
	if err := s.backendRequest(ctx, http.MethodGet, "/api/v1/auth/cards", nil, &raw); err != nil {
		return err
	}
	cards, err := parseBackendCards(raw)
	if err != nil {
		return err
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	byID := make(map[string]int, len(s.cards))
	for index, card := range s.cards {
		byID[card.ID] = index
	}
	for _, remote := range cards {
		id := strings.TrimSpace(remote.ID)
		if id == "" {
			continue
		}
		token := decodeBackendCardToken(id, remote.Token)
		if index, ok := byID[id]; ok {
			card := s.cards[index]
			if token != "" {
				card.Token = token
			}
			if remote.Name != "" {
				card.Title = remote.Name
			}
			if remote.SPWorldsID != "" {
				card.Number = remote.SPWorldsID
			}
			card.WebhookEnabled = card.WebhookEnabled || remote.WebhookConnected
			if card.OwnerUUID == "" {
				card.OwnerUUID = playerUUID
			}
			s.cards[index] = card
			continue
		}
		number := remote.SPWorldsID
		if number == "" {
			number = lastDigits(id, 5)
		}
		title := remote.Name
		if title == "" {
			title = "Карта"
		}
		title = number + ": " + title
		s.cards = append(s.cards, CardViewModel{ID: id, Token: token, Title: title, Number: number, OwnerUUID: playerUUID, WebhookEnabled: remote.WebhookConnected})
		byID[id] = len(s.cards) - 1
	}
	s.normalizeSelectedLocked()
	return s.saveLocked()
}

func decodeBackendCardToken(cardID, encoded string) string {
	decoded, err := base64.StdEncoding.DecodeString(strings.TrimSpace(encoded))
	if err != nil {
		return encoded
	}
	prefix := cardID + ":"
	value := string(decoded)
	if !strings.HasPrefix(value, prefix) {
		return encoded
	}
	return strings.TrimPrefix(value, prefix)
}

func parseBackendCards(raw []byte) ([]BackendCard, error) {
	var value any
	if err := json.Unmarshal(raw, &value); err != nil {
		return nil, fmt.Errorf("decode backend cards: %w", err)
	}
	var values []any
	switch typed := value.(type) {
	case []any:
		values = typed
	case map[string]any:
		for _, key := range []string{"$values", "cards", "values"} {
			if candidate, ok := typed[key].([]any); ok {
				values = candidate
				break
			}
		}
	}
	cards := make([]BackendCard, 0, len(values))
	for _, value := range values {
		data, err := json.Marshal(value)
		if err != nil {
			continue
		}
		var card BackendCard
		if json.Unmarshal(data, &card) == nil {
			cards = append(cards, card)
		}
	}
	return cards, nil
}

func (s *LocalBankService) request(ctx context.Context, method, endpoint, authorization string, payload, target any) error {
	var body bytes.Reader
	if payload != nil {
		data, err := json.Marshal(payload)
		if err != nil {
			return err
		}
		body = *bytes.NewReader(data)
	}
	req, err := http.NewRequestWithContext(ctx, method, endpoint, &body)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Authorization", authorization)
	if payload != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	response, err := s.http.Do(req)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		return err
	}
	if len(raw) == 1<<20 {
		return errors.New("response body too large")
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		message := extractMessage(string(raw))
		if message == "" {
			message = http.StatusText(response.StatusCode)
		}
		return fmt.Errorf("%d: %s", response.StatusCode, message)
	}
	if target == nil || len(raw) == 0 {
		return nil
	}
	if pointer, ok := target.(*json.RawMessage); ok {
		*pointer = append((*pointer)[:0], raw...)
		return nil
	}
	return json.Unmarshal(raw, target)
}

func extractMessage(raw string) string {
	var value any
	if json.Unmarshal([]byte(raw), &value) != nil {
		return strings.TrimSpace(raw)
	}
	if array, ok := value.([]any); ok && len(array) > 0 {
		value = array[0]
	}
	if object, ok := value.(map[string]any); ok {
		return firstString(object, "message", "error")
	}
	return strings.TrimSpace(raw)
}
func stringValue(object map[string]any, key string) string {
	value, _ := object[key].(string)
	return value
}
func firstString(object map[string]any, keys ...string) string {
	for _, key := range keys {
		if value := stringValue(object, key); value != "" {
			return value
		}
	}
	return ""
}
func normalizeUUID(value string) string { return strings.ToLower(strings.ReplaceAll(value, "-", "")) }
func lastDigits(value string, count int) string {
	value = strings.ReplaceAll(value, "-", "")
	if len(value) < count {
		return strings.Repeat("0", count)
	}
	return value[len(value)-count:]
}
func trimMessage(value string) string {
	if len(value) > 120 {
		return value[:120]
	}
	return value
}

// ReadNotifications returns pending backend webhook notifications and marks them read.
func (s *LocalBankService) ReadNotifications(ctx context.Context) ([]PaymentNotification, error) {
	if !s.backendReady() {
		return nil, nil
	}
	var raw json.RawMessage
	if err := s.backendRequest(ctx, http.MethodGet, "/api/v1/webhook/read", nil, &raw); err != nil {
		return nil, err
	}
	return parseNotifications(raw)
}

// HasEnabledCards reports whether webhook polling should run.
func (s *LocalBankService) HasEnabledCards() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, card := range s.cards {
		if card.WebhookEnabled {
			return true
		}
	}
	return false
}

func parseNotifications(raw []byte) ([]PaymentNotification, error) {
	var value any
	if err := json.Unmarshal(raw, &value); err != nil {
		return nil, fmt.Errorf("decode notifications: %w", err)
	}
	var list []any
	switch v := value.(type) {
	case []any:
		list = v
	case map[string]any:
		for _, key := range []string{"$values", "notifications", "values"} {
			if candidate, ok := v[key].([]any); ok {
				list = candidate
				break
			}
		}
	}
	result := make([]PaymentNotification, 0, len(list))
	for _, item := range list {
		object, ok := item.(map[string]any)
		if !ok {
			continue
		}
		result = append(result, PaymentNotification{ID: stringValue(object, "id"), SenderName: firstString(object, "senderName", "sender"), SenderNumber: firstString(object, "senderNumber", "senderCardNumber"), Comment: stringValue(object, "comment"), Amount: int64(numberValue(object["amount"])), Type: stringValue(object, "type")})
	}
	return result, nil
}
func numberValue(value any) float64 {
	switch v := value.(type) {
	case float64:
		return v
	case json.Number:
		n, _ := v.Float64()
		return n
	case int:
		return float64(v)
	case int64:
		return float64(v)
	}
	return 0
}
