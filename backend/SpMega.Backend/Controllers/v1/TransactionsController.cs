using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using SpMega.Backend.Persistent.Database;
using SpMega.Backend.Persistent.Models.Transactions;
using SpMega.Backend.Persistent.Models.Users;
using SPWorldsApi;

namespace SpMega.Backend.Controllers.v1;

[Route("api/v1/transactions")]
[ApiController]
public class TransactionsController(AppDbContext context, ILogger<TransactionsController> logger, IConfiguration config) : ControllerBase
{
   private const string BASE_URL = "https://spworlds.ru/api/public/";

   [HttpGet("{billId}")]
   public async Task<IActionResult> GetBill(string billId)
   {
      var transaction = await context.Transactions
         .FirstOrDefaultAsync(t => t.ShortId == billId);

      if (transaction == null)
      {
         if (billId == "00000000")
         {
            return Ok(new Transaction
            {
               Id = Guid.Empty,
               ShortId = "00000000",
               ReceiverName = "yawaflua",
               ReceiverCardNumber = "00000",
               Sender = new User
               {
                  Id = default,
                  Username = "yawaflua",
                  Token = "123",
               },
               SenderCardNumber = "010101",
               Amount = 42,
               Comment = "API FIRST",
               TransactionDate = DateTime.UtcNow
            });
         }
         return NotFound(new { error = "Transaction not found" });
      }

      return Ok(transaction);
   }

   [HttpPut()]
   [Authorize]
   public async Task<ActionResult<Transaction>> CreateTransaction([FromBody] CreateTransactionBody body)
   {
      var user = HttpContext.Items["@me"] as User;
      if (user == null)
      {
         return Unauthorized(new { error = "User not authenticated" });
      }
      var cardToUse = user.Cards.FirstOrDefault(l => l.Id == Guid.Parse(body.cardId));
      if (cardToUse == null)
      {
         return BadRequest(new { error = "Card not found" });
      }

      
      var shortId = Program.GenerateRandomString(5);
      while (true)
      {
            var a = await context.Transactions.FirstOrDefaultAsync(k => k.ShortId == shortId);
            if (a != null)
            {
               shortId = Program.GenerateRandomString(5);
            }
            else
            {
               break;
            }
      }
      var transaction = new Transaction
      {
         ReceiverName = body.receiverName,
         ShortId = shortId,
         ReceiverCardNumber = body.receiverCard,
         Sender = user,
         SenderCardNumber = body.cardId,
         Amount = body.amount,
         Comment = body.comment,
      };
      try
      {
         var uri = "s.ywfl.dev" + "/" + shortId;
         var transitionInfo = new Dictionary<string, object>
         {
            { "receiver", body.receiverCard },
            { "amount", body.amount },
            { "comment", body.comment + ";Чек:"+ uri }
         };
         Console.WriteLine((body.comment + ";Чек: "+ uri).Length);
         Console.WriteLine((body.comment + ";Чек: "+ uri));
         var resp = await SendRequest(endpoint: "transactions", body: transitionInfo,
            AuthHeader: new("Bearer", cardToUse.Token));
         var balance = (int?)JsonNode.Parse(resp)?["balance"];
         if (balance == null)
         {
            throw new Exception("Failed to create transaction: " + resp);
         }

      } catch (Exception exception)
      {
         logger.LogError(exception, "Error creating transaction");
         return BadRequest(new { error = "Failed to create transaction" });
      }
      await context.AddAsync(transaction);
      await context.SaveChangesAsync();

      return Ok(new Transaction
      {
         Id = transaction.Id,
         ReceiverName = transaction.ReceiverName,
      });
   }

   [HttpGet()]
   [Authorize]
   public async Task<ActionResult<List<Transaction>>> GetAllTransactions()
   {
      return Ok(await context.Transactions.Where(k => k.Sender.Id == ((User)HttpContext.Items["@me"]).Id).ToListAsync());
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
public record CreateTransactionBody(string cardId, string receiverCard, string receiverName, string comment, int amount);