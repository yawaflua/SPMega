package git.yawaflua.tech.spmega.client.ui;

import git.yawaflua.tech.spmega.client.ui.service.BackendAuthenticator;
import git.yawaflua.tech.spmega.client.ui.service.BankDatabase.LocalTransaction;
import git.yawaflua.tech.spmega.client.ui.service.BankUiService;
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

        loadTransactions();
    }

    private void loadTransactions() {
        loading = true;
        errorMessage = "";
        transactions.clear();

        CompletableFuture.runAsync(() -> {
            try {
                List<LocalTransaction> list = null;
                git.yawaflua.tech.spmega.ModConfig config = git.yawaflua.tech.spmega.SPMega.getConfig();
                if (config != null && config.allowBackend()) {
                    try {
                        list = BackendAuthenticator.fetchTransactionsFromBackend(cardId);
                    } catch (Exception e) {
                        System.err.println("[SPMEGA] Failed to fetch transactions from server, falling back to DB: " + e.getMessage());
                    }
                }

                if (list == null) {
                    list = BankUiService.instance().getDatabase().loadTransferHistory(cardId);
                }

                List<LocalTransaction> finalList = list;
                if (this.client != null) {
                    this.client.execute(() -> {
                        this.transactions.addAll(finalList);
                        this.loading = false;
                    });
                }
            } catch (Exception e) {
                if (this.client != null) {
                    this.client.execute(() -> {
                        this.errorMessage = e.getMessage();
                        this.loading = false;
                    });
                }
            }
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

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, 20, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Карта: " + cardTitle), centerX, 35, 0xBFBFBF);

        if (loading) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Загрузка транзакций..."), centerX, this.height / 2 - 10, 0xCCCCCC);
        } else if (!errorMessage.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Ошибка: " + errorMessage), centerX, this.height / 2 - 10, 0xFF5555);
        } else if (transactions.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Транзакций не найдено"), centerX, this.height / 2 - 10, 0xCCCCCC);
        } else {
            int y = startY + 15;
            for (int i = 0; i < Math.min(6, transactions.size()); i++) {
                LocalTransaction tx = transactions.get(i);

                String amountText = tx.amount() + " АР";
                String dateText = tx.createdAt();
                if (dateText.length() > 19) {
                    dateText = dateText.substring(0, 19).replace("T", " ");
                }
                String commentText = tx.comment().isEmpty() ? "" : " (" + tx.comment() + ")";

                String line = String.format("%s -> %s | %s%s", dateText, tx.receiver(), amountText, commentText);
                int color = tx.status().equalsIgnoreCase("SUCCESS") ? 0x55FF55 : 0xFF5555;

                context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(line), centerX, y, color);
                y += 18;
            }
        }
    }
}
