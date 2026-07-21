package git.yawaflua.tech.spmega.client.qr;

import com.google.zxing.*;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import git.yawaflua.tech.spmega.client.telemetry.TelemetryCollector;
import git.yawaflua.tech.spmega.client.telemetry.TelemetryEvent;
import git.yawaflua.tech.spmega.client.ui.QRcodeAcceptScreen;
import git.yawaflua.tech.spmega.client.ui.UiNotifications;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;

public final class QRCodeScanner {
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SPMega-QR-Decoder");
        thread.setDaemon(true);
        return thread;
    });

    private QRCodeScanner() {
    }

    public static void scanQrCode(Minecraft client) {
        if (client == null || client.player == null || client.gameRenderer.mainRenderTarget() == null) {
            return;
        }
        UiNotifications notifications = UiNotifications.instance();
        int framebufferWidth = client.getWindow().getWidth();
        int framebufferHeight = client.getWindow().getHeight();
        if (framebufferWidth <= 0 || framebufferHeight <= 0) {
            /*? if mc_26 {*/
             client.player.sendSystemMessage(Component.translatable("message.spmega.qr.capture_failed"));
            /*?} else {*/
            /*client.player.displayClientMessage(Component.translatable("message.spmega.qr.capture_failed"), false);
            *//*?}*/
            return;
        }

        long startNs = System.nanoTime();
        try {
            /*? if mc_1_21_11 {*/
            Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), nativeImage -> {
                processCapturedImage(client, notifications, nativeImage, startNs);
            });
            /*?} else {*/
            // processCapturedImage(client, notifications, Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget()), startNs);
            /*?}*/
        } catch (Exception ex) {
            notifications.show(Component.translatable("message.spmega.qr.error"));
            recordQrEvent(false, false, null, ex.getClass().getSimpleName() + ": " + ex.getMessage(), startNs);
        }
    }

    private static void processCapturedImage(
            Minecraft client,
            UiNotifications notifications,
            NativeImage nativeImage,
            long startNs
    ) {
        try {
            if (nativeImage == null || nativeImage.getWidth() <= 0 || nativeImage.getHeight() <= 0) {
                client.execute(() -> {
                    if (client.player != null) {
                        notifications.show(Component.translatable("message.spmega.qr.capture_failed"));
                    }
                });
                recordQrEvent(false, false, null, "capture_failed", startNs);
                return;
            }

            int width = nativeImage.getWidth();
            int height = nativeImage.getHeight();
            /*? if mc_1_21_11 {*/
            int[] pixels = nativeImage.getPixels();
            /*?} else {*/
            // int[] pixels = nativeImage.getPixelsRGBA();
            /*?}*/
            CompletableFuture.supplyAsync(() -> decodeQrCode(width, height, pixels), DECODER)
                    .whenComplete((result, throwable) -> {
                        client.execute(() -> showResult(client, notifications, result, throwable));
                        recordQrEvent(
                                throwable == null && result != null,
                                System.nanoTime() - startNs > 50_000_000L,
                                result,
                                throwable == null ? null : throwable.getClass().getSimpleName() + ": " + throwable.getMessage(),
                                startNs
                        );
                    });
        } catch (Exception exception) {
            client.execute(() -> notifications.show(Component.translatable("message.spmega.qr.error")));
            recordQrEvent(false, false, null,
                    exception.getClass().getSimpleName() + ": " + exception.getMessage(), startNs);
        } finally {
            if (nativeImage != null) {
                nativeImage.close();
            }
        }
    }

    private static void showResult(
            Minecraft client,
            UiNotifications notifications,
            String result,
            Throwable throwable
    ) {
        if (client.player == null) {
            return;
        }
        if (throwable != null) {
            notifications.show(Component.translatable("message.spmega.qr.error"));
            return;
        }
        if (result == null) {
            notifications.show(Component.translatable("message.spmega.qr.not_found"));
            return;
        }

        Component clickableLink = Component.literal(result).withStyle(style -> style.withInsertion(result));
        /*? if mc_26 {*/
         client.player.sendSystemMessage(Component.translatable("message.spmega.qr.found_link", clickableLink));
        /*?} else {*/
        /*client.player.displayClientMessage(Component.translatable("message.spmega.qr.found_link", clickableLink), false);
        *//*?}*/
        client.gui.setScreen(new QRcodeAcceptScreen(result, client.gui.screen()));
    }

    private static void recordQrEvent(boolean success, boolean didLag, String decodedUrl, String error, long startNs) {
        com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
        payload.addProperty("success", success);
        payload.addProperty("didLag", didLag);
        payload.addProperty("durationMs", (System.nanoTime() - startNs) / 1_000_000L);
        if (decodedUrl != null) {
            payload.addProperty("decodedUrl", decodedUrl);
        }
        if (error != null) {
            payload.addProperty("error", error);
        }
        TelemetryCollector.instance().record(TelemetryEvent.now("qr_scan", payload));
    }

    private static String decodeQrCode(int width, int height, int[] pixels) {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(DecodeHintType.ALSO_INVERTED, Boolean.TRUE);

        LuminanceSource source = new RGBLuminanceSource(width, height, pixels);

        String result = tryDecodeWithStrategies(source, hints);

        if (result == null) {
            hints.put(DecodeHintType.PURE_BARCODE, Boolean.TRUE);
            result = tryDecodeWithStrategies(source, hints);
        }

        return result;
    }

    private static String tryDecodeWithStrategies(LuminanceSource source, Map<DecodeHintType, Object> hints) {
        try {
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            return new MultiFormatReader().decode(bitmap, hints).getText();
        } catch (NotFoundException e) {
        }

        try {
            BinaryBitmap bitmap = new BinaryBitmap(new GlobalHistogramBinarizer(source));
            return new MultiFormatReader().decode(bitmap, hints).getText();
        } catch (NotFoundException e) {
        }
        try {
            GenericMultipleBarcodeReader reader = new GenericMultipleBarcodeReader(new MultiFormatReader());
            Result[] results = reader.decodeMultiple(new BinaryBitmap(new HybridBinarizer(source)), hints);
            if (results.length > 0) {
                return results[0].getText();
            }
        } catch (NotFoundException e) {
        }

        return null;
    }
}
