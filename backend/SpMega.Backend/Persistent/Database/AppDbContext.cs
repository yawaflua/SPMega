using Microsoft.EntityFrameworkCore;
using MongoDB.EntityFrameworkCore.Extensions;
using SpMega.Backend.Persistent.Models.Telemetry;
using SpMega.Backend.Persistent.Models.Transactions;
using SpMega.Backend.Persistent.Models.Users;
using SpMega.Backend.Services;

namespace SpMega.Backend.Persistent.Database;

public class AppDbContext(DbContextOptions<AppDbContext> options) : DbContext(options)
{
    internal DbSet<Transaction> Transactions { get; set; }
    internal DbSet<User> Users { get; set; }
    internal DbSet<UserSession> UserSessions { get; set; }
    internal DbSet<ModTelemetryDocument> ModTelemetry { get; set; }
    internal DbSet<BackendRequestTelemetryDocument> BackendRequestTelemetry { get; set; }

    protected override void OnConfiguring(DbContextOptionsBuilder optionsBuilder)
    {
        
    }
    
    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        base.OnModelCreating(modelBuilder);
        
        modelBuilder.Entity<User>()
            .OwnsMany(u => u.Cards, card =>
            {
                card.Property(c => c.Token)
                    .HasConversion(
                        v => EncryptionHelper.Encrypt(v),
                        v => EncryptionHelper.Decrypt(v)
                    );
            });
    }
}