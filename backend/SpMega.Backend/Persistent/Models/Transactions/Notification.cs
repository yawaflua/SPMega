using SpMega.Backend.Persistent.Models.Users;

namespace SpMega.Backend.Persistent.Models.Transactions;

public class Notification
{
    public Guid Id { get; set; }
    public Guid ReceiverId { get; set; }
    public string ReceiverName { get; set; }
    public string ReceiverNumber { get; set; }
    public string SenderName { get; set; }
    public string SenderNumber { get; set; }
    
    public string Comment { get; set; }
    public int Amount { get; set; }
    public string Type { get; set; }
    
    public bool IsRead { get; set; } = false;
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;
}