package telemetry

import (
	"net/http"
	"net/url"
	"strings"
	"time"
)

// RecordLifecycle records client initialization/deinitialization phases.
func RecordLifecycle(collector *Collector, phase string, durationMs int64) {
	if collector == nil {
		collector = defaultCollector
	}
	payload := map[string]any{"phase": phase}
	if durationMs >= 0 {
		payload["durationMs"] = durationMs
	}
	collector.Record(NewEvent("lifecycle", payload))
}

// RecordPayment records payment outcome without accepting card credentials.
func RecordPayment(collector *Collector, success bool, mode, destination string, durationMs int64, senderCardLast4 string, amount any, receiver, eventError string) {
	if collector == nil {
		collector = defaultCollector
	}
	if senderCardLast4 == "" {
		senderCardLast4 = "unknown"
	}
	payload := map[string]any{"success": success, "mode": mode, "destination": destination, "durationMs": durationMs, "senderCardLast4": senderCardLast4, "amount": amount, "receiver": receiver}
	if eventError != "" {
		payload["error"] = eventError
	}
	collector.Record(NewEvent("payment", payload))
}

// RecordQR records QR scan outcome. decodedURL is sanitized before collection.
func RecordQR(collector *Collector, success, didLag bool, decodedURL, eventError string, durationMs int64) {
	if collector == nil {
		collector = defaultCollector
	}
	payload := map[string]any{"success": success, "didLag": didLag, "durationMs": durationMs}
	if decodedURL != "" {
		payload["decodedUrl"] = SanitizeURI(decodedURL)
	}
	if eventError != "" {
		payload["error"] = eventError
	}
	collector.Record(NewEvent("qr_scan", payload))
}

// RecordPerformance records one FPS/memory sampling snapshot.
func RecordPerformance(collector *Collector, fpsAvg, fpsMin, fpsMax, sampleCount int, periodMs int64, usedMemoryMb, totalMemoryMb, maxMemoryMb int64) {
	if collector == nil {
		collector = defaultCollector
	}
	collector.Record(NewEvent("performance_snapshot", map[string]any{"fpsAvg": fpsAvg, "fpsMin": fpsMin, "fpsMax": fpsMax, "sampleCount": sampleCount, "periodMs": periodMs, "usedMemoryMb": usedMemoryMb, "totalMemoryMb": totalMemoryMb, "maxMemoryMb": maxMemoryMb}))
}

// RecordHTTPRequest records request metadata only; response bodies and headers are never collected.
func RecordHTTPRequest(collector *Collector, rawURL, method string, statusCode int, durationMs int64, success bool, errorType string, fpsBefore, fpsAfter int) {
	if collector == nil {
		collector = defaultCollector
	}
	payload := map[string]any{"target": ClassifyTarget(rawURL), "method": method, "path": SanitizeURI(rawURL), "statusCode": statusCode, "durationMs": durationMs, "success": success, "fpsBefore": fpsBefore, "fpsAfter": fpsAfter}
	if errorType != "" {
		payload["errorType"] = errorType
	}
	collector.Record(NewEvent("http_request", payload))
}

// HTTPRecorder wraps an HTTP client and records request timing. It does not record response data.
type HTTPRecorder struct {
	Client    *http.Client
	Collector *Collector
	FPS       func() int
}

func (r HTTPRecorder) Do(req *http.Request) (*http.Response, error) {
	client := r.Client
	if client == nil {
		client = http.DefaultClient
	}
	collector := r.Collector
	if collector == nil {
		collector = defaultCollector
	}
	before := 0
	if r.FPS != nil {
		before = r.FPS()
	}
	start := nowNanos()
	response, err := client.Do(req)
	duration := (nowNanos() - start) / 1_000_000
	after := before
	if r.FPS != nil {
		after = r.FPS()
	}
	if err != nil {
		RecordHTTPRequest(collector, requestURL(req), requestMethod(req), -1, duration, false, errorName(err), before, after)
		return nil, err
	}
	RecordHTTPRequest(collector, requestURL(req), requestMethod(req), response.StatusCode, duration, true, "", before, after)
	return response, nil
}
func requestURL(req *http.Request) string {
	if req == nil || req.URL == nil {
		return ""
	}
	return req.URL.String()
}
func requestMethod(req *http.Request) string {
	if req == nil || req.Method == "" {
		return http.MethodGet
	}
	return req.Method
}
func errorName(err error) string {
	if err == nil {
		return ""
	}
	text := strings.TrimSpace(err.Error())
	if i := strings.IndexByte(text, ':'); i > 0 {
		return text[:i]
	}
	return "error"
}

var nowNanos = func() int64 { return time.Now().UnixNano() }

// ParseTarget is a convenience for callers that already parsed a URL.
func ParseTarget(u *url.URL) string {
	if u == nil {
		return "unknown"
	}
	return ClassifyTarget(u.String())
}
