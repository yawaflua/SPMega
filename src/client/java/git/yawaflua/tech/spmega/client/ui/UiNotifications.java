package git.yawaflua.tech.spmega.client.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import git.yawaflua.tech.spmega.GpsHudPosition;
import git.yawaflua.tech.spmega.SPMega;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.awt.*;
import java.util.ArrayDeque;
import java.util.Queue;

public final class UiNotifications {
    private static final int DEFAULT_DURATION_TICKS = 70;
    private static final UiNotifications INSTANCE = new UiNotifications();

    private Text currentText = Text.empty();
    private final Queue<Text> queuedTexts = new ArrayDeque<>();
    private int remainingTicks;

    private UiNotifications() {
    }

    public static UiNotifications instance() {
        return INSTANCE;
    }

    public static String extractMessage(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        try {
            var reader = new JsonReader(new java.io.StringReader(raw));
            reader.setStrictness(Strictness.LENIENT);

            JsonElement parsed = JsonParser.parseReader(reader);
            JsonObject object = parsed.isJsonArray() ? parsed.getAsJsonArray().get(0).getAsJsonObject() : parsed.getAsJsonObject();
            if (object.has("message") && !object.get("message").isJsonNull()) {
                return object.get("message").getAsString();
            }

        } catch (Exception ignored) {
            // fallback to raw text
        }

        return raw;
    }

    public synchronized void show(Text text) {
        if (text == null || text.getString().isBlank()) {
            return;
        }
        currentText = text;
        remainingTicks = DEFAULT_DURATION_TICKS;
    }

    public synchronized void showMessage(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        show(Text.literal(extractMessage(message)));
    }

    public synchronized void showQueued(Text text) {
        if (text == null || text.getString().isBlank()) {
            return;
        }
        if (!isVisible()) {
            show(text);
        } else {
            queuedTexts.add(text);
        }
    }

    public synchronized void render(DrawContext context, TextRenderer textRenderer, int width, int height) {
        if (!isVisible()) {
            return;
        }

        String message = currentText.getString();
        int padding = 6;
        int textWidth = textRenderer.getWidth(message);
        int boxWidth = textWidth + padding * 2;
        int boxHeight = textRenderer.fontHeight + padding * 2;
        GpsHudPosition position = SPMega.getConfig() == null
                ? GpsHudPosition.BOTTOM_RIGHT
                : SPMega.getConfig().notificationPosition();
        int x = switch (position) {
            case TOP_LEFT, BOTTOM_LEFT -> 10;
            case TOP_CENTER, BOTTOM_CENTER -> (width - boxWidth) / 2;
            case TOP_RIGHT, BOTTOM_RIGHT -> width - boxWidth - 10;
        };
        int y = switch (position) {
            case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> 10;
            case BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT -> height - boxHeight - 10;
        };

        context.fill(x, y, x + boxWidth, y + boxHeight, Color.DARK_GRAY.getRGB());
        context.drawTextWithShadow(textRenderer, currentText, x + padding, y + padding, Color.WHITE.getRGB());
    }

    public synchronized void tick() {
        if (remainingTicks <= 0) {
            return;
        }
        remainingTicks--;
        if (remainingTicks <= 0) {
            currentText = queuedTexts.isEmpty() ? Text.empty() : queuedTexts.remove();
            remainingTicks = currentText.getString().isBlank() ? 0 : DEFAULT_DURATION_TICKS;
        }
    }

    public synchronized boolean isVisible() {
        return currentText != null && !currentText.getString().isBlank() && remainingTicks > 0;
    }
}

