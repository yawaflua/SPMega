using Microsoft.AspNetCore.Authentication;

namespace SpMega.Backend.Authetication;

public class JwtAuthenticationSchemeOptions : AuthenticationSchemeOptions
{
    public const string DefaultScheme = "JwtScheme";
}