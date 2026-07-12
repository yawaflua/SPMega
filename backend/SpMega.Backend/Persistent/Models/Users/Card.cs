using Microsoft.EntityFrameworkCore;

namespace SpMega.Backend.Persistent.Models.Users;

[Owned]
public class Card
{
    public Guid Id { get; set; }
    public string Name { get; set; }
    public string SpworldsID { get; set; }
    public string Token { get; set; }
    public string? ShortId { get; set; } = "";
    public bool? WebhookConnected { get; set; } = false;

    public int? Balance { get; set; } = -1;
    
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public DateTime UpdatedAt { get; set; }
    public DateTime? DeletedAt { get; set; }
    public bool IsDeleted { get; set; } = false;
}