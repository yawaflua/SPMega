using System.Diagnostics;
using System.Text.Json;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SpMega.Backend.Persistent.Models.DTO;
using SpMega.Backend.Persistent.Models.Users;

namespace SpMega.Backend.Controllers.v1;

[ApiController]
[Route("api/v1/telemetry")]
[Authorize]
public class TelemetryController : ControllerBase
{
    private static readonly ActivitySource Source = new("SpMega.ModTelemetry");

    [HttpPost]
    [AllowAnonymous]
    public IActionResult Post([FromBody] ModTelemetryBatchDto batch)
    {
        if (batch?.Events == null || batch.Events.Count == 0)
        {
            return Ok(new { received = 0 });
        }

        var user = HttpContext.Items["@me"] as User;
        var userId = user?.Id.ToString() ?? User.FindFirst(System.Security.Claims.ClaimTypes.NameIdentifier)?.Value ?? "anonymous";
        var sessionId = !string.IsNullOrEmpty(batch.SessionId) ? batch.SessionId : Guid.NewGuid().ToString("N");

        foreach (var e in batch.Events)
        {
            using var activity = Source.StartActivity("mod.telemetry.event", ActivityKind.Client);
            if (activity is null) continue;

            activity.SetTag("user.id", userId);
            activity.SetTag("session.id", sessionId);
            activity.SetTag("client.version", batch.ClientVersion ?? string.Empty);
            activity.SetTag("event.type", e.EventType);
            activity.SetTag("event.timestamp", e.Timestamp.ToString("O"));
            activity.SetTag("event.payload", JsonSerializer.Serialize(e.Payload));
            activity.SetTag("batch.sent_at", batch.SentAt.ToString("O"));
            
        }

        return Ok(new { received = batch.Events.Count });
    }
}
