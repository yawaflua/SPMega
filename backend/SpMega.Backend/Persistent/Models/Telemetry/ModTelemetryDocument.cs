using MongoDB.Bson;

namespace SpMega.Backend.Persistent.Models.Telemetry;

public class ModTelemetryDocument
{
    public ObjectId Id { get; set; }
    public string UserId { get; set; } = string.Empty;
    public string SessionId { get; set; } = string.Empty;
    public string ClientVersion { get; set; } = string.Empty;
    public DateTimeOffset SentAt { get; set; }
    public DateTimeOffset ReceivedAt { get; set; } = DateTimeOffset.UtcNow;
    public List<ModTelemetryEventDocument> Events { get; set; } = [];
}

public class ModTelemetryEventDocument
{
    public string EventType { get; set; } = string.Empty;
    public DateTimeOffset Timestamp { get; set; }
    public string PayloadJson { get; set; } = "{}";
}
