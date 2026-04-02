package git.yawaflua.tech.spmega;

public record ModConfig(String apiDomain, String apiToken, boolean signQuickPayEnabled) {
    public static final String DEFAULT_API_DOMAIN = "https://spworlds.ru";
    public static final String DEFAULT_API_TOKEN = "ulBKE9MWEtIGiPAhXV69I28W9BRiSrV3";
    public static final boolean DEFAULT_SIGN_QUICK_PAY_ENABLED = true;

    public static ModConfig createDefault() {
        return new ModConfig(
                DEFAULT_API_DOMAIN,
                DEFAULT_API_TOKEN,
                DEFAULT_SIGN_QUICK_PAY_ENABLED
        );
    }
}

