package git.yawaflua.tech.spmega.client.ui;

import git.yawaflua.tech.spmega.client.ui.service.BackendAuthenticator;
import git.yawaflua.tech.spmega.client.ui.service.BankDatabase.LocalTransaction;
import git.yawaflua.tech.spmega.client.ui.service.BankUiService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class TransactionHistoryScreen extends Screen {
    private final Screen parent;
    private final String cardId;
    private final String cardTitle;
    private final List<LocalTransaction> transactions = new ArrayList<>();
    private boolean loading = true;
    private String errorMessage = "";
    private int scrollOffset = 0;
    private int currentPage = 1;

    public TransactionHistoryScreen(Screen parent, String cardId, String cardTitle) {
        super(Text.literal("История транзакций"));
        this.parent = parent;
        this.cardId = cardId;
        this.cardTitle = cardTitle;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int startY = this.height / 2 + 50;

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Назад"), button -> this.close())
                .dimensions(centerX - 60, startY + 10, 120, 20)
                .build());

        // Scroll Up/Down buttons
        this.addDrawableChild(ButtonWidget.builder(Text.literal("▲"), button -> scrollUp())
                .dimensions(centerX + 140, 65, 20, 20)
                .build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("▼"), button -> scrollDown())
                .dimensions(centerX + 140, 155, 20, 20)
                .build());

        // Prev Page button
        this.addDrawableChild(ButtonWidget.builder(Text.literal("<"), button -> {
            if (currentPage > 1) {
                currentPage--;
                scrollOffset = 0;
                loadTransactions();
            }
        }).dimensions(centerX - 100, startY - 15, 30, 20).build());

        // Next Page button
        this.addDrawableChild(ButtonWidget.builder(Text.literal(">"), button -> {
            currentPage++;
            scrollOffset = 0;
            loadTransactions();
        }).dimensions(centerX + 70, startY - 15, 30, 20).build());

        loadTransactions();
    }

    private void scrollUp() {
        if (scrollOffset > 0) {
            scrollOffset--;
        }
    }

    private void scrollDown() {
        if (scrollOffset < Math.max(0, transactions.size() - 6)) {
            scrollOffset++;
        }
    }

    // Support pre-1.20.2 mouse scroll
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (amount > 0) {
            scrollUp();
        } else if (amount < 0) {
            scrollDown();
        }
        return true;
    }

    // Support 1.20.2+ mouse scroll
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (verticalAmount > 0) {
            scrollUp();
        } else if (verticalAmount < 0) {
            scrollDown();
        }
        return true;
    }

    private void loadTransactions() {
        loading = true;
        errorMessage = "";
        transactions.clear();

        UiNotifications.instance().show(Text.literal("Загрузка..."));

        int page = currentPage;
        System.out.println("[SPMEGA] Transaction history loading started for card: " + cardId + ", page: " + page);
        git.yawaflua.tech.spmega.ModConfig config = git.yawaflua.tech.spmega.SPMega.getConfig();
        CompletableFuture<List<LocalTransaction>> remote = config != null && config.allowBackend()
                ? BackendAuthenticator.fetchTransactionsFromBackendAsync(cardId, page)
                  .whenComplete((list, exception) -> {
                      if (exception == null) {
                          System.out.println("[SPMEGA] Fetched " + list.size() + " transactions from backend.");
                      } else {
                          System.err.println("[SPMEGA] Failed to fetch transactions from server, falling back to DB: "
                                             + exception.getMessage());
                      }
                  })
                  .exceptionally(exception -> null)
                : CompletableFuture.completedFuture(null);

        remote.thenApply(list -> {
                    if (list != null) {
                        return list;
                    }
                    List<LocalTransaction> local = BankUiService.instance().getDatabase().loadTransferHistory(cardId);
                    System.out.println("[SPMEGA] Loaded " + local.size() + " transactions from database.");
                    return local;
                })
                .whenComplete((list, exception) -> {
                    MinecraftClient minecraftClient = MinecraftClient.getInstance();
                    if (minecraftClient == null) {
                        System.out.println("[SPMEGA] MinecraftClient.getInstance() is null in loadTransactions callback!");
                        return;
                    }
                    minecraftClient.execute(() -> {
                        if (exception != null) {
                            errorMessage = exception.getMessage();
                        } else {
                            System.out.println("[SPMEGA] Setting transactions list on main thread. Count: " + list.size());
                            transactions.addAll(list);
                            scrollOffset = 0;
                        }
                        loading = false;
                    });
                });
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        int startY = 50;
        int bottomStartY = this.height / 2 + 50;

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, 20, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Карта: " + cardTitle), centerX, 35, 0xFFBFBFBF);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Стр. " + currentPage), centerX, bottomStartY - 9, 0xFFFFFFFF);

        if (loading) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Загрузка транзакций..."), centerX, this.height / 2 - 10, 0xFFCCCCCC);
        } else if (!errorMessage.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Ошибка: " + errorMessage), centerX, this.height / 2 - 10, 0xFFFF5555);
        } else if (transactions.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Транзакций не найдено"), centerX, this.height / 2 - 10, 0xFFCCCCCC);
        } else {
            int y = startY + 15;
            int limit = Math.min(6, transactions.size() - scrollOffset);
            for (int i = 0; i < limit; i++) {
                LocalTransaction tx = transactions.get(scrollOffset + i);

                String amountText = tx.amount() + " АР";
                String dateText = tx.createdAt();
                if (dateText == null) {
                    dateText = "";
                }
                if (dateText.length() > 19) {
                    dateText = dateText.substring(0, 19).replace("T", " ");
                }
                String commentText = tx.comment().isEmpty() ? "" : " (" + tx.comment() + ")";

                String line = String.format("%s -> %s | %s%s", dateText, tx.receiver(), amountText, commentText);

                String status = tx.status();
                int color = (status != null && status.equalsIgnoreCase("SUCCESS")) ? 0xFF55FF55 : 0xFFFF5555;

                context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(line), centerX, y, color);
                y += 18;
            }
        }
    }
}
