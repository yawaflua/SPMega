using System.ComponentModel.DataAnnotations;
using System.Text.Json.Serialization;
using SpMega.Backend.Persistent.Models.Users;

namespace SpMega.Backend.Persistent.Models.Transactions;

public class Transaction
{
    public Guid Id { get; set; }
    public string ShortId { get; set; } = Program.GenerateRandomString(8);
    [MaxLength(2048)] public string ReceiverName { get; set; }
    [MaxLength(2048)] public string ReceiverCardNumber { get; set; }
    [JsonIgnore] public User Sender { get; set; }
    
    [MaxLength(2048)] public string SenderMinecraftName { get; set; }
    [MaxLength(2048)] public string SenderCardNumber { get; set; }
    
    public int Amount { get; set; } = 0;
    [MaxLength(2048)] public string Comment { get; set; } = "";
    
    public DateTime TransactionDate { get; set; } = DateTime.UtcNow;
}