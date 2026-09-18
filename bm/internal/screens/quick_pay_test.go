package screens

import "testing"

func TestExtractQuickPayCardNumber(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name string
		text string
		want string
	}{
		{name: "plain", text: "12345", want: "12345"},
		{name: "surrounded", text: "Оплата: 12345 АР", want: "12345"},
		{name: "first isolated", text: "12345 или 54321", want: "12345"},
		{name: "embedded left", text: "912345", want: ""},
		{name: "embedded right", text: "123456", want: ""},
		{name: "embedded both", text: "9123456", want: ""},
		{name: "unicode digit boundary", text: "١12345", want: ""},
		{name: "too short", text: "1234", want: ""},
		{name: "none", text: "карта", want: ""},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			if got := extractQuickPayCardNumber(test.text); got != test.want {
				t.Fatalf("extractQuickPayCardNumber(%q) = %q, want %q", test.text, got, test.want)
			}
		})
	}
}

func TestExtractQuickPayCardNumberFromTextsPreservesOrder(t *testing.T) {
	t.Parallel()

	texts := []string{"без карты", "front 13579", "back 24680"}
	if got := extractQuickPayCardNumberFromTexts(texts); got != "13579" {
		t.Fatalf("got %q, want %q", got, "13579")
	}
}
