package it.magius.struttura.architect.client.gui;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.config.ArchitectConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.FittingMultiLineTextWidget;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Screen shown when a newer version of Struttura Architect is available.
 * Offers: download & restart, visit website, or continue without updating.
 */
@Environment(EnvType.CLIENT)
public class UpdateAvailableScreen extends Screen {

    private static final int CONTENT_WIDTH = 380;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 260;
    private static final int BUTTON_SPACING = 4;
    private static final int TITLE_Y = 10;

    private final Screen parentScreen;
    private final String latestVersion;
    private final String downloadUrl;
    private boolean downloading = false;

    public UpdateAvailableScreen(Screen parentScreen, String latestVersion, String downloadUrl) {
        super(Component.translatable("struttura.update.title"));
        this.parentScreen = parentScreen;
        this.latestVersion = latestVersion;
        this.downloadUrl = downloadUrl;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;

        // Fixed layout: buttons at bottom
        int totalButtonsHeight = 3 * BUTTON_HEIGHT + 2 * BUTTON_SPACING;
        int bottomMargin = 10;
        int buttonsStartY = this.height - totalButtonsHeight - bottomMargin;

        // Text area: between title and buttons
        int textPadding = (this.width - CONTENT_WIDTH) / 2;
        int textStartY = TITLE_Y + 14;
        int textHeight = Math.max(40, buttonsStartY - textStartY - 8);

        // Scrollable message
        String message = Component.translatable("struttura.update.message",
            latestVersion, Architect.MOD_VERSION).getString();
        FittingMultiLineTextWidget messageWidget = new FittingMultiLineTextWidget(
            textPadding,
            textStartY,
            CONTENT_WIDTH,
            textHeight,
            Component.literal(message),
            this.font
        );
        this.addRenderableWidget(messageWidget);

        // Button 1: Download & Restart
        this.addRenderableWidget(Button.builder(
            Component.translatable("struttura.update.btn.download"),
            button -> downloadAndRestart(button)
        ).bounds(centerX - BUTTON_WIDTH / 2, buttonsStartY, BUTTON_WIDTH, BUTTON_HEIGHT).build());

        // Button 2: Go to website
        this.addRenderableWidget(Button.builder(
            Component.translatable("struttura.update.btn.website"),
            button -> openWebsite()
        ).bounds(centerX - BUTTON_WIDTH / 2, buttonsStartY + BUTTON_HEIGHT + BUTTON_SPACING, BUTTON_WIDTH, BUTTON_HEIGHT).build());

        // Button 3: Continue without updating (not recommended)
        this.addRenderableWidget(Button.builder(
            Component.translatable("struttura.update.btn.skip"),
            button -> this.minecraft.setScreen(this.parentScreen)
        ).bounds(centerX - BUTTON_WIDTH / 2, buttonsStartY + 2 * (BUTTON_HEIGHT + BUTTON_SPACING), BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        GuiAssets.renderBackground(graphics, this.width, this.height);
        graphics.fill(0, 0, this.width, this.height, 0xA0000000);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, TITLE_Y, 0xFFFFFFFF);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void downloadAndRestart(Button button) {
        if (downloading || downloadUrl == null || downloadUrl.isEmpty()) return;
        downloading = true;
        button.active = false;
        button.setMessage(Component.translatable("struttura.update.btn.downloading"));

        // Run download in background thread
        new Thread(() -> {
            try {
                // Find current mod JAR
                Path currentJar = FabricLoader.getInstance().getModContainer("architect")
                    .flatMap(c -> c.getOrigin().getPaths().stream().findFirst())
                    .orElse(null);

                if (currentJar == null) {
                    Architect.LOGGER.error("Cannot find current mod JAR path");
                    Minecraft.getInstance().execute(() -> {
                        button.setMessage(Component.translatable("struttura.update.btn.error"));
                        button.active = true;
                        downloading = false;
                    });
                    return;
                }

                // Build full download URL from the endpoint base host + server path
                ArchitectConfig config = ArchitectConfig.getInstance();
                String fullUrl;
                if (downloadUrl.startsWith("http")) {
                    fullUrl = downloadUrl;
                } else {
                    // downloadUrl is like /v1/cdn/mod-releases/... (relative to host root)
                    // endpoint is like https://api-struttura.magius.it/v1 — extract host base
                    String endpoint = config.getEndpoint();
                    try {
                        java.net.URI uri = URI.create(endpoint);
                        String baseUrl = uri.getScheme() + "://" + uri.getAuthority();
                        fullUrl = baseUrl + (downloadUrl.startsWith("/") ? downloadUrl : "/" + downloadUrl);
                    } catch (Exception e) {
                        // Fallback: strip /v1 suffix
                        int idx = endpoint.indexOf("/v1");
                        String baseUrl = idx >= 0 ? endpoint.substring(0, idx) : endpoint;
                        fullUrl = baseUrl + (downloadUrl.startsWith("/") ? downloadUrl : "/" + downloadUrl);
                    }
                }

                Architect.LOGGER.info("Downloading update from {}", fullUrl);

                // Download new JAR to mods folder
                Path modsFolder = FabricLoader.getInstance().getGameDir().resolve("mods");
                String newFilename = "struttura-" + latestVersion + ".jar";
                Path newJar = modsFolder.resolve(newFilename);

                HttpURLConnection conn = (HttpURLConnection) URI.create(fullUrl).toURL().openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("Authorization", config.getAuth());

                int status = conn.getResponseCode();
                if (status >= 200 && status < 300) {
                    try (InputStream is = conn.getInputStream()) {
                        Files.copy(is, newJar, StandardCopyOption.REPLACE_EXISTING);
                    }
                    conn.disconnect();

                    Architect.LOGGER.info("Update downloaded to {}", newJar);

                    // Delete old JAR
                    try {
                        Files.deleteIfExists(currentJar);
                        Architect.LOGGER.info("Deleted old mod JAR: {}", currentJar);
                    } catch (Exception e) {
                        Architect.LOGGER.warn("Could not delete old JAR (will be cleaned up manually): {}", e.getMessage());
                    }

                    // Schedule Minecraft shutdown on render thread
                    Minecraft.getInstance().execute(() -> {
                        Architect.LOGGER.info("Update complete, shutting down Minecraft for restart");
                        Minecraft.getInstance().stop();
                    });
                } else {
                    conn.disconnect();
                    Architect.LOGGER.error("Update download failed with status {}", status);
                    Minecraft.getInstance().execute(() -> {
                        button.setMessage(Component.translatable("struttura.update.btn.error"));
                        button.active = true;
                        downloading = false;
                    });
                }
            } catch (Exception e) {
                Architect.LOGGER.error("Update download failed", e);
                Minecraft.getInstance().execute(() -> {
                    button.setMessage(Component.translatable("struttura.update.btn.error"));
                    button.active = true;
                    downloading = false;
                });
            }
        }, "Struttura-Update-Download").start();
    }

    private void openWebsite() {
        ArchitectConfig config = ArchitectConfig.getInstance();
        String www = config.getWww();
        if (www == null || www.isEmpty()) {
            www = "https://struttura.magius.it";
        }
        if (www.endsWith("/")) {
            www = www.substring(0, www.length() - 1);
        }
        String url = www;
        this.minecraft.setScreen(new ConfirmLinkScreen(
            confirmed -> {
                if (confirmed) {
                    try {
                        Util.getPlatform().openUri(new URI(url));
                    } catch (Exception e) {
                        // Ignore URI parsing errors
                    }
                }
                this.minecraft.setScreen(this);
            },
            url,
            true
        ));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) { // ESC
            this.minecraft.setScreen(this.parentScreen);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean consumed) {
        if (consumed) return true;
        return super.mouseClicked(event, consumed);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parentScreen);
    }
}
