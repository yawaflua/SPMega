package screens

import "strings"

const manageCardChatMarker = "управление картой"

func ParseChatCard(message string, clickValues []string) (cardID, cardToken string, ok bool) {
	if !strings.Contains(strings.ToLower(message), manageCardChatMarker) || len(clickValues) < 2 {
		return "", "", false
	}
	cardToken = strings.TrimSpace(clickValues[0])
	cardID = strings.TrimSpace(clickValues[1])
	if cardID == "" || cardToken == "" {
		return "", "", false
	}
	return cardID, cardToken, true
}
