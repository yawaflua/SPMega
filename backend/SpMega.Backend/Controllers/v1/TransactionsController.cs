using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
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
   private const string BASE_URL = "https://spworlds.ru/api/public";

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
      var BearerToken = $"{body.cardToUse.Id}:{body.cardToUse.Token}";
      string Base64BearerToken = Convert.ToBase64String(Encoding.UTF8.GetBytes(BearerToken));

      
      var shortId = Program.GenerateRandomString(8);
      var transaction = new Transaction
      {
         ReceiverName = body.receiverName,
         ShortId = shortId,
         ReceiverCardNumber = body.receiverCard,
         Sender = HttpContext.Items["@me"] as User,
         SenderCardNumber = body.cardToUse.SpworldsID,
         Amount = body.amount,
         Comment = body.comment,
      };
      try
      {
         var uri = new Uri(new Uri(config["Url"] ?? "https://spmega.yawaflua.tech"), "/" + shortId);
         var transitionInfo = new Dictionary<string, object>
         {
            { "receiver", body.receiverCard },
            { "amount", body.amount },
            { "comment", "Чек: "+ uri }
         };

         await SendRequest(endpoint: "transactions", body: transitionInfo, AuthHeader:new("Bearer", Base64BearerToken));

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
public record CreateTransactionBody(Card cardToUse, string receiverCard, string receiverName, string comment, int amount);