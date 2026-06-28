package git.yawaflua.tech.spmega;

public enum GpsHudPosition {
    TOP_LEFT("Слева вверху"),
    TOP_RIGHT("Справа вверху"),
    BOTTOM_LEFT("Слева внизу"),
    BOTTOM_RIGHT("Справа внизу"),

    TOP_CENTER("Сверху по-центру"),
    BOTTOM_CENTER("Снизу по-центру");

    private final String displayName;

    GpsHudPosition(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
