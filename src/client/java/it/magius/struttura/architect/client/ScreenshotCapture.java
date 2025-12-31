package it.magius.struttura.architect.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.network.ScreenshotDataPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gestisce la cattura e l'elaborazione degli screenshot lato client.
 * La cattura avviene in due fasi:
 * 1. Hide UI e attendi un frame
 * 2. Cattura il framebuffer al frame successivo
 */
public class ScreenshotCapture {

    private static final int MAX_WIDTH = 1600;
    private static final int MAX_HEIGHT = 900;
    private static final float JPEG_QUALITY = 0.85f;

    // Stato per la cattura in due fasi
    private static String pendingConstructionId = null;
    private static String pendingTitle = null;
    private static int frameCounter = 0;
    private static boolean wasGuiHidden = false;

    /**
     * Richiede la cattura di uno screenshot.
     * La cattura vera e propria avverra' nei prossimi frame render.
     */
    public static void requestCapture(String constructionId, String title) {
        if (pendingConstructionId != null) {
            Architect.LOGGER.warn("Screenshot capture already pending, ignoring new request");
            return;
        }

        Minecraft mc = Minecraft.getInstance();

        // Salva lo stato corrente della GUI e nascondila
        wasGuiHidden = mc.options.hideGui;
        mc.options.hideGui = true;

        pendingConstructionId = constructionId;
        pendingTitle = title;
        frameCounter = 0;

        Architect.LOGGER.debug("Screenshot capture requested for {}, waiting for frame render", constructionId);
    }

    /**
     * Chiamato ad ogni frame render.
     * Gestisce la cattura in due fasi per assicurarsi che la UI sia nascosta.
     */
    public static void onRenderTick() {
        if (pendingConstructionId == null) {
            return;
        }

        frameCounter++;

        // Aspetta 2 frame per assicurarsi che la UI sia completamente nascosta
        if (frameCounter < 2) {
            return;
        }

        String constructionId = pendingConstructionId;
        String title = pendingTitle;
        pendingConstructionId = null;
        pendingTitle = null;

        try {
            captureAndSend(constructionId, title);
        } catch (Exception e) {
            Architect.LOGGER.error("Failed to capture screenshot", e);
        } finally {
            // Ripristina lo stato della GUI
            Minecraft mc = Minecraft.getInstance();
            mc.options.hideGui = wasGuiHidden;
        }
    }

    /**
     * Cattura lo screenshot, lo processa e lo invia al server.
     * In MC 1.21+ Screenshot.takeScreenshot richiede un Consumer asincrono.
     */
    private static void captureAndSend(String constructionId, String title) {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget framebuffer = mc.getMainRenderTarget();

        // Cattura il framebuffer in modo asincrono
        Screenshot.takeScreenshot(framebuffer, nativeImage -> {
            try {
                processAndSend(nativeImage, constructionId, title);
            } catch (Exception e) {
                Architect.LOGGER.error("Failed to process screenshot", e);
            } finally {
                nativeImage.close();
            }
        });
    }

    /**
     * Processa l'immagine catturata e la invia al server.
     */
    private static void processAndSend(NativeImage screenshot, String constructionId, String title) throws IOException {
        // Converti NativeImage in BufferedImage
        BufferedImage bufferedImage = nativeImageToBufferedImage(screenshot);

        // Ridimensiona l'immagine
        BufferedImage resized = resizeImage(bufferedImage, MAX_WIDTH, MAX_HEIGHT);

        // Codifica in JPEG
        byte[] jpegData = encodeJpeg(resized);

        Architect.LOGGER.info("Screenshot captured for {}: {}x{} -> {}x{}, {} bytes JPEG",
            constructionId,
            bufferedImage.getWidth(), bufferedImage.getHeight(),
            resized.getWidth(), resized.getHeight(),
            jpegData.length);

        // Invia al server (deve essere eseguito sul thread principale)
        Minecraft.getInstance().execute(() -> {
            ClientPlayNetworking.send(new ScreenshotDataPacket(constructionId, title, jpegData));
        });
    }

    /**
     * Converte una NativeImage in BufferedImage.
     */
    private static BufferedImage nativeImageToBufferedImage(NativeImage nativeImage) {
        int width = nativeImage.getWidth();
        int height = nativeImage.getHeight();

        BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // NativeImage.getPixel() in MC 1.21 restituisce ABGR packed come int
                // Formato: 0xAABBGGRR (alpha nei bit alti, red nei bit bassi)
                int pixel = nativeImage.getPixel(x, y);

                // Swap R e B per ottenere il formato corretto per BufferedImage
                // BufferedImage.setRGB() vuole 0x00RRGGBB
                int a = (pixel >> 24) & 0xFF;
                int b = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int r = pixel & 0xFF;

                // Costruisci RGB swappando R e B
                int rgb = (b << 16) | (g << 8) | r;
                bufferedImage.setRGB(x, y, rgb);
            }
        }

        return bufferedImage;
    }

    /**
     * Ridimensiona l'immagine per entrare nei bounds massimi mantenendo l'aspect ratio.
     * Algoritmo fit/contain: scala per entrare completamente nei bounds.
     */
    private static BufferedImage resizeImage(BufferedImage original, int maxWidth, int maxHeight) {
        int origW = original.getWidth();
        int origH = original.getHeight();

        // Se gia' nei limiti, ritorna l'originale
        if (origW <= maxWidth && origH <= maxHeight) {
            return original;
        }

        // Calcola la scala per entrare nei bounds
        double scaleW = (double) maxWidth / origW;
        double scaleH = (double) maxHeight / origH;
        double scale = Math.min(scaleW, scaleH);

        // Calcola le nuove dimensioni
        int newW = (int) Math.round(origW * scale);
        int newH = (int) Math.round(origH * scale);

        // Assicurati di non superare i limiti (sicurezza per arrotondamenti)
        newW = Math.min(newW, maxWidth);
        newH = Math.min(newH, maxHeight);

        // Ridimensiona con alta qualita'
        BufferedImage resized = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = resized.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.drawImage(original, 0, 0, newW, newH, null);
        g2d.dispose();

        return resized;
    }

    /**
     * Codifica l'immagine in formato JPEG con qualita' configurabile.
     */
    private static byte[] encodeJpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // Trova il writer JPEG
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG writer found");
        }

        ImageWriter writer = writers.next();
        try {
            ImageOutputStream ios = ImageIO.createImageOutputStream(baos);
            writer.setOutput(ios);

            // Configura la qualita'
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);

            // Scrivi l'immagine
            writer.write(null, new IIOImage(image, null, null), param);

            ios.close();
        } finally {
            writer.dispose();
        }

        return baos.toByteArray();
    }
}
