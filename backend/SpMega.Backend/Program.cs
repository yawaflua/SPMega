using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.Authorization;
using Microsoft.EntityFrameworkCore;
using MongoDB.Driver;
using OpenTelemetry.Exporter;
using OpenTelemetry.Metrics;
using OpenTelemetry.Resources;
using OpenTelemetry.Trace;
using SpMega.Backend.Authetication;
using SpMega.Backend.Middleware;
using SpMega.Backend.Persistent.Database;
using SpMega.Backend.Services;

namespace SpMega.Backend;

public class Program
{
    public static void Main(string[] args)
    {
        var builder = WebApplication.CreateBuilder(args);
        builder.Configuration
            .AddJsonFile("appsettings.json", true)
            .AddJsonFile("appsettings.Development.json", true)
            .AddEnvironmentVariables();

        var conf = builder.Configuration;
        var encryptionKey = conf["Encryption:Key"] ?? "a-default-fallback-only-for-dev-key-change-this!";
        EncryptionHelper.Initialize(encryptionKey);
        _ = conf["JWT:Secret"] ?? conf["JWT__Secret"] ?? throw new InvalidOperationException("JWT__Secret is not configured.");
        
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
        
        var mongoClient =
            new MongoClient(builder.Configuration.GetValue<string>("Mongo") ?? "mongodb://curiosity:27018");
        builder.Services.AddSingleton<IMongoClient>(mongoClient);

        builder.Services.AddDbContext<AppDbContext>((_, options) =>
        {
            options.UseMongoDB(mongoClient, "spmega");
        });

        var otlpEndpoint = (conf["Otlp__Endpoint"] ?? "http://curiosity:5080/api/default").TrimEnd('/');
        var otlpHeaders = conf["Otlp__Headers"] ?? throw new InvalidOperationException("Otlp__Headers is not configured.");
        var tracesEndpoint = new Uri($"{otlpEndpoint}/v1/traces");
        var metricsEndpoint = new Uri($"{otlpEndpoint}/v1/metrics");
        builder.Services.AddOpenTelemetry()
            .ConfigureResource(res => res.AddService("SpMega.Backend"))
            .WithTracing(tracing =>
            {
                tracing
                    .AddSource("SpMega.ModTelemetry")
                    .AddSource("SpMega.RequestTelemetry")
                    .AddAspNetCoreInstrumentation()
                    .AddOtlpExporter(opts =>
                    {
                        opts.Protocol = OtlpExportProtocol.HttpProtobuf;
                        opts.Endpoint = tracesEndpoint;
                        opts.Headers = otlpHeaders;
                    });
            })
            .WithMetrics(metrics => metrics
                .AddAspNetCoreInstrumentation()
                .AddMeter(
                    "Microsoft.AspNetCore.Hosting",
                    "Microsoft.AspNetCore.Server.Kestrel",
                    "System.Net.Http",
                    "System.Runtime")
                .AddOtlpExporter(opts =>
                {
                    opts.Protocol = OtlpExportProtocol.HttpProtobuf;
                    opts.Endpoint = metricsEndpoint;
                    opts.Headers = otlpHeaders;
                }));
    
        var app = builder.Build();

        app.UseMiddleware<RequestTelemetryMiddleware>();
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
        
        var result = new StringBuilder(length);

        for (var i = 0; i < length; i++)
        {
            var index = RandomNumberGenerator.GetInt32(chars.Length);
            result.Append(chars[index]);
        }

        return result.ToString();
    }
}
