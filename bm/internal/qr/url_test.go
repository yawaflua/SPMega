package qr

import "testing"

func TestValidateURL(t *testing.T) {
	t.Parallel()

	for _, value := range []string{"https://spmega.yawaflua.tech/pay", "https://spworlds.ru/pay", "https://ywfl.dev/s123"} {
		if err := ValidateURL(value); err != nil {
			t.Errorf("ValidateURL(%q) = %v", value, err)
		}
	}
	for _, value := range []string{"", "not-a-url", "javascript:alert(1)", "file:///tmp/test", "http://localhost:8080/test", "https://evil.example/pay", "https://spworlds.ru@evil.example/pay"} {
		if err := ValidateURL(value); err == nil {
			t.Errorf("ValidateURL(%q) unexpectedly succeeded", value)
		}
	}
}
