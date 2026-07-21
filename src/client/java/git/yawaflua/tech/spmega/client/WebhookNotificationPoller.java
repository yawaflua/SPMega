package git.yawaflua.tech.spmega.client;

import git.yawaflua.tech.spmega.client.ui.UiNotifications;
import git.yawaflua.tech.spmega.client.ui.service.BackendAuthenticator;
import git.yawaflua.tech.spmega.client.ui.service.BankUiService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class WebhookNotificationPoller {
    private static final WebhookNotificationPoller INSTANCE = new WebhookNotificationPoller();
    private static final long POLL_INTERVAL_SECONDS = 15;

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean requestInFlight = new AtomicBoolean();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SPMega-Webhook-Poller");
        thread.setDaemon(true);
        return thread;
    });

    private WebhookNotificationPoller() {
    }

    public static WebhookNotificationPoller instance() {
        return INSTANCE;
    }

    private static String format(BackendAuthenticator.PaymentNotification notification) {
        String sender = notification.senderName().isBlank()
                ? notification.senderNumber()
                : notification.senderName();
        String comment = notification.comment().isBlank() ? "" : " — " + notification.comment();
        return "Получено " + notification.amount() + " АР от " + sender + comment;
    }

    public void start() {
        if (started.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(this::poll, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void poll() {
        try {
            if (!BankUiService.instance().hasWebhookEnabledCards()
                    || !requestInFlight.compareAndSet(false, true)) {
                return;
            }
        } catch (Exception exception) {
            System.err.println("[SPMEGA] Failed to read local webhook state: " + exception.getMessage());
            return;
        }

        BackendAuthenticator.readNotificationsAsync().whenComplete((notifications, exception) -> {
            requestInFlight.set(false);
            if (exception != null) {
                System.err.println("[SPMEGA] Failed to poll webhook notifications: " + exception.getMessage());
                return;
            }
            Minecraft client = Minecraft.getInstance();
            if (client == null || notifications.isEmpty()) {
                return;
            }
            client.execute(() -> notifications.forEach(notification ->
                    UiNotifications.instance().showQueued(Component.literal(format(notification)))));
        });
    }
}
