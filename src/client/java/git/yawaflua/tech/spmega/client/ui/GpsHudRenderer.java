package git.yawaflua.tech.spmega.client.ui;

import com.google.gson.Gson;
import git.yawaflua.tech.spmega.GpsHudPosition;
import git.yawaflua.tech.spmega.SPMega;
import git.yawaflua.tech.spmega.client.telemetry.InstrumentedHttpClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.world.World;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class GpsHudRenderer {
    private static final GpsHudRenderer INSTANCE = new GpsHudRenderer();
    private boolean enabled = true;
    private volatile List<City> cities = List.of();

    private GpsHudRenderer() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "SPMega-City-Poller");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(this::fetchCities, 0, 5, TimeUnit.HOURS);
    }

    public static GpsHudRenderer instance() {
        return INSTANCE;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void toggle() {
        this.enabled = !this.enabled;
    }

    private void fetchCities() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://map.sp-mini.ru/api/map/territories"))
                .GET()
                .build();

        new InstrumentedHttpClient().sendAsync(request)
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        return;
                    }
                    TerritoryWrapper[] parsed = new Gson().fromJson(response.body(), TerritoryWrapper[].class);
                    if (parsed == null) {
                        return;
                    }
                    List<City> updatedCities = new ArrayList<>();
                    for (TerritoryWrapper wrapper : parsed) {
                        if (wrapper != null && wrapper.territory != null
                                && wrapper.territory.nether_portal != null
                                && wrapper.territory.nether_portal.length >= 2) {
                            City city = new City();
                            city.name = wrapper.territory.name;
                            city.netherX = wrapper.territory.nether_portal[0];
                            city.netherZ = wrapper.territory.nether_portal[1];
                            updatedCities.add(city);
                        }
                    }
                    cities = List.copyOf(updatedCities);
                })
                .exceptionally(ignored -> null);
    }

    public void render(DrawContext context, TextRenderer textRenderer, int width, int height) {
        if (!enabled) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) {
            return;
        }

        if (client.world.getRegistryKey() != World.NETHER) {
            return;
        }

        double playerX = client.player.getX();
        double playerZ = client.player.getZ();

        LaneInfo playerLane = new LaneInfo(Math.round(playerX), Math.round(playerZ));

        String titleText = "§7Ветка: " + playerLane.name + " §7(§f" + playerLane.coord + "§7)";
        String offsetText = "§7Смещение: §f" + (playerLane.offset == 0 ? "0" : (playerLane.offset > 0 ? "+" + playerLane.offset : playerLane.offset));

        City nearestCity = null;
        double minDistanceSquared = Double.MAX_VALUE;
        for (City city : cities) {
            double dx = playerX - (city.netherX / 8);
            double dz = playerZ - (city.netherZ / 8);
            double distanceSquared = dx * dx + dz * dz;
            if (distanceSquared < minDistanceSquared) {
                minDistanceSquared = distanceSquared;
                nearestCity = city;
            }
        }

        String cityText = null;
        if (nearestCity != null) {
            long cx = Math.round(nearestCity.netherX / 8.0);
            long cz = Math.round(nearestCity.netherZ / 8.0);
            LaneInfo cityLane = new LaneInfo(cx, cz);
            cityText = "§7Ближайший: §f" + nearestCity.name + " §7~(§f" + cityLane.name + " §f" + cityLane.coord + "§7, §7см: §f" + (cityLane.offset == 0 ? "0" : (cityLane.offset > 0 ? "+" + cityLane.offset : cityLane.offset)) + "§7)";
        }

        int padding = 6;
        int lineSpacing = 2;
        int textWidth = Math.max(textRenderer.getWidth(Text.literal(titleText)), textRenderer.getWidth(Text.literal(offsetText)));
        if (cityText != null) {
            textWidth = Math.max(textWidth, textRenderer.getWidth(Text.literal(cityText)));
        }

        int boxWidth = textWidth + padding * 2 + 6;
        int linesCount = cityText != null ? 3 : 2;
        int boxHeight = textRenderer.fontHeight * linesCount + lineSpacing * (linesCount - 1) + padding * 2;

        GpsHudPosition position = GpsHudPosition.TOP_CENTER;
        if (SPMega.getConfig() != null) {
            position = SPMega.getConfig().gpsPosition();
        }

        int boxX;
        int boxY;

        switch (position) {
            case TOP_RIGHT:
                boxX = width - boxWidth - 10;
                boxY = 10;
                break;
            case BOTTOM_LEFT:
                boxX = 10;
                boxY = height - boxHeight - 10;
                break;
            case BOTTOM_RIGHT:
                boxX = width - boxWidth - 10;
                boxY = height - boxHeight - 10;
                break;
            case TOP_CENTER:
                boxX = (width - boxWidth) / 2;
                boxY = 10;
                break;
            case BOTTOM_CENTER:
                boxX = (width - boxWidth) / 2;
                boxY = height - boxHeight - 10;
                break;
            case TOP_LEFT:
            default:
                boxX = 10;
                boxY = 10;
                break;
        }

        int bgCol = 0x90101010;
        context.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, bgCol);

        int barWidth = 3;
        context.fill(boxX + padding, boxY + padding, boxX + padding + barWidth, boxY + boxHeight - padding, playerLane.color);

        int textX = boxX + padding + barWidth + 4;
        int textY = boxY + padding;
        context.drawTextWithShadow(textRenderer, Text.literal(titleText), textX, textY, 0xFFFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.literal(offsetText), textX, textY + textRenderer.fontHeight + lineSpacing, 0xFFFFFFFF);
        if (cityText != null) {
            context.drawTextWithShadow(textRenderer, Text.literal(cityText), textX, textY + (textRenderer.fontHeight + lineSpacing) * 2, 0xFFFFFFFF);
        }
    }

    private static class City {
        String name;
        int netherX;
        int netherZ;
    }

    private static class TerritoryWrapper {
        Territory territory;
    }

    private static class Territory {
        String name;
        int[] nether_portal;
    }

    private static class LaneInfo {
        String name;
        int color;
        long coord;
        long offset;

        LaneInfo(long x, long z) {
            long absX = Math.abs(x);
            long absZ = Math.abs(z);
            if (absX > absZ) {
                if (x > 0) {
                    name = "§aЗеленая";
                    color = 0xFF55FF55;
                    coord = x;
                    offset = z;
                } else {
                    name = "§9Синяя";
                    color = 0xFF5555FF;
                    coord = absX;
                    offset = z;
                }
            } else {
                if (z < 0) {
                    name = "§cКрасная";
                    color = 0xFFFF5555;
                    coord = absZ;
                    offset = x;
                } else {
                    name = "§eЖелтая";
                    color = 0xFFFFFF55;
                    coord = absZ;
                    offset = x;
                }
            }
        }
    }
}
