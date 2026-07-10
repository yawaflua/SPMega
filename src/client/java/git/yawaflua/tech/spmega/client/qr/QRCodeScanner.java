package git.yawaflua.tech.spmega.client.qr;

import com.google.zxing.*;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import git.yawaflua.tech.spmega.client.telemetry.TelemetryCollector;
import git.yawaflua.tech.spmega.client.telemetry.TelemetryEvent;
import git.yawaflua.tech.spmega.client.ui.QRcodeAcceptScreen;
import git.yawaflua.tech.spmega.client.ui.UiNotifications;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public class QRCodeScanner {

    public static void ScanQrCode(MinecraftClient client) {
        if (client == null || client.player == null || client.getFramebuffer() == null) {
            return;
        }
        UiNotifications notifications = UiNotifications.instance();
        int framebufferWidth = client.getWindow().getFramebufferWidth();
        int framebufferHeight = client.getWindow().getFramebufferHeight();
        if (framebufferWidth <= 0 || framebufferHeight <= 0) {
            client.player.sendMessage(Text.translatable("message.spmega.qr.capture_failed"), false);
            return;
        }

        long startNs = System.nanoTime();
        try {
            ScreenshotRecorder.takeScreenshot(client.getFramebuffer(), nativeImage -> {
                try {
                    if (nativeImage == null || nativeImage.getWidth() <= 0 || nativeImage.getHeight() <= 0) {
                        client.execute(() -> {
                            if (client.player != null) {
                                notifications.show(Text.translatable("message.spmega.qr.capture_failed"));
                            }
                        });
                        recordQrEvent(false, false, null, "capture_failed", startNs);
                        return;
                    }

                    String result = decodeQRCode(nativeImageToBufferedImage(nativeImage));
                    long scanNs = System.nanoTime();
                    boolean didLag = (scanNs - startNs) > 50_000_000L;
                    client.execute(() -> {
                        if (client.player == null) {
                            return;
                        }
                        if (result == null) {
                            notifications.show(Text.translatable("message.spmega.qr.not_found"));
                            return;
                        }

                        Text clickableLink = Text.literal(result)
                                .styled(style -> style.withInsertion(result));
                        client.player.sendMessage(Text.translatable("message.spmega.qr.found_link", clickableLink), false);
                        client.setScreen(new QRcodeAcceptScreen(result, client.currentScreen));
                    });
                    recordQrEvent(result != null, didLag, result, null, startNs);
                } finally {
                    if (nativeImage != null) {
                        nativeImage.close();
                    }
                }
            });
        } catch (Exception ex) {
            notifications.show(Text.translatable("message.spmega.qr.error"));
            recordQrEvent(false, false, null, ex.getClass().getSimpleName() + ": " + ex.getMessage(), startNs);
        }
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

    private static BufferedImage nativeImageToBufferedImage(NativeImage screenshot) {
        BufferedImage bufferedImage = new BufferedImage(
                screenshot.getWidth(),
                screenshot.getHeight(),
                BufferedImage.TYPE_INT_RGB
        );

        for (int y = 0; y < screenshot.getHeight(); y++) {
            for (int x = 0; x < screenshot.getWidth(); x++) {
                int color = screenshot.getColorArgb(x, y);
                bufferedImage.setRGB(x, y, color);
            }
        }

        return bufferedImage;
    }

    private static String decodeQRCode(BufferedImage image) {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(DecodeHintType.ALSO_INVERTED, Boolean.TRUE);

        LuminanceSource source = new RGBLuminanceSource(image.getWidth(), image.getHeight(), image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()));

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

