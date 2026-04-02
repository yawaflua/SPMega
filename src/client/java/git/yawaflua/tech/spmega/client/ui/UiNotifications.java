package git.yawaflua.tech.spmega.client.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.awt.*;

public final class UiNotifications {
    private static final int DEFAULT_DURATION_TICKS = 70;
    private static final UiNotifications INSTANCE = new UiNotifications();

    private Text currentText = Text.empty();
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

    public synchronized void render(DrawContext context, TextRenderer textRenderer, int width, int height) {
        if (!isVisible()) {
            return;
        }

        String message = currentText.getString();
        int padding = 6;
        int textWidth = textRenderer.getWidth(message);
        int boxWidth = textWidth + padding * 2;
        int boxHeight = textRenderer.fontHeight + padding * 2;
        int x = width - boxWidth - 10;
        int y = height - boxHeight - 10;

        context.fill(x, y, x + boxWidth, y + boxHeight, Color.GRAY.getRGB());
        context.drawTextWithShadow(textRenderer, currentText, x + padding, y + padding, Color.WHITE.getRGB());
    }

    public synchronized void tick() {
        if (remainingTicks <= 0) {
            return;
        }
        remainingTicks--;
        if (remainingTicks <= 0) {
            currentText = Text.empty();
            remainingTicks = 0;
        }
    }

    public synchronized boolean isVisible() {
        return currentText != null && !currentText.getString().isBlank() && remainingTicks > 0;
    }
}

