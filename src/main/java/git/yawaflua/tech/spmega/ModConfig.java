package git.yawaflua.tech.spmega;

public record ModConfig(String apiDomain, String apiToken, boolean allowBackend, boolean signQuickPayEnabled,
                        boolean gpsEnabled, GpsHudPosition gpsPosition) {
    public static final String DEFAULT_API_DOMAIN = "http://localhost:5129";
    public static final boolean ALLOW_BACKEND = true;
    public static final String DEFAULT_API_TOKEN = "-";
    public static final boolean DEFAULT_SIGN_QUICK_PAY_ENABLED = true;
    public static final boolean DEFAULT_GPS_ENABLED = true;
    public static final GpsHudPosition DEFAULT_GPS_POSITION = GpsHudPosition.TOP_CENTER;

    public static ModConfig createDefault() {
        return new ModConfig(
                DEFAULT_API_DOMAIN,
                DEFAULT_API_TOKEN,
                ALLOW_BACKEND,
                DEFAULT_SIGN_QUICK_PAY_ENABLED,
                DEFAULT_GPS_ENABLED,
                DEFAULT_GPS_POSITION
        );
    }
}


