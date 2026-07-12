using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using MongoDB.Driver.Linq;
using SpMega.Backend.Persistent.Database;
using SpMega.Backend.Persistent.Models.Transactions;
using SpMega.Backend.Persistent.Models.Users;

namespace SpMega.Backend.Controllers.v1;

[ApiController]
[Route("/api/v1/webhook")]
public class WebhookController( AppDbContext dbContext, ILogger<WebhookController> logger, IConfiguration config) : ControllerBase
{
    private const string BASE_URL = "https://spworlds.ru/api/public/";
    
    
    [HttpPut("{cardId}")]
    [Authorize]
    public async Task<IActionResult> RegisterWebhookForCard(Guid cardId)
    {
        var user = HttpContext.Items["@me"] as User;
        if (user == null) return Unauthorized();

        var cardToUse = user.Cards.FirstOrDefault(c => c.Id == cardId);
        if (cardToUse == null)
            return BadRequest("Card not found");
        
        var shortId = Program.GenerateRandomString(6);
        while (true)
        {
            var a = user.Cards.FirstOrDefault(k => k.ShortId == shortId);
            if (a != null)
            {
                shortId = Program.GenerateRandomString(6);
            }
            else
            {
                break;
            }
        }
        cardToUse.ShortId = shortId;
        cardToUse.WebhookConnected = true;
        
        await dbContext.SaveChangesAsync();
        
        var webhookUrl = new UriBuilder();
        webhookUrl.Scheme = "https";
        webhookUrl.Host = config["Url"];
        webhookUrl.Path = $"api/v1/webhook/{user.ShortId}/{cardToUse.ShortId}/{cardId}";
        
        var resp = await SendRequest($"card/webhook", new AuthenticationHeaderValue("Bearer", cardToUse.Token), HttpMethod.Put, new { url = webhookUrl.ToString() });
        var jsonResponse = JsonSerializer.Deserialize<JsonObject>(resp);
        if (jsonResponse != null && jsonResponse.TryGetPropertyValue("id", out var respId)
                                 && respId?.GetValue<Guid>() == cardId) return Ok();
        logger.LogError("Failed to register webhook for card {CardId}. Response: {Response}", cardId, resp);
        cardToUse.WebhookConnected = false;
        
        await dbContext.SaveChangesAsync();
        return BadRequest("Failed to register webhook for card");
    }
    
    [HttpPost("{userShortId}/{cardShortId}/{cardId}")]
    public async Task<IActionResult> ReceiveWebhook(string userShortId, string cardShortId, Guid cardId)
    {
        var user = await dbContext.Users.FirstOrDefaultAsync(k => k.ShortId == userShortId);
        if (user == null)
            return BadRequest("User not found");
        var cardToUse = user.Cards.FirstOrDefault(c => c.ShortId == cardShortId && c.Id == cardId && c.WebhookConnected == true);
        if (cardToUse == null)
            return BadRequest("Card not found");
        
        var bodyHash = Request.Headers["X-Body-Hash"].ToString();
        var rawBody = await new StreamReader(Request.Body).ReadToEndAsync();
        var keyBytes = Encoding.UTF8.GetBytes(cardToUse.Token);
        var messageBytes = Encoding.UTF8.GetBytes(rawBody);

        using var hmac = new HMACSHA256(keyBytes);
        var hashBytes = hmac.ComputeHash(messageBytes);

        byte[] receivedHash;
        try
        {
            receivedHash = Convert.FromBase64String(bodyHash);
        }
        catch (FormatException)
        {
            return BadRequest("Invalid body hash");
        }

        if (!CryptographicOperations.FixedTimeEquals(hashBytes, receivedHash))
        {
            logger.LogError("Invalid body hash for webhook. Expected: {ExpectedHash}, Received: {ReceivedHash}", Convert.ToBase64String(hashBytes), bodyHash);
            return BadRequest("Invalid body hash");
        }

        var body = JsonSerializer.Deserialize<Webhook>(rawBody, new JsonSerializerOptions(JsonSerializerDefaults.Web));
        if (body == null || !Guid.TryParse(body.Id, out var notificationId))
            return BadRequest("Invalid webhook body");

        if (await dbContext.Notifications.FirstOrDefaultAsync(notification => notification.Id == notificationId) != null)
            return Ok();

        var notify = new Notification
        {
            Id = notificationId,
            ReceiverId = user.Id,
            ReceiverName = body.Receiver.Username,
            ReceiverNumber = body.Receiver.Number,
            SenderName = body.Sender.Username,
            SenderNumber = body.Sender.Number,
            Comment = body.Comment,
            Amount = body.Amount,
            Type = body.Type,
            IsRead = false,
            CreatedAt = DateTime.UtcNow,
            UpdatedAt = DateTime.UtcNow
        };
        
        await dbContext.Notifications.AddAsync(notify);
        await dbContext.SaveChangesAsync();
        return Ok();
    }

    [HttpGet("read")]
    [Authorize]
    public async Task<ActionResult<List<Notification>>> ReadAllNotifications()
    {
        var user = HttpContext.Items["@me"] as User;
        if (user == null) return Unauthorized();

        var notifications = await dbContext.Notifications
            .Where(notification => notification.ReceiverId == user.Id && !notification.IsRead)
            .ToListAsync();
        foreach (var notification in notifications)
        {
            notification.IsRead = true;
            notification.UpdatedAt = DateTime.UtcNow;
        }
        if (notifications.Count > 0) await dbContext.SaveChangesAsync();
        return Ok(notifications);
    }
    
    [HttpGet("all")]
    [Authorize]
    public async Task<ActionResult<List<Notification>>> GetAllNotifications([FromQuery] int after, [FromQuery] int limit = 20)
    {
        var user = HttpContext.Items["@me"] as User;
        if (user == null) return Unauthorized();
        var notification = await dbContext.Notifications.Where(k => k.ReceiverId == user.Id).Skip(after).Take(limit).ToListAsync();
        return Ok(notification);
    }

    [NonAction]
    internal async Task<string> SendRequest(string endpoint, AuthenticationHeaderValue AuthHeader, HttpMethod method = null, object body = null)
    {
        method ??= body == null ? HttpMethod.Get : HttpMethod.Post;

        HttpResponseMessage message;
        var client = new System.Net.Http.HttpClient();

        using (var requestMessage = new HttpRequestMessage(method, BASE_URL + endpoint))
        {
            requestMessage.Content = new StringContent(
                JsonSerializer.Serialize(body),
                Encoding.UTF8, "application/json"
            );

            requestMessage.Headers.Authorization = AuthHeader;

            message = await client.SendAsync(requestMessage);

        }

        client.Dispose();

        return await message.Content.ReadAsStringAsync();
    }
}

public record Webhook(
    string Id,
    int Amount,
    string Type,
    WebhookUser Sender,
    WebhookUser Receiver,
    string Comment,
    string CreatedAt
);

public record WebhookUser(string Username, string Number);
