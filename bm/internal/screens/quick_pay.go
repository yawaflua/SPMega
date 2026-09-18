package screens

import "unicode"

const quickPayCardLength = 5

func extractQuickPayCardNumber(text string) string {
	runes := []rune(text)
	for start := 0; start+quickPayCardLength <= len(runes); start++ {
		if start > 0 && unicode.IsDigit(runes[start-1]) {
			continue
		}
		matched := true
		for offset := 0; offset < quickPayCardLength; offset++ {
			if runes[start+offset] < '0' || runes[start+offset] > '9' {
				matched = false
				break
			}
		}
		if !matched {
			continue
		}
		end := start + quickPayCardLength
		if end < len(runes) && unicode.IsDigit(runes[end]) {
			continue
		}
		return string(runes[start:end])
	}
	return ""
}

func extractQuickPayCardNumberFromTexts(texts []string) string {
	for _, text := range texts {
		if number := extractQuickPayCardNumber(text); number != "" {
			return number
		}
	}
	return ""
}
