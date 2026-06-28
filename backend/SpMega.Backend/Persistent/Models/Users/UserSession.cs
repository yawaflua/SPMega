namespace SpMega.Backend.Persistent.Models.Users;

public class UserSession
{
    public Guid Id { get; set; }
    public string Token { get; set; }
    public User? User { get; set; } = null;
    public Guid UserId { get; set; }
    public string UserName { get; set; }
    
    public DateTime CreatedAt { get; init; } = DateTime.UtcNow;
    public DateTime UpdatedAt { get; set; }
    public DateTime? DeletedAt { get; set; }
    public bool IsDeleted { get; set; } = false;
}