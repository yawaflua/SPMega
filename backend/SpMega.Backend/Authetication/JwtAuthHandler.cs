using System.Security.Claims;
using System.Text.Encodings.Web;
using Microsoft.AspNetCore.Authentication;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;
using SpMega.Backend.Persistent.Database;
using SpMega.Backend.Services;

namespace SpMega.Backend.Authetication;

public class JwtAuthHandler(
    IOptionsMonitor<JwtAuthenticationSchemeOptions> options,
    ILoggerFactory logger,
    UrlEncoder encoder,
    AppDbContext dbContext,
    TokenService tokenService) : AuthenticationHandler<JwtAuthenticationSchemeOptions>(options, logger, encoder)
{
    protected override async Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        var authorization = Request.Headers.Authorization.ToString();
        if (string.IsNullOrWhiteSpace(authorization))
        {
            return AuthenticateResult.NoResult();
        }

        const string bearerPrefix = "Bearer ";
        if (!authorization.StartsWith(bearerPrefix, StringComparison.OrdinalIgnoreCase))
        {
            return AuthenticateResult.Fail("Invalid authorization header format.");
        }

        var token = authorization[bearerPrefix.Length..].Trim();
        if (string.IsNullOrWhiteSpace(token))
        {
            return AuthenticateResult.Fail("Invalid authorization header format.");
        }

        try
        {
            var validatedToken = tokenService.ValidateAccessToken(token);
            var user = await dbContext.Users.FirstOrDefaultAsync(
                candidate => candidate.Id == validatedToken.UserId && !candidate.IsDeleted,
                Context.RequestAborted);

            if (user == null || !string.Equals(user.Username, validatedToken.UserName, StringComparison.Ordinal))
            {
                return AuthenticateResult.Fail("Invalid token subject.");
            }

            var claims = new List<Claim>
            {
                new(ClaimTypes.NameIdentifier, user.Id.ToString()),
                new(ClaimTypes.Name, user.Username),
                new("UserId", user.Id.ToString()),
                new(ClaimTypes.Role, "default")
            };

            var principal = new ClaimsPrincipal(new ClaimsIdentity(claims, Scheme.Name));
            Context.Items["User"] = user;
            Context.Items["@me"] = user;
            Context.Items["user"] = user;
            Context.Items[TokenService.HttpContextItemKey] = validatedToken;

            return AuthenticateResult.Success(new AuthenticationTicket(principal, Scheme.Name));
        }
        catch (SecurityTokenException exception)
        {
            Logger.LogWarning("JWT authentication rejected: {Reason}", exception.Message);
            return AuthenticateResult.Fail("Invalid or expired token.");
        }
        catch (Exception exception)
        {
            Logger.LogError(exception, "Error during JWT authentication");
            return AuthenticateResult.Fail("Authentication error occurred.");
        }
    }

    protected override Task HandleChallengeAsync(AuthenticationProperties properties)
    {
        Response.StatusCode = StatusCodes.Status401Unauthorized;
        Response.ContentType = "application/json";
        Response.Headers.WWWAuthenticate = $"{Scheme.Name} error=\"invalid_token\"";
        Response.Headers["X-SPMega-Reauthenticate"] = "true";
        return Response.WriteAsJsonAsync(new
        {
            code = "reauthentication_required",
            message = "The access token is missing, invalid, expired, or older than two days."
        });
    }
}
