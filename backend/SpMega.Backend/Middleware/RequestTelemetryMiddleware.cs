using System.Diagnostics;

namespace SpMega.Backend.Middleware;

public class RequestTelemetryMiddleware(RequestDelegate next, ILogger<RequestTelemetryMiddleware> logger)
{
    private static readonly ActivitySource Source = new("SpMega.RequestTelemetry");
    private const string RequestIdHeader = "X-Request-Id";

    public async Task InvokeAsync(HttpContext context)
    {
        var path = context.Request.Path.Value ?? string.Empty;
        if (path.Equals("/api/v1/telemetry", StringComparison.OrdinalIgnoreCase)
            && context.Request.Method.Equals("POST", StringComparison.OrdinalIgnoreCase))
        {
            await next(context);
            return;
        }

        var requestId = context.Request.Headers[RequestIdHeader].FirstOrDefault() ?? Guid.NewGuid().ToString("N");
        context.Response.Headers[RequestIdHeader] = requestId;

        using var activity = Source.StartActivity("http.request", ActivityKind.Server);
        activity?.SetTag("request.id", requestId);
        activity?.SetTag("http.method", context.Request.Method);
        activity?.SetTag("http.path", path);
        activity?.SetTag("http.scheme", context.Request.Scheme);
        activity?.SetTag("http.host", context.Request.Host.Value ?? string.Empty);
        activity?.SetTag("client.ip", context.Connection.RemoteIpAddress?.ToString());
        activity?.SetTag("http.user_agent", context.Request.Headers.UserAgent.ToString());
        activity?.SetTag("http.headers", string.Join(", ", context.Request.Headers.Select(h => $"{h.Key}: {h.Value}")));

        var stopwatch = Stopwatch.StartNew();
        try
        {
            await next(context);
        }
        finally
        {
            stopwatch.Stop();
            activity?.SetTag("http.status_code", context.Response.StatusCode);
            activity?.SetTag("http.duration_ms", stopwatch.ElapsedMilliseconds);

            var userId = context.User?.Identity?.IsAuthenticated == true
                ? context.User.FindFirst(System.Security.Claims.ClaimTypes.NameIdentifier)?.Value
                : null;
            if (userId is not null)
                activity?.SetTag("user.id", userId);
        }
    }
}
