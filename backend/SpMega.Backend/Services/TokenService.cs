using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Text;
using Microsoft.IdentityModel.Tokens;

namespace SpMega.Backend.Services;

public class TokenService(IConfiguration conf)
{
    private const int AccessTokenExpirationMinutes = 24;
    private readonly string _issuer = conf["JWT__Issuer"] ?? "spmega.il.yawaflua.tech";
    private readonly string _secret = conf["JWT__Secret"] ?? throw new InvalidOperationException("JWT__Secret is not configured.");

    public string GenerateAccessToken(string userName, Guid uuid)
    {
        var claims = new List<Claim>
        {
            new(JwtRegisteredClaimNames.Name, userName),
            new (JwtRegisteredClaimNames.UniqueName, uuid.ToString()),
            new(JwtRegisteredClaimNames.AuthTime, DateTimeOffset.UtcNow.ToUnixTimeSeconds().ToString()),
            new(JwtRegisteredClaimNames.Jti, Guid.NewGuid().ToString())
        };

        var tokenHandler = new JwtSecurityTokenHandler();
        var key = Encoding.ASCII.GetBytes(_secret);

        var tokenDescriptor = new SecurityTokenDescriptor
        {
            Subject = new ClaimsIdentity(claims),
            Expires = DateTime.UtcNow.AddHours(AccessTokenExpirationMinutes),
            Issuer = _issuer,
            SigningCredentials = new SigningCredentials(new SymmetricSecurityKey(key), SecurityAlgorithms.HmacSha256Signature),
            IssuedAt = DateTime.UtcNow
        };

        var token = tokenHandler.CreateToken(tokenDescriptor);

        return tokenHandler.WriteToken(token);
    }
}