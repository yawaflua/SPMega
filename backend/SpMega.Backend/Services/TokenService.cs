using System.Globalization;
using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Text;
using Microsoft.IdentityModel.Tokens;

namespace SpMega.Backend.Services;

public sealed record ValidatedAccessToken(
    Guid UserId,
    string UserName,
    DateTimeOffset AuthenticatedAt,
    DateTimeOffset ExpiresAt);

public class TokenService(IConfiguration conf)
{
    public const string HttpContextItemKey = "ValidatedAccessToken";
    public static readonly TimeSpan AccessTokenLifetime = TimeSpan.FromHours(24);
    public static readonly TimeSpan MaximumAuthenticationAge = TimeSpan.FromDays(2);
    public static readonly TimeSpan RefreshThreshold = TimeSpan.FromHours(1);

    private static readonly TimeSpan ClockSkew = TimeSpan.FromMinutes(1);
    private readonly string _issuer = conf["JWT:Issuer"] ?? conf["JWT__Issuer"] ?? "spmega.il.yawaflua.tech";
    private readonly string _secret = conf["JWT:Secret"] ?? conf["JWT__Secret"] ?? throw new InvalidOperationException("JWT__Secret is not configured.");

    public string GenerateAccessToken(string userName, Guid uuid)
    {
        return GenerateAccessToken(userName, uuid, DateTimeOffset.UtcNow);
    }

    public string GenerateAccessToken(string userName, Guid uuid, DateTimeOffset authenticatedAt)
    {
        var now = DateTimeOffset.UtcNow;
        var maximumExpiration = authenticatedAt.Add(MaximumAuthenticationAge);
        var expiration = now.Add(AccessTokenLifetime);
        if (expiration > maximumExpiration)
        {
            expiration = maximumExpiration;
        }

        if (expiration <= now)
        {
            throw new SecurityTokenExpiredException("The original authentication is older than two days.");
        }

        var claims = new List<Claim>
        {
            new(JwtRegisteredClaimNames.Name, userName),
            new(JwtRegisteredClaimNames.UniqueName, uuid.ToString()),
            new(JwtRegisteredClaimNames.AuthTime, authenticatedAt.ToUnixTimeSeconds().ToString(CultureInfo.InvariantCulture)),
            new(JwtRegisteredClaimNames.Jti, Guid.NewGuid().ToString())
        };

        var tokenHandler = new JwtSecurityTokenHandler { MapInboundClaims = false };
        var tokenDescriptor = new SecurityTokenDescriptor
        {
            Subject = new ClaimsIdentity(claims),
            Expires = expiration.UtcDateTime,
            Issuer = _issuer,
            SigningCredentials = new SigningCredentials(
                new SymmetricSecurityKey(Encoding.UTF8.GetBytes(_secret)),
                SecurityAlgorithms.HmacSha256),
            IssuedAt = now.UtcDateTime
        };

        return tokenHandler.WriteToken(tokenHandler.CreateToken(tokenDescriptor));
    }

    public ValidatedAccessToken ValidateAccessToken(string token)
    {
        var tokenHandler = new JwtSecurityTokenHandler { MapInboundClaims = false };
        var principal = tokenHandler.ValidateToken(token, new TokenValidationParameters
        {
            ValidateIssuer = true,
            ValidIssuer = _issuer,
            ValidateAudience = false,
            ValidateIssuerSigningKey = true,
            IssuerSigningKey = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(_secret)),
            ValidateLifetime = true,
            RequireExpirationTime = true,
            RequireSignedTokens = true,
            ClockSkew = ClockSkew,
            ValidAlgorithms = [SecurityAlgorithms.HmacSha256]
        }, out var validatedToken);

        if (validatedToken is not JwtSecurityToken jwtToken ||
            !string.Equals(jwtToken.Header.Alg, SecurityAlgorithms.HmacSha256, StringComparison.Ordinal))
        {
            throw new SecurityTokenValidationException("Unsupported JWT signing algorithm.");
        }

        var userName = principal.FindFirst(JwtRegisteredClaimNames.Name)?.Value;
        var uniqueName = principal.FindFirst(JwtRegisteredClaimNames.UniqueName)?.Value;
        var authTimeValue = principal.FindFirst(JwtRegisteredClaimNames.AuthTime)?.Value;
        var expirationValue = principal.FindFirst(JwtRegisteredClaimNames.Exp)?.Value;

        if (string.IsNullOrWhiteSpace(userName) ||
            !Guid.TryParse(uniqueName, out var userId) ||
            !long.TryParse(authTimeValue, NumberStyles.None, CultureInfo.InvariantCulture, out var authTimeSeconds) ||
            !long.TryParse(expirationValue, NumberStyles.None, CultureInfo.InvariantCulture, out var expirationSeconds))
        {
            throw new SecurityTokenValidationException("Required JWT claims are missing or invalid.");
        }

        DateTimeOffset authenticatedAt;
        DateTimeOffset expiresAt;
        try
        {
            authenticatedAt = DateTimeOffset.FromUnixTimeSeconds(authTimeSeconds);
            expiresAt = DateTimeOffset.FromUnixTimeSeconds(expirationSeconds);
        }
        catch (ArgumentOutOfRangeException exception)
        {
            throw new SecurityTokenValidationException("JWT timestamps are invalid.", exception);
        }

        var now = DateTimeOffset.UtcNow;
        if (authenticatedAt > now.Add(ClockSkew) || now - authenticatedAt > MaximumAuthenticationAge)
        {
            throw new SecurityTokenExpiredException("Reauthentication is required.");
        }

        return new ValidatedAccessToken(userId, userName, authenticatedAt, expiresAt);
    }
}
