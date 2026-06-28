using System.Text.Json.Serialization;
using SpMega.Backend.Persistent.Models.Users;

namespace SpMega.Backend.Persistent.Models.Transactions;

public class Transaction
{
    public Guid Id { get; set; }
    public string ShortId { get; set; } = Program.GenerateRandomString(8);
    public string ReceiverName { get; set; }
    public string ReceiverCardNumber { get; set; }
    [JsonIgnore] public User Sender { get; set; }
    public string SenderCardNumber { get; set; }
    
    public int Amount { get; set; } = 0;
    public string Comment { get; set; } = "";
    
    public DateTime TransactionDate { get; set; } = DateTime.UtcNow;
}