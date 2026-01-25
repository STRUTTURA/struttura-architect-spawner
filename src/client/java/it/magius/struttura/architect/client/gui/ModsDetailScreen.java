package it.magius.struttura.architect.client.gui;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.client.ModValidator;
import it.magius.struttura.architect.model.ModInfo;
import it.magius.struttura.architect.network.InGameListsPacket;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Screen showing detailed mod requirements for an InGame list.
 * Shows list of required mods with install status and download links.
 */
@Environment(EnvType.CLIENT)
public class ModsDetailScreen extends Screen {

    private static final int CONTENT_WIDTH = 380;
    private static final int MOD_ITEM_HEIGHT = 48;
    private static final int BUTTON_HEIGHT = 20;
    private static final int MAX_VISIBLE_MODS = 4;

    private final InGameSetupScreen parentScreen;
    private final InGameListsPacket.ListInfo listInfo;
    private final Map<String, ModInfo> mods;
    private final Map<String, String> installedVersions;
    private final Map<String, ModInfo> missingMods;
    private final List<String> modIds;

    // Scroll state
    private int scrollOffset = 0;

    // Download link areas for click detection
    private final List<DownloadLinkArea> downloadLinkAreas = new ArrayList<>();

    public ModsDetailScreen(InGameSetupScreen parent, InGameListsPacket.ListInfo listInfo) {
        super(Component.translatable("struttura.ingame.mods.detail.title"));
        this.parentScreen = parent;
        this.listInfo = listInfo;
        this.mods = listInfo.mods();
        this.installedVersions = ModValidator.getInstalledVersions(mods);
        this.missingMods = ModValidator.getMissingMods(mods);
        this.modIds = new ArrayList<>(mods.keySet());
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;

        // Bottom buttons
        int bottomY = this.height - 45;
        int buttonWidth = 140;
        int buttonSpacing = 20;
        int totalWidth = (buttonWidth * 2) + buttonSpacing;
        int buttonsLeft = centerX - totalWidth / 2;

        // Back button (left)
        this.addRenderableWidget(Button.builder(
            Component.translatable("gui.back"),
            btn -> this.minecraft.setScreen(parentScreen)
        ).bounds(buttonsLeft, bottomY, buttonWidth, BUTTON_HEIGHT).build());

        // Continue button (right)
        this.addRenderableWidget(Button.builder(
            Component.translatable("struttura.ingame.mods.continue"),
            btn -> confirmSelection()
        ).bounds(buttonsLeft + buttonWidth + buttonSpacing, bottomY, buttonWidth, BUTTON_HEIGHT).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Clear download link areas
        downloadLinkAreas.clear();

        // Draw background
        GuiAssets.renderBackground(graphics, this.width, this.height);

        // Semi-transparent overlay
        graphics.fill(0, 0, this.width, this.height, 0xB0000000);

        int centerX = this.width / 2;
        int contentLeft = centerX - CONTENT_WIDTH / 2;

        // Title: list name
        String langCode = this.minecraft != null ? this.minecraft.getLanguageManager().getSelected() : "en_us";
        String listName = listInfo.getLocalizedName(langCode);
        graphics.drawCenteredString(this.font, listName, centerX, 20, 0xFFFFFFFF);

        // Subtitle: "Required Mods"
        graphics.drawCenteredString(this.font, this.title, centerX, 35, 0xFFFFAA00);

        // Warning message
        Component warning = Component.translatable("struttura.ingame.mods.warning");
        int warningWidth = this.font.width(warning);
        // Word wrap if too long
        if (warningWidth > CONTENT_WIDTH) {
            List<net.minecraft.util.FormattedCharSequence> lines = this.font.split(warning, CONTENT_WIDTH);
            int lineY = 52;
            for (var line : lines) {
                graphics.drawCenteredString(this.font, line, centerX, lineY, 0xFFFF8800);
                lineY += 12;
            }
        } else {
            graphics.drawCenteredString(this.font, warning, centerX, 52, 0xFFFF8800);
        }

        // Mods list area
        int listStartY = 80;
        int listEndY = this.height - 60;
        int visibleHeight = listEndY - listStartY;
        int maxVisibleMods = visibleHeight / MOD_ITEM_HEIGHT;

        // Render visible mods
        int y = listStartY;
        for (int i = scrollOffset; i < Math.min(modIds.size(), scrollOffset + maxVisibleMods); i++) {
            String modId = modIds.get(i);
            ModInfo mod = mods.get(modId);
            renderModEntry(graphics, modId, mod, contentLeft, y, CONTENT_WIDTH, mouseX, mouseY);
            y += MOD_ITEM_HEIGHT;
        }

        // Scroll indicators
        if (scrollOffset > 0) {
            graphics.drawCenteredString(this.font, "\u25B2 Scroll up", centerX, listStartY - 12, 0xFF888888);
        }
        if (scrollOffset + maxVisibleMods < modIds.size()) {
            graphics.drawCenteredString(this.font, "\u25BC Scroll down", centerX, listEndY + 2, 0xFF888888);
        }

        // Stats summary
        int totalMods = mods.size();
        int missingCount = missingMods.size();
        int installedCount = totalMods - missingCount;
        String statsText = String.format("%d/%d mods installed", installedCount, totalMods);
        int statsColor = missingCount > 0 ? 0xFFFF8888 : 0xFF88FF88;
        graphics.drawCenteredString(this.font, statsText, centerX, this.height - 60, statsColor);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderModEntry(GuiGraphics graphics, String modId, ModInfo mod, int x, int y, int width, int mouseX, int mouseY) {
        // Background for the entry
        boolean isInstalled = installedVersions.get(modId) != null;
        int bgColor = isInstalled ? 0x40008800 : 0x40880000;
        graphics.fill(x, y, x + width, y + MOD_ITEM_HEIGHT - 2, bgColor);

        // Mod name and version required
        String displayName = mod.getDisplayName() != null ? mod.getDisplayName() : modId;
        String requiredVersion = mod.getVersion() != null ? mod.getVersion() : "any";
        graphics.drawString(this.font, displayName, x + 8, y + 4, 0xFFFFFFFF);
        graphics.drawString(this.font, "v" + requiredVersion, x + 8 + this.font.width(displayName) + 8, y + 4, 0xFFAAAAAA);

        // Blocks/Entities count
        String counts = String.format("Blocks: %d, Mobs: %d", mod.getBlockCount(), mod.getMobsCount());
        graphics.drawString(this.font, counts, x + 8, y + 16, 0xFF888888);

        // Installed status
        String installedVersion = installedVersions.get(modId);
        if (installedVersion != null) {
            Component statusText = Component.translatable("struttura.ingame.mods.installed")
                .append(": " + installedVersion);
            graphics.drawString(this.font, statusText, x + 8, y + 28, 0xFF55FF55);
        } else {
            Component statusText = Component.translatable("struttura.ingame.mods.not_installed");
            graphics.drawString(this.font, statusText, x + 8, y + 28, 0xFFFF5555);
        }

        // Download link (if available and not installed)
        String downloadUrl = mod.getDownloadUrl();
        if (downloadUrl != null && !downloadUrl.isEmpty()) {
            Component downloadText = Component.translatable("struttura.ingame.mods.download");
            int linkX = x + width - this.font.width(downloadText) - 8;
            int linkY = y + 28;

            // Check if mouse is hovering over link
            boolean hovering = mouseX >= linkX && mouseX <= linkX + this.font.width(downloadText)
                            && mouseY >= linkY && mouseY <= linkY + 10;

            int linkColor = hovering ? 0xFF88AAFF : 0xFF5588FF;
            graphics.drawString(this.font, downloadText, linkX, linkY, linkColor);

            // Underline if hovering
            if (hovering) {
                graphics.fill(linkX, linkY + 10, linkX + this.font.width(downloadText), linkY + 11, linkColor);
            }

            // Store link area for click detection
            downloadLinkAreas.add(new DownloadLinkArea(linkX, linkY, this.font.width(downloadText), 10, downloadUrl));
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean consumed) {
        if (consumed) return true;

        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();

        if (button == 0) { // Left click
            for (DownloadLinkArea area : downloadLinkAreas) {
                if (mouseX >= area.x && mouseX <= area.x + area.width
                    && mouseY >= area.y && mouseY <= area.y + area.height) {
                    openDownloadLink(area.url);
                    return true;
                }
            }
        }
        return super.mouseClicked(event, consumed);
    }

    private void openDownloadLink(String url) {
        // Use ConfirmLinkScreen (standard Minecraft way) to warn user about leaving the game
        this.minecraft.setScreen(new ConfirmLinkScreen(
            confirmed -> {
                if (confirmed) {
                    try {
                        Util.getPlatform().openUri(new URI(url));
                    } catch (Exception e) {
                        Architect.LOGGER.warn("Failed to open download URL: {}", url, e);
                    }
                }
                this.minecraft.setScreen(this);
            },
            url,
            true
        ));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int listStartY = 80;
        int listEndY = this.height - 60;
        int visibleHeight = listEndY - listStartY;
        int maxVisibleMods = visibleHeight / MOD_ITEM_HEIGHT;

        if (scrollY > 0 && scrollOffset > 0) {
            scrollOffset--;
            return true;
        } else if (scrollY < 0 && scrollOffset + maxVisibleMods < modIds.size()) {
            scrollOffset++;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void confirmSelection() {
        // Confirm selection through parent screen
        parentScreen.confirmListSelection(listInfo);
        this.minecraft.setScreen(null);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true; // Allow ESC to go back
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parentScreen);
    }

    /**
     * Helper class to track download link click areas.
     */
    private record DownloadLinkArea(int x, int y, int width, int height, String url) {}
}
