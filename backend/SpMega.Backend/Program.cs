using System.Text;
using Microsoft.AspNetCore.Authorization;
using Microsoft.EntityFrameworkCore;
using MongoDB.Driver;
using SpMega.Backend.Authetication;
using SpMega.Backend.Persistent.Database;
using SpMega.Backend.Services;

namespace SpMega.Backend;

public class Program
{
    public static void Main(string[] args)
    {
        var builder = WebApplication.CreateBuilder(args);
        builder.Configuration
            .AddJsonFile("appsettings.json", false)
            .AddJsonFile("appsettings.Development.json", false)
            .AddEnvironmentVariables();
        
        builder.Services.AddAuthorization();
        builder.Services.AddLogging(logging =>
        {
            logging.AddConsole();
            logging.AddDebug();
        });
        builder.Services
            .AddScoped<TokenService>()
            .AddRazorPages();
        builder.Services
            .AddHttpLogging(logging =>
            {
                logging.LoggingFields = Microsoft.AspNetCore.HttpLogging.HttpLoggingFields.All;
            })
            .AddRouting()
            .AddControllers();
        
        
        builder.Services.AddAuthentication(options =>
            {
                options.DefaultAuthenticateScheme = JwtAuthenticationSchemeOptions.DefaultScheme;
                options.DefaultChallengeScheme = JwtAuthenticationSchemeOptions.DefaultScheme;
            })
            .AddScheme<JwtAuthenticationSchemeOptions, JwtAuthHandler>(
                JwtAuthenticationSchemeOptions.DefaultScheme,
                options => { });

        builder.Services.AddAuthorizationBuilder()
            .SetDefaultPolicy(new AuthorizationPolicyBuilder()
                .RequireAuthenticatedUser()
                .Build());
        
        var serviceCollection = new ServiceCollection();
        var mongoClient =
            new MongoClient(builder.Configuration.GetValue<string>("Mongo") ?? "mongodb://curiosity:27018");
        serviceCollection.AddSingleton<IMongoClient>(mongoClient);
        
        builder.Services.AddDbContext<AppDbContext>((_, options) =>
        {
            options.UseMongoDB(mongoClient, "spmega");
        });
    
        var app = builder.Build();

        app.UseAuthentication();
        app.UseAuthorization();
        app.UseWhen(ctx => ctx.Request.Path.StartsWithSegments("/api"), appBuilder =>
        {
            appBuilder.UseHttpLogging();
        });

        app.MapControllers();
        app.MapRazorPages();

        app.Run();
    }
    
    public static string GenerateRandomString(int length)
    {
        const string chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        
        var random = new Random(); 
        var result = new StringBuilder(length);

        for (var i = 0; i < length; i++)
        {
            var index = random.Next(chars.Length);
            result.Append(chars[index]);
        }

        return result.ToString();
    }
}