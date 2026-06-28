using SpMega.Backend.Persistent.Models.Transactions;

namespace SpMega.Backend.Persistent.Models.Users;

public class User
{
    public Guid Id { get; set; }
    public string Username { get; set; }
    public string Token { get; set; }

    public List<Card> Cards { get; set; } = [];
    public List<Transaction> Transactions { get; set; } = [];
    
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public DateTime UpdatedAt { get; set; }
    public DateTime? DeletedAt { get; set; }
    public bool IsDeleted { get; set; } = false;
}