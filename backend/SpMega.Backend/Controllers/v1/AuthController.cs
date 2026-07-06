using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using MongoDB.Driver;
using SpMega.Backend.Persistent.Database;
using SpMega.Backend.Persistent.Models.DTO;
using SpMega.Backend.Persistent.Models.Users;
using SpMega.Backend.Services;
using SPWorldsApi;
using SPWorldsApi.Types.Interfaces;

namespace SpMega.Backend.Controllers.v1;


public record GetSessionIdBody(string userName, Guid userUUID);
public record ValidateSessionBody(string sessionId, Guid userUUID);
[Route("api/v1/auth")]
[ApiController]
public class AuthController(AppDbContext dbContext, TokenService tokenService, ILogger<AuthController> logger) : ControllerBase
{
    private const string BASE_URL = "https://spworlds.ru/api/public";
    public AuthenticationHeaderValue? AuthHeader { get; set; }

    [HttpPost("start")]
    public async Task<IActionResult> GetSessionIdAsync([FromBody] GetSessionIdBody body)
    {
        var userSession = new UserSession
        {
            Id = Guid.NewGuid(),
            Token = Program.GenerateRandomString(15),
            UserId = body.userUUID,
            UserName = body.userName,
            CreatedAt = DateTime.UtcNow
        };
        await dbContext.UserSessions.AddAsync(userSession);
        await dbContext.SaveChangesAsync();
        
        
        return Ok(new { sessionId = userSession.Token});
    }
    
    [HttpPost("validate")]
    public async Task<ActionResult<User>> ValidateSessionAsync([FromBody]ValidateSessionBody body)
    {
        try
        {
            var session =
                await dbContext.UserSessions.FirstOrDefaultAsync(k => k.UserId == body.userUUID && k.Token == body.sessionId);
            if (session == null || session.CreatedAt.AddMinutes(2) < DateTime.UtcNow || session.IsDeleted)
                throw new Exception("Session expired or not fount");

            using var httpClient = new HttpClient();
            using var request = new HttpRequestMessage(
                HttpMethod.Get,
                $"https://sessionserver.mojang.com/session/minecraft/hasJoined?username={session.UserName}&serverId={session.Token}"
            );
            session.IsDeleted = true;
            session.DeletedAt = DateTime.UtcNow;
            session.UpdatedAt = DateTime.UtcNow;
            await dbContext.SaveChangesAsync();

            var resp = await httpClient.SendAsync(request);
            if (resp.StatusCode != HttpStatusCode.OK) throw new Exception("Mojang response is not OK");
            Console.WriteLine(await resp.Content.ReadAsStringAsync());
            var dto = await resp.Content.ReadFromJsonAsync<MojangDto>();
            if (dto == null || dto.Name != session.UserName || Guid.Parse(dto.Id) != session.UserId) throw new Exception("Session expired, or dto is not acceptable.");
            var token = tokenService.GenerateAccessToken(session.UserName, body.userUUID);
            var user = await dbContext.Users.FirstOrDefaultAsync(k => k.Id == session.UserId);
            if (user != null)
            {
                user.Token = token;
                dbContext.Update(user);
            }
            else
            {
                user = new User
                {
                    Id = body.userUUID,
                    Username = session.UserName,
                    Token = token,
                };
                await dbContext.AddAsync(user);
            }

            await dbContext.SaveChangesAsync();
            
            return Ok(user);
        }
        catch (Exception e)
        {
            logger.LogError(e, "Error when validation session.");
            return BadRequest("I think ure try to fool us. Try again later");
        }

    }

    [HttpPut("cards")]
    [Authorize]
    public async Task<IActionResult> AddCardAsync([FromBody] AddCardBody body)
    {
        try
        {
            var BearerToken = $"{body.id}:{body.token}";
            string Base64BearerToken = Convert.ToBase64String(Encoding.UTF8.GetBytes(BearerToken));
            var resp = await SendRequest("/accounts/me", new("Bearer", Base64BearerToken));
            var me = JsonSerializer.Deserialize<UserAccountDTO>(resp);
            var user = ((User)HttpContext.Items["@me"]);
            
            if (user == null || user.Id != Guid.Parse(me.minecraftUUID))
            {
                throw new Exception("Its not ur card");
            }
            
            var balanceResp = await SendRequest("/public/card", new("Bearer", Base64BearerToken));
            var balance = (int?)JsonNode.Parse(balanceResp)?["balance"];
            
            

            var card = me.cards.First(k => k.id == body.id);
            var existingCard = user.Cards.FirstOrDefault(k => k.Id.ToString() == card.id);
            if (existingCard == null)
                user.Cards.Add(new Card
                {
                    Id =  Guid.Parse(card.id),
                    Name = card.name,
                    Balance = balance ?? -1,
                    SpworldsID = card.number,
                    Token = Base64BearerToken,
                    CreatedAt = DateTime.UtcNow,
                });
            else
            {

                existingCard.Name = card.name;
                existingCard.SpworldsID = card.number;
                existingCard.Token = Base64BearerToken;
                existingCard.Balance = balance ?? -1;
                existingCard.UpdatedAt = DateTime.UtcNow;
                
            }
            await dbContext.SaveChangesAsync();
            return Ok();
        }
        catch (Exception e)
        {
            logger.LogError(e, "Error when adding card.");
            return BadRequest("Error when adding card.");
        }
    }

    

    [HttpGet("cards")]
    [Authorize]
    public async Task<ActionResult<List<Card>>> GetAllCardsAsync()
    {
        return Ok(((User)HttpContext.Items["@me"]).Cards.Where(k => !k.IsDeleted).ToList());
    }

    [HttpDelete("cards/{id}")]
    [Authorize]
    public async Task<IActionResult> DeleteCardAsync(Guid id)
    {
        try
        {
            var user = (HttpContext.Items["@me"] as User);
            user.Cards.RemoveAll(c => c.Id == id);
            await dbContext.SaveChangesAsync();
            return Ok();
        }
        catch (Exception e)
        {
            logger.LogError(e, "Error when deleting card.");
            return BadRequest("Error when deleting card.");
        }
    }
    
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

public record AddCardBody(string id, string token);

