using System.Text.Json;

namespace SpMega.Backend.Persistent.Models.DTO;

public record ModTelemetryEventDto(
    string EventType,
    DateTimeOffset Timestamp,
    JsonElement Payload
);

public record ModTelemetryBatchDto(
    string ClientVersion,
    string SessionId,
    DateTimeOffset SentAt,
    List<ModTelemetryEventDto> Events
);
