package git.yawaflua.tech.spmega.client.telemetry;

import java.net.URI;
import java.util.regex.Pattern;

public final class TelemetryUriSanitizer {
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(token|key|secret|password|auth|bearer)=[^&]+", Pattern.CASE_INSENSITIVE);

    private TelemetryUriSanitizer() {
    }

    public static String sanitize(URI uri) {
        if (uri == null) return "<null>";
        String path = uri.getPath() != null ? uri.getPath() : "";
        path = UUID_PATTERN.matcher(path).replaceAll("<uuid>");

        String query = uri.getQuery();
        if (query != null) {
            query = TOKEN_PATTERN.matcher(query).replaceAll("$1=<redacted>");
            return uri.getHost() + path + "?" + query;
        }
        return uri.getHost() + path;
    }

    public static String sanitize(String url) {
        try {
            return sanitize(URI.create(url));
        } catch (Exception e) {
            return "<invalid-url>";
        }
    }

    public static String classifyTarget(URI uri) {
        if (uri == null) return "unknown";
        String host = uri.getHost();
        if (host == null) return "unknown";
        if (host.contains("spworlds.ru")) return "spworlds";
        if (host.contains("spmega") || host.contains("ywfl.dev") || host.contains("yawaflua")) return "spmega-backend";
        if (host.contains("mojang.com") || host.contains("minecraft.net")) return "mojang";
        if (host.contains("sp-mini.ru") || host.contains("spmap")) return "gps-map";
        if (host.contains("ipify.org")) return "ipify";
        return "other";
    }
}
