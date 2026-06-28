using Microsoft.EntityFrameworkCore;
using MongoDB.EntityFrameworkCore.Extensions;
using SpMega.Backend.Persistent.Models.Transactions;
using SpMega.Backend.Persistent.Models.Users;

namespace SpMega.Backend.Persistent.Database;

public class AppDbContext(DbContextOptions<AppDbContext> options) : DbContext(options)
{
    internal DbSet<Transaction> Transactions { get; set; }
    internal DbSet<User> Users { get; set; }
    internal DbSet<UserSession> UserSessions { get; set; }

    protected override void OnConfiguring(DbContextOptionsBuilder optionsBuilder)
    {
        
    }
    
    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        base.OnModelCreating(modelBuilder);
        

    }
}