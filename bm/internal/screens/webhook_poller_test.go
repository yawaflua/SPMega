package screens

import "testing"

func TestFormatPaymentNotification(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name         string
		notification PaymentNotification
		want         string
	}{
		{name: "name and comment", notification: PaymentNotification{SenderName: "Steve", SenderNumber: "12345", Amount: 42, Comment: "спасибо"}, want: "Получено 42 АР от Steve — спасибо"},
		{name: "number fallback", notification: PaymentNotification{SenderNumber: "12345", Amount: 7}, want: "Получено 7 АР от 12345"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			if got := formatPaymentNotification(test.notification); got != test.want {
				t.Fatalf("got %q, want %q", got, test.want)
			}
		})
	}
}
