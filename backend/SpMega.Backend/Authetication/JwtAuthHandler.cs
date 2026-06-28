using System.Security.Claims;
using System.Text.Encodings.Web;
using Microsoft.AspNetCore.Authentication;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using SpMega.Backend.Persistent.Database;

namespace SpMega.Backend.Authetication;

public class JwtAuthHandler(
    IOptionsMonitor<JwtAuthenticationSchemeOptions> options,
    ILoggerFactory logger,
    UrlEncoder encoder,
    AppDbContext dbContext) : AuthenticationHandler<JwtAuthenticationSchemeOptions>(options, logger, encoder)
{

    protected override async Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        if (!Request.Headers.ContainsKey("Authorization") || !Request.Headers.Authorization.ToString().StartsWith("Bearer "))
            return AuthenticateResult.NoResult();

        var token = Request.Headers.Authorization.ToString().Replace("Bearer ", "");

        if (string.IsNullOrEmpty(token))
        {
            return AuthenticateResult.Fail("Invalid authorization header format");
        }

        try
        {
            var user = await dbContext.Users.FirstOrDefaultAsync(k => k.Token == token);

            if (user == null)
            {
                return AuthenticateResult.Fail("Invalid or expired token");
            }

            var claims = new List<Claim>
            {
                new(ClaimTypes.NameIdentifier, user.Id.ToString()),
                new(ClaimTypes.Name, user.Username),
                new("UserId", user.Id.ToString()),
                new("Token", token),
                new(ClaimTypes.Role, "default")
            };

            var claimsIdentity = new ClaimsIdentity(claims, Scheme.Name);
            var claimsPrincipal = new ClaimsPrincipal(claimsIdentity);

            Context.Items["User"] = user;
            Context.Items["@me"] = user;
            Context.Items["user"] = user;

            var ticket = new AuthenticationTicket(claimsPrincipal, Scheme.Name);

            return AuthenticateResult.Success(ticket);
        }
        catch (Exception ex)
        {
            Logger.LogError(ex, "Error during JWT authentication");
            return AuthenticateResult.Fail("Authentication error occurred");
        }
    }
}
