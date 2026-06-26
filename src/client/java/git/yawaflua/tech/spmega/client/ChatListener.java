package git.yawaflua.tech.spmega.client;

import git.yawaflua.tech.spmega.client.ui.UiNotifications;
import git.yawaflua.tech.spmega.client.ui.service.BankUiService;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

public class ChatListener {
    private final UiNotifications notifications = UiNotifications.instance();
    private final Pattern TRANSACTION_PATTERN = Pattern.compile(
            ".*(Управление картой).*",
            Pattern.CASE_INSENSITIVE
    );

    public void register() {
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, timestamp) -> {
            try {
                handleMessage(message);
            } catch (Throwable t) {
                System.err.println("[SPMega] Ошибка обработки сообщения в CHAT листенере:");
                t.printStackTrace();
            }
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            try {
                handleMessage(message);
            } catch (Throwable t) {
                System.err.println("[SPMega] Ошибка обработки сообщения в GAME листенере:");
                t.printStackTrace();
            }
        });
    }

    private void handleMessage(Text message) {
        if (message == null) {
            return;
        }

        String text = message.getString();
        if (TRANSACTION_PATTERN.matcher(text).matches()) {
            List<String> clickValues = new ArrayList<>();
            collectClickEventValues(message, clickValues);

            if (clickValues.size() >= 2) {
                String tokenId = clickValues.get(0);
                String cardId = clickValues.get(1);

                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null) {
                    String playerUuid = client.player.getUuidAsString();

                    CompletableFuture
                            .supplyAsync(() -> BankUiService.instance().addCard(cardId, tokenId, playerUuid))
                            .thenAccept(msg -> {
                                if (client == null) {
                                    return;
                                }
                                client.execute(() -> {
                                    notifications.showMessage(msg);
                                });
                            })
                            .exceptionally(exception -> {
                                if (client != null) {
                                    client.execute(() -> {
                                        notifications.show(Text.literal("Ошибка добавления карты: " + exception.getMessage()));
                                    });
                                }
                                return null;
                            });
                }
            } else {
                System.out.println("[SPMega] Недостаточно кликабельных данных в сообщении (найдено: " + clickValues.size() + ")");
            }
        }
    }

    private void collectClickEventValues(Text text, List<String> values) {
        if (text == null) {
            return;
        }

        ClickEvent clickEvent = text.getStyle().getClickEvent();
        if (clickEvent != null) {
            try {
                String value = getEventValue(clickEvent);
                if (value != null && !value.isEmpty()) {
                    values.add(value);
                }
            } catch (Throwable t) {
                System.err.println("[SPMega] Ошибка извлечения значения клик-события:");
                t.printStackTrace();
            }
        }

        for (Text sibling : text.getSiblings()) {
            collectClickEventValues(sibling, values);
        }
    }

    private String getEventValue(ClickEvent clickEvent) {
        if (clickEvent instanceof ClickEvent.CopyToClipboard(String value)) {
            return value;
        } else if (clickEvent instanceof ClickEvent.RunCommand(String command1)) {
            return command1;
        } else if (clickEvent instanceof ClickEvent.SuggestCommand(String command)) {
            return command;
        } else if (clickEvent instanceof ClickEvent.OpenUrl(java.net.URI uri)) {
            return uri.toString();
        } else if (clickEvent instanceof ClickEvent.OpenFile(String path)) {
            return path;
        } else if (clickEvent instanceof ClickEvent.ChangePage(int page)) {
            return String.valueOf(page);
        }
        return "";
    }
}
