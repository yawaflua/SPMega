package screens

import "context"

type CardViewModel struct {
	ID             string `json:"id"`
	Token          string `json:"token,omitempty"`
	Title          string `json:"title"`
	Number         string `json:"number"`
	Balance        int64  `json:"balance"`
	OwnerUUID      string `json:"ownerUuid,omitempty"`
	WebhookEnabled bool   `json:"webhookEnabled"`
}

type PaymentNotification struct {
	ID           string `json:"id"`
	SenderName   string `json:"senderName"`
	SenderNumber string `json:"senderNumber"`
	Comment      string `json:"comment"`
	Amount       int64  `json:"amount"`
	Type         string `json:"type"`
}

type PaymentDraft struct {
	SenderCardID string
	Recipient    string
	Amount       int64
	Comment      string
}

type Transaction struct {
	Receiver  string `json:"receiver"`
	Amount    int64  `json:"amount"`
	Comment   string `json:"comment"`
	Status    string `json:"status"`
	CreatedAt string `json:"createdAt"`
}

type BackendCard struct {
	ID               string `json:"id"`
	Name             string `json:"name"`
	SPWorldsID       string `json:"spworldsID"`
	Token            string `json:"token"`
	WebhookConnected bool   `json:"webhookConnected"`
}

type BankService interface {
	Cards() []CardViewModel
	SelectedCard() (CardViewModel, bool)
	SelectedCardIndex() int
	SelectCard(index int)
	CycleSelectedCard(direction int) (CardViewModel, bool)
	AddCard(ctx context.Context, cardID, cardToken, playerUUID string) string
	RemoveSelectedCard(ctx context.Context) string
	RefreshSelectedCard(ctx context.Context, playerUUID string) string
	RecipientCards(ctx context.Context, username string) ([]string, error)
	SubmitPayment(ctx context.Context, draft PaymentDraft) (bool, string)
	RegisterSelectedWebhook(ctx context.Context) string
	Transactions(ctx context.Context, cardID string, page int) ([]Transaction, error)
}
