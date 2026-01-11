package it.magius.struttura.architect.client.gui.panel;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.client.gui.EditBoxHelper;
import it.magius.struttura.architect.client.gui.PanelManager;
import it.magius.struttura.architect.network.GuiActionPacket;
import it.magius.struttura.architect.network.SelectionKeyPacket;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;

/**
 * Panel showing editing information and controls.
 * Visible only when player is in editing mode.
 */
@Environment(EnvType.CLIENT)
public class EditingPanel {

    /**
     * Represents an item that can be removed from the dropdown (block or entity).
     */
    private record RemovableItem(
        String id,          // blockId or entity UUID
        String displayName, // Display name
        String extra,       // "x123" for blocks, entity type for entities
        boolean isEntity    // true if entity, false if block
    ) {}

    /**
     * Build a combined list of removable items (blocks + entities).
     * Blocks come first, then a separator (null), then entities.
     */
    private static java.util.List<RemovableItem> buildRemovableList(PanelManager pm) {
        java.util.List<RemovableItem> items = new java.util.ArrayList<>();

        // Add blocks
        for (PanelManager.BlockInfo block : pm.getBlockList()) {
            items.add(new RemovableItem(
                block.blockId(),
                block.displayName(),
                "x" + block.count(),
                false
            ));
        }

        // Add entities (grouped by type with count, like blocks)
        for (PanelManager.EntityInfo entity : pm.getEntityList()) {
            items.add(new RemovableItem(
                entity.entityType(),
                entity.displayName(),
                "x" + entity.count(),
                true
            ));
        }

        return items;
    }

    private static final int WIDTH = 180;
    private static final int HEIGHT = 220;  // Stessa altezza del MainPanel
    private static final int PADDING = 5;
    private static final int BUTTON_HEIGHT = 16;
    private static final int LINE_HEIGHT = 11;
    private static final int DROPDOWN_HEIGHT = 14;
    private static final int DROPDOWN_MAX_ITEMS = 5;

    private int x, y;
    private int hoveredButton = -1;

    // EditBox helpers for editable fields
    private EditBoxHelper rdnsBox;
    private EditBoxHelper nameBox;
    private boolean editBoxesInitialized = false;

    // Track which field was being edited to restore value on escape
    private String originalRdns = "";
    private String originalName = "";

    // Block dropdown state
    private boolean dropdownOpen = false;
    private int dropdownScrollOffset = 0;
    private int selectedBlockIndex = 0;

    // Dropdown render state (cached for later rendering on top of everything)
    private int cachedDropdownX = 0;
    private int cachedDropdownY = 0;
    private int cachedDropdownWidth = 0;
    private int cachedDropdownListY = 0;
    private int cachedDropdownListHeight = 0;

    // Bottom section cached positions for click handling
    private int cachedDoneY = 0;

    // Scrollbar drag state
    private boolean draggingScrollbar = false;
    private int dragStartY = 0;
    private int dragStartScrollOffset = 0;

    // Short description modal state
    private boolean shortDescModalOpen = false;
    private String shortDescText = "";
    private int shortDescCursorPos = 0;
    private int shortDescScrollOffset = 0;
    private boolean shortDescDraggingScrollbar = false;
    private int shortDescDragStartY = 0;
    private int shortDescDragStartScrollOffset = 0;
    // Text selection state (-1 means no selection)
    private int shortDescSelectionStart = -1;
    private int shortDescSelectionEnd = -1;
    // Mouse dragging for selection
    private boolean shortDescDraggingSelection = false;

    // Language dropdown state
    private boolean langDropdownOpen = false;
    private String selectedLangId = "en";  // Default to English
    private boolean langInitialized = false;  // Track if language was already set
    private java.util.Map<String, String> allTitles = new java.util.HashMap<>();
    private java.util.Map<String, String> allShortDescriptions = new java.util.HashMap<>();

    // Language dropdown render state (cached for later rendering on top of everything)
    private int cachedLangDropdownX = 0;
    private int cachedLangDropdownY = 0;
    private int cachedLangDropdownWidth = 0;
    private int cachedLangDropdownListY = 0;
    private int cachedLangDropdownListHeight = 0;

    // Room dropdown state
    private boolean roomDropdownOpen = false;
    private int selectedRoomIndex = 0;  // 0 = "+++ new +++" placeholder
    private int cachedRoomDropdownX = 0;
    private int cachedRoomDropdownY = 0;
    private int cachedRoomDropdownWidth = 0;
    private int cachedRoomDropdownListY = 0;
    private int cachedRoomDropdownListHeight = 0;

    // New room modal state
    private boolean newRoomModalOpen = false;
    private String newRoomIdPreview = "";
    private EditBoxHelper newRoomNameBox;

    // Room editing state (when editing an existing room)
    private EditBoxHelper roomIdBox;
    private EditBoxHelper roomNameBox;
    private String originalRoomId = "";
    private String originalRoomName = "";
    private String roomRenameError = "";  // Error message for duplicate ID

    // Supported languages with display names
    private static final java.util.List<LangInfo> SUPPORTED_LANGUAGES = java.util.List.of(
        new LangInfo("en", "English"),
        new LangInfo("it", "Italiano"),
        new LangInfo("de", "Deutsch"),
        new LangInfo("fr", "Français"),
        new LangInfo("es", "Español"),
        new LangInfo("pt", "Português"),
        new LangInfo("nl", "Nederlands"),
        new LangInfo("pl", "Polski"),
        new LangInfo("ru", "Русский"),
        new LangInfo("uk", "Українська")
    );

    private record LangInfo(String id, String displayName) {}

    /**
     * Generate room ID from name (same algorithm as server-side Room.generateId).
     */
    private static String generateRoomId(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        String id = name.toLowerCase()
            .trim()
            .replace(' ', '_')
            .replace('-', '_');
        // Remove invalid chars (keep only a-z, 0-9, _)
        StringBuilder sb = new StringBuilder();
        for (char c : id.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            }
        }
        id = sb.toString();
        // Remove multiple consecutive underscores
        while (id.contains("__")) {
            id = id.replace("__", "_");
        }
        // Remove leading/trailing underscores
        id = id.replaceAll("^_+|_+$", "");
        // Ensure doesn't start with digit
        if (!id.isEmpty() && Character.isDigit(id.charAt(0))) {
            id = "room_" + id;
        }
        // Truncate to 50 chars
        if (id.length() > 50) {
            id = id.substring(0, 50);
        }
        return id;
    }

    public int getWidth() {
        return WIDTH;
    }

    public int getHeight() {
        return HEIGHT;
    }

    /**
     * Check if dropdown is open.
     */
    public boolean isDropdownOpen() {
        return dropdownOpen;
    }

    /**
     * Check if short description modal is open.
     */
    public boolean isShortDescModalOpen() {
        return shortDescModalOpen;
    }

    /**
     * Check if new room modal is open.
     */
    public boolean isNewRoomModalOpen() {
        return newRoomModalOpen;
    }

    /**
     * Check if room dropdown is open.
     */
    public boolean isRoomDropdownOpen() {
        return roomDropdownOpen;
    }

    /**
     * Set the short description text (called when loading from server).
     */
    public void setShortDescText(String text) {
        this.shortDescText = text != null ? text : "";
        this.shortDescCursorPos = 0;
        this.shortDescScrollOffset = 0;
        this.shortDescSelectionStart = -1;
        this.shortDescSelectionEnd = -1;
    }

    /**
     * Set room rename error message (called from server response handler).
     */
    public void setRoomRenameError(String error) {
        this.roomRenameError = error != null ? error : "";
    }

    /**
     * Clear room rename error message.
     */
    public void clearRoomRenameError() {
        this.roomRenameError = "";
    }

    /**
     * Update translations from server.
     */
    public void updateTranslations(java.util.Map<String, String> titles, java.util.Map<String, String> shortDescriptions) {
        this.allTitles = new java.util.HashMap<>(titles);
        this.allShortDescriptions = new java.util.HashMap<>(shortDescriptions);

        // Set initial language to system language only on first call (not on subsequent updates)
        if (!langInitialized) {
            langInitialized = true;
            String systemLang = Minecraft.getInstance().getLanguageManager().getSelected();
            if (systemLang != null) {
                // Extract language code (e.g., "en_us" -> "en")
                String langCode = systemLang.split("_")[0].toLowerCase();
                // Check if this language is in our supported list
                for (LangInfo lang : SUPPORTED_LANGUAGES) {
                    if (lang.id().equals(langCode)) {
                        selectedLangId = langCode;
                        break;
                    }
                }
            }
        }

        // Update name box and short desc with selected language
        updateFieldsForSelectedLanguage();
    }

    /**
     * Clear translations.
     */
    public void clearTranslations() {
        this.allTitles.clear();
        this.allShortDescriptions.clear();
        this.selectedLangId = "en";
        this.langInitialized = false;  // Reset so next edit session will use system language
        this.langDropdownOpen = false;
    }

    /**
     * Update Name and ShortDesc fields based on selected language.
     */
    private void updateFieldsForSelectedLanguage() {
        String title = allTitles.getOrDefault(selectedLangId, "");
        String shortDesc = allShortDescriptions.getOrDefault(selectedLangId, "");

        if (nameBox != null) {
            nameBox.setValue(title);
            originalName = title;
        }
        setShortDescText(shortDesc);
    }

    /**
     * Get current selected language ID.
     */
    public String getSelectedLangId() {
        return selectedLangId;
    }

    /**
     * Get language indicator for dropdown display.
     * Returns "" if empty, "~" if only one of title/shortDesc is set, "*" if both are set.
     */
    private String getLangIndicator(String langId) {
        boolean hasTitle = allTitles.containsKey(langId) && !allTitles.get(langId).isEmpty();
        boolean hasShortDesc = allShortDescriptions.containsKey(langId) && !allShortDescriptions.get(langId).isEmpty();

        if (hasTitle && hasShortDesc) {
            return " *";
        } else if (hasTitle || hasShortDesc) {
            return " ~";
        }
        return "";
    }

    /**
     * Get display name for language ID.
     */
    private String getLangDisplayName(String langId) {
        for (LangInfo lang : SUPPORTED_LANGUAGES) {
            if (lang.id().equals(langId)) {
                return lang.displayName();
            }
        }
        return langId;
    }

    /**
     * Check if dropdown is open and click is within dropdown list bounds.
     * Used by StrutturaScreen to prioritize dropdown clicks.
     */
    public boolean isDropdownOpenAt(double mouseX, double mouseY) {
        if (!dropdownOpen) {
            return false;
        }
        return mouseX >= cachedDropdownX && mouseX < cachedDropdownX + cachedDropdownWidth &&
               mouseY >= cachedDropdownListY && mouseY < cachedDropdownListY + cachedDropdownListHeight;
    }

    /**
     * Check if language dropdown is open and mouse is within its list bounds.
     */
    public boolean isLangDropdownOpenAt(double mouseX, double mouseY) {
        if (!langDropdownOpen) {
            return false;
        }
        return mouseX >= cachedLangDropdownX && mouseX < cachedLangDropdownX + cachedLangDropdownWidth &&
               mouseY >= cachedLangDropdownListY && mouseY < cachedLangDropdownListY + cachedLangDropdownListHeight;
    }

    /**
     * Check if language dropdown is open.
     */
    public boolean isLangDropdownOpen() {
        return langDropdownOpen;
    }

    /**
     * Handle click on the dropdown list area.
     * Returns true if click was handled.
     */
    public boolean handleDropdownClick(double mouseX, double mouseY, int button) {
        if (button != 0 || !dropdownOpen) {
            return false;
        }

        java.util.List<RemovableItem> removableList = buildRemovableList(PanelManager.getInstance());
        if (removableList.isEmpty()) {
            return false;
        }

        // Check if click is on the scrollbar area
        int scrollbarWidth = 4;
        int scrollbarX = cachedDropdownX + cachedDropdownWidth - scrollbarWidth - 1;
        int visibleItems = cachedDropdownListHeight / DROPDOWN_HEIGHT;
        visibleItems = Math.min(visibleItems, removableList.size());

        if (removableList.size() > visibleItems &&
            mouseX >= scrollbarX && mouseX < scrollbarX + scrollbarWidth + 1 &&
            mouseY >= cachedDropdownListY && mouseY < cachedDropdownListY + cachedDropdownListHeight) {
            // Start dragging scrollbar
            draggingScrollbar = true;
            dragStartY = (int) mouseY;
            dragStartScrollOffset = dropdownScrollOffset;
            return true;
        }

        // Check click on dropdown list item
        if (mouseX >= cachedDropdownX && mouseX < cachedDropdownX + cachedDropdownWidth &&
            mouseY >= cachedDropdownListY && mouseY < cachedDropdownListY + cachedDropdownListHeight) {
            int itemIndex = (int)((mouseY - cachedDropdownListY) / DROPDOWN_HEIGHT) + dropdownScrollOffset;
            if (itemIndex >= 0 && itemIndex < removableList.size()) {
                selectedBlockIndex = itemIndex;
                dropdownOpen = false;
            }
            return true;
        }

        // Click outside dropdown - close it
        dropdownOpen = false;
        return false;
    }

    /**
     * Handle mouse drag for scrollbar.
     */
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        // Handle short desc modal text selection drag
        if (shortDescDraggingSelection && shortDescModalOpen) {
            Minecraft mc = Minecraft.getInstance();
            Font font = mc.font;
            int screenW = mc.getWindow().getGuiScaledWidth();
            int screenH = mc.getWindow().getGuiScaledHeight();

            int modalW = 400;
            int modalH = 240;
            int modalX = (screenW - modalW) / 2;
            int modalY = (screenH - modalH) / 2;

            int scrollbarW = 8;
            int textAreaX = modalX + 5;
            int textAreaY = modalY + 18;
            int textAreaW = modalW - 10 - scrollbarW - 2;
            int lineHeight = 10;

            int newPos = getCharPositionFromClick(mouseX, mouseY, textAreaX, textAreaY, textAreaW, lineHeight, font);
            shortDescCursorPos = newPos;
            shortDescSelectionEnd = newPos;
            return true;
        }

        // Handle short desc modal scrollbar drag
        if (shortDescDraggingScrollbar && shortDescModalOpen) {
            Minecraft mc = Minecraft.getInstance();
            Font font = mc.font;
            int screenW = mc.getWindow().getGuiScaledWidth();
            int screenH = mc.getWindow().getGuiScaledHeight();

            int modalW = 400;
            int modalH = 240;
            int modalX = (screenW - modalW) / 2;
            int modalY = (screenH - modalH) / 2;

            int scrollbarW = 8;
            int textAreaX = modalX + 5;
            int textAreaY = modalY + 18;
            int textAreaW = modalW - 10 - scrollbarW - 2;
            int textAreaH = modalH - 55;
            int lineHeight = 10;

            java.util.List<String> lines = wrapText(shortDescText, textAreaW - 6, font);
            int totalLines = lines.size();
            int visibleLines = (textAreaH - 6) / lineHeight;
            int maxScroll = Math.max(0, totalLines - visibleLines);

            if (maxScroll > 0) {
                float thumbRatio = (float) visibleLines / totalLines;
                int thumbH = Math.max(20, (int) (textAreaH * thumbRatio));
                int scrollableTrack = textAreaH - thumbH;

                if (scrollableTrack > 0) {
                    int dragDistance = (int) mouseY - shortDescDragStartY;
                    float scrollPercent = (float) dragDistance / scrollableTrack;
                    int newOffset = shortDescDragStartScrollOffset + (int) (scrollPercent * maxScroll);
                    shortDescScrollOffset = Math.max(0, Math.min(maxScroll, newOffset));
                }
            }
            return true;
        }

        if (!draggingScrollbar || !dropdownOpen) {
            return false;
        }

        java.util.List<RemovableItem> removableList = buildRemovableList(PanelManager.getInstance());
        if (removableList.isEmpty()) {
            draggingScrollbar = false;
            return false;
        }

        int visibleItems = cachedDropdownListHeight / DROPDOWN_HEIGHT;
        visibleItems = Math.min(visibleItems, removableList.size());
        int maxScroll = Math.max(0, removableList.size() - visibleItems);

        if (maxScroll == 0) {
            draggingScrollbar = false;
            return false;
        }

        // Calculate scroll based on drag distance
        int dragDistance = (int) mouseY - dragStartY;
        int trackHeight = cachedDropdownListHeight - 2;
        int thumbHeight = Math.max(10, (visibleItems * cachedDropdownListHeight) / removableList.size());
        int scrollableTrack = trackHeight - thumbHeight;

        if (scrollableTrack > 0) {
            float scrollPercent = (float) dragDistance / scrollableTrack;
            int newOffset = dragStartScrollOffset + (int) (scrollPercent * maxScroll);
            dropdownScrollOffset = Math.max(0, Math.min(maxScroll, newOffset));
        }

        return true;
    }

    /**
     * Handle mouse release for scrollbar drag and selection.
     */
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (shortDescDraggingSelection) {
            shortDescDraggingSelection = false;
            // If selection is empty (start == end), clear it
            if (shortDescSelectionStart == shortDescSelectionEnd) {
                clearShortDescSelection();
            }
            return true;
        }
        if (shortDescDraggingScrollbar) {
            shortDescDraggingScrollbar = false;
            return true;
        }
        if (draggingScrollbar) {
            draggingScrollbar = false;
            return true;
        }
        return false;
    }

    /**
     * Initialize EditBox helpers. Must be called after Minecraft client is ready.
     */
    private void initEditBoxes(Font font) {
        if (editBoxesInitialized) return;

        // RDNS box - lowercase only ID characters
        rdnsBox = new EditBoxHelper(font, 0, 0, WIDTH - PADDING * 2, LINE_HEIGHT,
            "namespace.category.name",
            s -> s.chars().allMatch(c -> (c >= 'a' && c <= 'z') || Character.isDigit(c) || c == '.' || c == '_'),
            null);

        // Name box - free text
        nameBox = EditBoxHelper.createTextBox(font, 0, 0, WIDTH - PADDING * 2, LINE_HEIGHT, "(not set)");

        // New room name box - free text, max 50 chars
        newRoomNameBox = EditBoxHelper.createTextBox(font, 0, 0, 200, LINE_HEIGHT, "(enter name)");
        newRoomNameBox.setMaxLength(50);

        // Room ID box - lowercase only ID characters (for editing existing rooms)
        roomIdBox = new EditBoxHelper(font, 0, 0, WIDTH - PADDING * 2, LINE_HEIGHT,
            "room_id",
            s -> s.chars().allMatch(c -> (c >= 'a' && c <= 'z') || Character.isDigit(c) || c == '_'),
            null);
        roomIdBox.setMaxLength(50);

        // Room name box - free text (for editing existing rooms)
        roomNameBox = EditBoxHelper.createTextBox(font, 0, 0, WIDTH - PADDING * 2, LINE_HEIGHT, "(room name)");
        roomNameBox.setMaxLength(50);

        editBoxesInitialized = true;
    }

    public void render(GuiGraphics graphics, int x, int y, int mouseX, int mouseY, float tickDelta) {
        this.x = x;
        this.y = y;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        PanelManager pm = PanelManager.getInstance();

        // Initialize EditBoxes if needed
        initEditBoxes(font);

        // When any dropdown is open, use fake mouse coordinates for elements below it
        // This prevents hover effects on buttons when mouse is over dropdown
        int effectiveMouseX = mouseX;
        int effectiveMouseY = mouseY;
        if ((dropdownOpen && isDropdownOpenAt(mouseX, mouseY)) ||
            (langDropdownOpen && isLangDropdownOpenAt(mouseX, mouseY))) {
            // Move mouse to invalid position to prevent any hover
            effectiveMouseX = -1000;
            effectiveMouseY = -1000;
        }

        // Draw background
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, 0xE0000000);
        graphics.renderOutline(x, y, WIDTH, HEIGHT, 0xFF404040);

        int currentY = y + PADDING;
        hoveredButton = -1;

        // Check if in room editing mode
        boolean inRoomEditing = pm.isInRoom();

        // Title: "Edit building" or "Edit room" based on mode, with language dropdown on the right
        String panelTitle = inRoomEditing ? "Edit room" : "Edit building";
        graphics.drawString(font, panelTitle, x + PADDING, currentY, 0xFFFFAA00, true);

        // Language dropdown (aligned right)
        String langDisplay = getLangDisplayName(selectedLangId) + getLangIndicator(selectedLangId);
        int langWidth = font.width(langDisplay) + 12;  // Add padding for dropdown arrow
        int langX = x + WIDTH - PADDING - langWidth;
        int langY = currentY - 1;
        int langH = LINE_HEIGHT + 2;

        // Draw language dropdown button (check hover only if dropdown list is not covering it)
        boolean langHovered = !langDropdownOpen &&
                              mouseX >= langX && mouseX < langX + langWidth &&
                              mouseY >= langY && mouseY < langY + langH;
        graphics.fill(langX, langY, langX + langWidth, langY + langH,
                     langHovered ? 0xFF404060 : 0xFF303050);
        graphics.renderOutline(langX, langY, langWidth, langH, 0xFF505080);
        graphics.drawString(font, langDisplay, langX + 3, langY + 2, 0xFFCCCCFF, false);
        // Dropdown arrow
        graphics.drawString(font, langDropdownOpen ? "▲" : "▼", langX + langWidth - 10, langY + 2, 0xFF8888AA, false);

        // Cache language dropdown position for rendering the list later (on top of everything)
        cachedLangDropdownX = x + WIDTH - PADDING - 90;  // listW = 90
        cachedLangDropdownY = langY;
        cachedLangDropdownWidth = 90;
        cachedLangDropdownListY = langY + langH;
        cachedLangDropdownListHeight = SUPPORTED_LANGUAGES.size() * (LINE_HEIGHT + 1) + 2;

        currentY += LINE_HEIGHT + 2;

        // ID field - shows room ID when in room editing, construction ID otherwise
        String idLabel = "ID: ";
        int idLabelWidth = font.width(idLabel);
        graphics.drawString(font, idLabel, x + PADDING, currentY, 0xFF808080, false);

        int fieldX = x + PADDING + idLabelWidth;
        int fieldWidth = WIDTH - PADDING * 2 - idLabelWidth;

        if (inRoomEditing) {
            // Room ID (editable)
            if (!roomIdBox.isFocused()) {
                String currentRoomId = pm.getCurrentRoomId();
                if (!roomIdBox.getValue().equals(currentRoomId)) {
                    roomIdBox.setValue(currentRoomId);
                    originalRoomId = currentRoomId;
                }
            }
            roomIdBox.setPosition(fieldX, currentY - 1);
            roomIdBox.setWidth(fieldWidth);
            roomIdBox.render(graphics, effectiveMouseX, effectiveMouseY, tickDelta);
        } else {
            // Construction ID (editable)
            if (!rdnsBox.isFocused()) {
                String currentId = pm.getEditingConstructionId();
                if (!rdnsBox.getValue().equals(currentId)) {
                    rdnsBox.setValue(currentId);
                }
            }
            rdnsBox.setPosition(fieldX, currentY - 1);
            rdnsBox.setWidth(fieldWidth);
            rdnsBox.render(graphics, effectiveMouseX, effectiveMouseY, tickDelta);
        }
        currentY += LINE_HEIGHT + 2;

        // Name field - shows room name when in room editing, construction title otherwise
        String nameLabel = "Name: ";
        int nameLabelWidth = font.width(nameLabel);
        graphics.drawString(font, nameLabel, x + PADDING, currentY, 0xFF808080, false);

        fieldX = x + PADDING + nameLabelWidth;
        fieldWidth = WIDTH - PADDING * 2 - nameLabelWidth;

        if (inRoomEditing) {
            // Room name (editable) - when changed, also updates ID preview
            if (!roomNameBox.isFocused()) {
                String currentRoomName = pm.getCurrentRoomName();
                if (!roomNameBox.getValue().equals(currentRoomName)) {
                    roomNameBox.setValue(currentRoomName);
                    originalRoomName = currentRoomName;
                }
            }
            roomNameBox.setPosition(fieldX, currentY - 1);
            roomNameBox.setWidth(fieldWidth);
            roomNameBox.render(graphics, effectiveMouseX, effectiveMouseY, tickDelta);
        } else {
            // Construction name (editable) - uses selected language
            if (!nameBox.isFocused()) {
                String currentTitle = allTitles.getOrDefault(selectedLangId, "");
                if (!nameBox.getValue().equals(currentTitle)) {
                    nameBox.setValue(currentTitle);
                    originalName = currentTitle;
                }
            }
            nameBox.setPosition(fieldX, currentY - 1);
            nameBox.setWidth(fieldWidth);
            nameBox.render(graphics, effectiveMouseX, effectiveMouseY, tickDelta);
        }
        currentY += LINE_HEIGHT + 2;

        // Show room rename error message if any
        if (inRoomEditing && !roomRenameError.isEmpty()) {
            graphics.drawString(font, roomRenameError, x + PADDING, currentY, 0xFFFF6666, false);
            currentY += LINE_HEIGHT + 2;
        }

        // Short Desc button (only for construction editing, not room)
        if (!inRoomEditing) {
            String descLabel = "Short Desc.: ";
            int descLabelWidth = font.width(descLabel);
            graphics.drawString(font, descLabel, x + PADDING, currentY, 0xFF808080, false);

            int descBtnX = x + PADDING + descLabelWidth;
            int descBtnWidth = WIDTH - PADDING * 2 - descLabelWidth;
            boolean descHovered = effectiveMouseX >= descBtnX && effectiveMouseX < descBtnX + descBtnWidth &&
                                 effectiveMouseY >= currentY && effectiveMouseY < currentY + LINE_HEIGHT;

            graphics.fill(descBtnX, currentY - 1, descBtnX + descBtnWidth, currentY + LINE_HEIGHT - 1,
                         descHovered ? 0xFF404040 : 0xFF303030);
            graphics.renderOutline(descBtnX, currentY - 1, descBtnWidth, LINE_HEIGHT, 0xFF606060);

            // Replace newlines with spaces for preview display
            String descPreview = shortDescText.isEmpty() ? "(click to edit)" : truncate(shortDescText.replace('\n', ' '), descBtnWidth - 6, font);
            int descColor = shortDescText.isEmpty() ? 0xFF888888 : 0xFFCCCCCC;
            graphics.drawString(font, descPreview, descBtnX + 3, currentY, descColor, false);
            currentY += LINE_HEIGHT + PADDING;
        }

        // Stats - same format for both building and room editing
        // Server now sends room-specific stats when in room editing mode
        // Row 1: Blocks: <num>, Air: <num>
        String statsRow1 = "Blocks: " + pm.getBlockCount() + ", Air: " + pm.getAirBlockCount();
        graphics.drawString(font, statsRow1, x + PADDING, currentY, 0xFFCCCCCC, false);
        currentY += LINE_HEIGHT;

        // Row 2: Entities: <num>, Mob: <num>
        String statsRow2 = "Entities: " + pm.getEntityCount() + ", Mob: " + pm.getMobCount();
        graphics.drawString(font, statsRow2, x + PADDING, currentY, 0xFFCCCCCC, false);
        currentY += LINE_HEIGHT;

        // Row 3: Dimensions: WxHxD (or N/A for rooms)
        String statsRow3 = "Dimensions: " + pm.getBounds();
        graphics.drawString(font, statsRow3, x + PADDING, currentY, 0xFFCCCCCC, false);
        currentY += LINE_HEIGHT + PADDING;

        // Block/Entity removal section: dropdown + REMOVE button (moved before Mode)
        java.util.List<RemovableItem> removableList = buildRemovableList(pm);
        int dropdownWidth = WIDTH - PADDING * 2 - 50 - 2; // Leave space for REMOVE button
        int removeBtnWidth = 50;

        // Ensure selectedBlockIndex is valid for current list
        if (selectedBlockIndex >= removableList.size()) {
            selectedBlockIndex = Math.max(0, removableList.size() - 1);
            dropdownScrollOffset = 0;
        }

        // Dropdown closed: show selected item
        int dropdownY = currentY;
        String selectedText;
        if (removableList.isEmpty()) {
            selectedText = "(empty)";
        } else {
            RemovableItem selected = removableList.get(selectedBlockIndex);
            selectedText = truncate(selected.displayName() + " " + selected.extra(), dropdownWidth - 15, font);
        }

        boolean dropdownHovered = effectiveMouseX >= x + PADDING && effectiveMouseX < x + PADDING + dropdownWidth &&
                                  effectiveMouseY >= dropdownY && effectiveMouseY < dropdownY + DROPDOWN_HEIGHT;

        graphics.fill(x + PADDING, dropdownY, x + PADDING + dropdownWidth, dropdownY + DROPDOWN_HEIGHT,
                     dropdownHovered ? 0xFF404040 : 0xFF303030);
        graphics.renderOutline(x + PADDING, dropdownY, dropdownWidth, DROPDOWN_HEIGHT, 0xFF606060);
        graphics.drawString(font, selectedText, x + PADDING + 3, dropdownY + 3, 0xFFCCCCCC, false);
        // Draw dropdown arrow (pointing up since dropdown opens upward)
        graphics.drawString(font, dropdownOpen ? "\u25BC" : "\u25B2", x + PADDING + dropdownWidth - 10, dropdownY + 3, 0xFF888888, false);

        // REMOVE button
        int removeBtnX = x + PADDING + dropdownWidth + 2;
        boolean removeHovered = effectiveMouseX >= removeBtnX && effectiveMouseX < removeBtnX + removeBtnWidth &&
                               effectiveMouseY >= dropdownY && effectiveMouseY < dropdownY + DROPDOWN_HEIGHT;
        boolean removeDisabled = removableList.isEmpty();

        int removeBgColor;
        int removeTextColor;
        if (removeDisabled) {
            removeBgColor = 0xFF202020;
            removeTextColor = 0xFF606060;
        } else {
            removeBgColor = removeHovered ? 0xFF604040 : 0xFF503030;
            removeTextColor = 0xFFFFAAAA;
        }
        graphics.fill(removeBtnX, dropdownY, removeBtnX + removeBtnWidth, dropdownY + DROPDOWN_HEIGHT, removeBgColor);
        graphics.renderOutline(removeBtnX, dropdownY, removeBtnWidth, DROPDOWN_HEIGHT, removeDisabled ? 0xFF404040 : 0xFFA06060);
        int removeTextWidth = font.width("REMOVE");
        graphics.drawString(font, "REMOVE", removeBtnX + (removeBtnWidth - removeTextWidth) / 2, dropdownY + 3, removeTextColor, false);

        // Cache dropdown position for rendering the list later (on top of everything)
        cachedDropdownX = x + PADDING;
        cachedDropdownY = dropdownY;
        cachedDropdownWidth = dropdownWidth;

        // Calculate list position (opens UPWARD) and limit to screen bounds
        if (dropdownOpen && !removableList.isEmpty()) {
            int visibleItems = Math.min(removableList.size(), DROPDOWN_MAX_ITEMS);
            int listHeight = visibleItems * DROPDOWN_HEIGHT;
            // List goes upward from dropdown, with scroll if needed
            cachedDropdownListY = dropdownY - listHeight;
            cachedDropdownListHeight = listHeight;

            // Ensure dropdown doesn't go above screen
            if (cachedDropdownListY < 0) {
                cachedDropdownListY = 0;
                cachedDropdownListHeight = dropdownY; // Limit to space available
            }
        }

        currentY += DROPDOWN_HEIGHT + PADDING;

        // Mode toggle button
        String modeText = "Mode: " + pm.getMode();
        int modeBtnY = currentY;
        boolean modeHovered = effectiveMouseX >= x + PADDING && effectiveMouseX < x + WIDTH - PADDING &&
                             effectiveMouseY >= modeBtnY && effectiveMouseY < modeBtnY + BUTTON_HEIGHT;

        int modeBgColor = modeHovered ? 0xFF505050 : 0xFF303030;
        int modeAccentColor = pm.getMode().equals("ADD") ? 0xFF60A060 : 0xFFA06060;
        graphics.fill(x + PADDING, modeBtnY, x + WIDTH - PADDING, modeBtnY + BUTTON_HEIGHT, modeBgColor);
        graphics.renderOutline(x + PADDING, modeBtnY, WIDTH - PADDING * 2, BUTTON_HEIGHT, modeAccentColor);
        int modeTextWidth = font.width(modeText);
        graphics.drawString(font, modeText, x + (WIDTH - modeTextWidth) / 2, modeBtnY + 4,
                           pm.getMode().equals("ADD") ? 0xFF88FF88 : 0xFFFF8888, false);
        currentY += BUTTON_HEIGHT + PADDING;

        // Selection section - label and all buttons on same line
        String selLabel = "Selection:";
        int selLabelWidth = font.width(selLabel);
        graphics.drawString(font, selLabel, x + PADDING, currentY + 4, 0xFF808080, false);

        // Selection buttons: 1, 2, X, ↓, ↓Air - with custom widths
        // Buttons 0-2 (1, 2, X) are small, button 3 (↓) is smaller, button 4 (↓Air) is wider
        String[] selBtns = {"1", "2", "\u2715", "\u2193", "\u2193Air"};
        int buttonsStartX = x + PADDING + selLabelWidth + 4;
        int availableWidth = WIDTH - PADDING - (buttonsStartX - x);
        int gapCount = 4; // 5 buttons = 4 gaps
        int totalGaps = gapCount * 2;
        // Custom widths: small (1,2,X), smaller (↓), wider (↓Air)
        int smallBtnWidth = 14;  // for 1, 2, X
        int arrowBtnWidth = 12;  // for ↓ (smaller)
        int airBtnWidth = availableWidth - totalGaps - (smallBtnWidth * 3) - arrowBtnWidth;  // remaining for ↓Air
        int[] btnWidths = {smallBtnWidth, smallBtnWidth, smallBtnWidth, arrowBtnWidth, airBtnWidth};

        int btnX = buttonsStartX;
        for (int i = 0; i < selBtns.length; i++) {
            int thisWidth = btnWidths[i];
            int btnY = currentY;

            boolean btnHovered = effectiveMouseX >= btnX && effectiveMouseX < btnX + thisWidth &&
                                effectiveMouseY >= btnY && effectiveMouseY < btnY + BUTTON_HEIGHT;

            int bgColor = btnHovered ? 0xFF505050 : 0xFF303030;
            graphics.fill(btnX, btnY, btnX + thisWidth, btnY + BUTTON_HEIGHT, bgColor);
            graphics.renderOutline(btnX, btnY, thisWidth, BUTTON_HEIGHT, 0xFF606060);

            int textWidth = font.width(selBtns[i]);
            graphics.drawString(font, selBtns[i], btnX + (thisWidth - textWidth) / 2, btnY + 4, 0xFFFFFFFF, false);

            btnX += thisWidth + 2;
        }

        // Entrance anchor row (only for base construction editing, not rooms)
        if (!inRoomEditing) {
            currentY += BUTTON_HEIGHT + PADDING;

            // Home icon (⌂ or similar)
            String homeIcon = "\u2302";  // ⌂
            int homeIconWidth = font.width(homeIcon);
            graphics.drawString(font, homeIcon, x + PADDING, currentY + 4, 0xFF808080, false);

            // Coordinates display (x, y, z, yaw)
            int coordsX = x + PADDING + homeIconWidth + 4;
            String coordsText;
            if (pm.hasEntrance()) {
                int[] entrance = pm.getEntrance();
                int yawInt = Math.round(pm.getEntranceYaw());
                coordsText = entrance[0] + ", " + entrance[1] + ", " + entrance[2] + ", " + yawInt + "\u00B0";
            } else {
                coordsText = "- - -";
            }
            int coordsWidth = font.width(coordsText);
            graphics.drawString(font, coordsText, coordsX, currentY + 4, pm.hasEntrance() ? 0xFFCCCCCC : 0xFF666666, false);

            // Check if player is at the entrance position (only coordinates, not yaw)
            boolean playerAtEntrance = false;
            if (pm.hasEntrance()) {
                var player = Minecraft.getInstance().player;
                if (player != null) {
                    int[] entrance = pm.getEntrance();
                    int playerX = player.blockPosition().getX();
                    int playerY = player.blockPosition().getY();
                    int playerZ = player.blockPosition().getZ();
                    // Player is at entrance if same block position
                    playerAtEntrance = playerX == entrance[0] && playerY == entrance[1] && playerZ == entrance[2];
                }
            }

            // SET and TP buttons (right-aligned)
            int setBtnWidth = 24;
            int tpBtnWidth = 20;
            int gap = 2;
            int tpBtnX = x + WIDTH - PADDING - tpBtnWidth;
            int setBtnX = tpBtnX - gap - setBtnWidth;

            // SET button (disabled if player is already at entrance)
            boolean setDisabled = playerAtEntrance;
            boolean setHovered = !setDisabled && effectiveMouseX >= setBtnX && effectiveMouseX < setBtnX + setBtnWidth &&
                                effectiveMouseY >= currentY && effectiveMouseY < currentY + BUTTON_HEIGHT;
            int setBgColor = setDisabled ? 0xFF202020 : (setHovered ? 0xFF405040 : 0xFF304030);
            int setTextColor = setDisabled ? 0xFF606060 : 0xFF88FF88;
            int setBorderColor = setDisabled ? 0xFF404040 : 0xFF608060;
            graphics.fill(setBtnX, currentY, setBtnX + setBtnWidth, currentY + BUTTON_HEIGHT, setBgColor);
            graphics.renderOutline(setBtnX, currentY, setBtnWidth, BUTTON_HEIGHT, setBorderColor);
            int setTextWidth = font.width("SET");
            graphics.drawString(font, "SET", setBtnX + (setBtnWidth - setTextWidth) / 2, currentY + 4, setTextColor, false);

            // TP button (disabled if no entrance or player already at entrance)
            boolean tpDisabled = !pm.hasEntrance() || playerAtEntrance;
            boolean tpHovered = !tpDisabled && effectiveMouseX >= tpBtnX && effectiveMouseX < tpBtnX + tpBtnWidth &&
                               effectiveMouseY >= currentY && effectiveMouseY < currentY + BUTTON_HEIGHT;
            int tpBgColor = tpDisabled ? 0xFF202020 : (tpHovered ? 0xFF405060 : 0xFF304050);
            int tpTextColor = tpDisabled ? 0xFF606060 : 0xFF88CCFF;
            int tpBorderColor = tpDisabled ? 0xFF404040 : 0xFF6080A0;
            graphics.fill(tpBtnX, currentY, tpBtnX + tpBtnWidth, currentY + BUTTON_HEIGHT, tpBgColor);
            graphics.renderOutline(tpBtnX, currentY, tpBtnWidth, BUTTON_HEIGHT, tpBorderColor);
            int tpTextWidth = font.width("TP");
            graphics.drawString(font, "TP", tpBtnX + (tpBtnWidth - tpTextWidth) / 2, currentY + 4, tpTextColor, false);
        }

        // === BOTTOM SECTION: Render from the bottom of the panel ===
        int bottomY = y + HEIGHT - PADDING;

        // DONE button (full width, at very bottom)
        int doneY = bottomY - BUTTON_HEIGHT;
        int doneBtnWidth = WIDTH - PADDING * 2;
        boolean doneHovered = effectiveMouseX >= x + PADDING && effectiveMouseX < x + WIDTH - PADDING &&
                             effectiveMouseY >= doneY && effectiveMouseY < doneY + BUTTON_HEIGHT;

        int doneBgColor = doneHovered ? 0xFF604040 : 0xFF403030;
        graphics.fill(x + PADDING, doneY, x + WIDTH - PADDING, doneY + BUTTON_HEIGHT, doneBgColor);
        graphics.renderOutline(x + PADDING, doneY, doneBtnWidth, BUTTON_HEIGHT, 0xFF806060);

        int doneTextWidth = font.width("DONE");
        graphics.drawString(font, "DONE", x + (WIDTH - doneTextWidth) / 2, doneY + 4, 0xFFFFAAAA, false);

        // Cache done button Y for click handling
        cachedDoneY = doneY;

        // Room dropdown with EDIT/DEL buttons (only when NOT in room editing) - above DONE
        if (!inRoomEditing) {
            java.util.List<PanelManager.RoomInfo> roomListData = pm.getRoomList();

            // Room dropdown (opens upward)
            int roomDropdownWidth = WIDTH - PADDING * 2 - 50 - 25 - 4; // Leave space for EDIT and DEL buttons
            int editBtnWidth = 25;
            int delBtnWidth = 50;

            int roomDropdownY = doneY - DROPDOWN_HEIGHT - 2;

            // Determine selected item text
            String selectedRoomText;
            if (selectedRoomIndex == 0) {
                selectedRoomText = "+++ new +++";
            } else {
                int roomIdx = selectedRoomIndex - 1;
                if (roomIdx < roomListData.size()) {
                    PanelManager.RoomInfo ri = roomListData.get(roomIdx);
                    selectedRoomText = truncate(ri.name() + " (" + ri.blockCount() + "b)", roomDropdownWidth - 15, font);
                } else {
                    selectedRoomText = "+++ new +++";
                    selectedRoomIndex = 0;
                }
            }

            boolean roomDropdownHovered = effectiveMouseX >= x + PADDING && effectiveMouseX < x + PADDING + roomDropdownWidth &&
                                          effectiveMouseY >= roomDropdownY && effectiveMouseY < roomDropdownY + DROPDOWN_HEIGHT;

            graphics.fill(x + PADDING, roomDropdownY, x + PADDING + roomDropdownWidth, roomDropdownY + DROPDOWN_HEIGHT,
                         roomDropdownHovered ? 0xFF404040 : 0xFF303030);
            graphics.renderOutline(x + PADDING, roomDropdownY, roomDropdownWidth, DROPDOWN_HEIGHT, 0xFF606060);
            int roomTextColor = selectedRoomIndex == 0 ? 0xFF88FF88 : 0xFFCCCCCC;
            graphics.drawString(font, selectedRoomText, x + PADDING + 3, roomDropdownY + 3, roomTextColor, false);
            graphics.drawString(font, roomDropdownOpen ? "\u25BC" : "\u25B2", x + PADDING + roomDropdownWidth - 10, roomDropdownY + 3, 0xFF888888, false);

            // Cache room dropdown position
            cachedRoomDropdownX = x + PADDING;
            cachedRoomDropdownY = roomDropdownY;
            cachedRoomDropdownWidth = roomDropdownWidth;
            int roomListCount = 1 + roomListData.size(); // +1 for "+++ new +++"
            int visibleRoomItems = Math.min(roomListCount, DROPDOWN_MAX_ITEMS);
            int roomListHeight = visibleRoomItems * DROPDOWN_HEIGHT;
            cachedRoomDropdownListY = roomDropdownY - roomListHeight;
            cachedRoomDropdownListHeight = roomListHeight;

            // EDIT button
            int editBtnX = x + PADDING + roomDropdownWidth + 2;
            boolean editHovered = effectiveMouseX >= editBtnX && effectiveMouseX < editBtnX + editBtnWidth &&
                                  effectiveMouseY >= roomDropdownY && effectiveMouseY < roomDropdownY + DROPDOWN_HEIGHT;
            int editBgColor = editHovered ? 0xFF404060 : 0xFF303050;
            graphics.fill(editBtnX, roomDropdownY, editBtnX + editBtnWidth, roomDropdownY + DROPDOWN_HEIGHT, editBgColor);
            graphics.renderOutline(editBtnX, roomDropdownY, editBtnWidth, DROPDOWN_HEIGHT, 0xFF6060A0);
            int editTextWidth = font.width("EDIT");
            graphics.drawString(font, "EDIT", editBtnX + (editBtnWidth - editTextWidth) / 2, roomDropdownY + 3, 0xFFAAAAFF, false);

            // DEL button (disabled for "+++ new +++")
            int delBtnX = editBtnX + editBtnWidth + 2;
            boolean delDisabled = selectedRoomIndex == 0;
            boolean delHovered = !delDisabled && effectiveMouseX >= delBtnX && effectiveMouseX < delBtnX + delBtnWidth &&
                                 effectiveMouseY >= roomDropdownY && effectiveMouseY < roomDropdownY + DROPDOWN_HEIGHT;
            int delBgColor;
            int delTextColor;
            if (delDisabled) {
                delBgColor = 0xFF202020;
                delTextColor = 0xFF606060;
            } else {
                delBgColor = delHovered ? 0xFF604040 : 0xFF503030;
                delTextColor = 0xFFFFAAAA;
            }
            graphics.fill(delBtnX, roomDropdownY, delBtnX + delBtnWidth, roomDropdownY + DROPDOWN_HEIGHT, delBgColor);
            graphics.renderOutline(delBtnX, roomDropdownY, delBtnWidth, DROPDOWN_HEIGHT, delDisabled ? 0xFF404040 : 0xFFA06060);
            int delTextWidth = font.width("DEL");
            graphics.drawString(font, "DEL", delBtnX + (delBtnWidth - delTextWidth) / 2, roomDropdownY + 3, delTextColor, false);
        }

        // Render block/entity dropdown list ON TOP of other elements (opens upward)
        if (dropdownOpen && !removableList.isEmpty()) {
            renderDropdownList(graphics, mouseX, mouseY, font, removableList);
        }

        // Render room dropdown list ON TOP of other elements (opens upward)
        if (roomDropdownOpen && !inRoomEditing) {
            renderRoomDropdownList(graphics, mouseX, mouseY, font, pm.getRoomList());
        }

        // Render language dropdown list ON TOP of other elements (opens downward)
        if (langDropdownOpen) {
            renderLangDropdownList(graphics, mouseX, mouseY, font);
        }

        // Render short desc modal if open
        if (shortDescModalOpen) {
            renderShortDescModal(graphics, mouseX, mouseY, font);
        }

        // Render new room modal if open
        if (newRoomModalOpen) {
            renderNewRoomModal(graphics, mouseX, mouseY, font);
        }
    }

    /**
     * Render the language dropdown list on top of other elements.
     */
    private void renderLangDropdownList(GuiGraphics graphics, int mouseX, int mouseY, Font font) {
        int listX = cachedLangDropdownX;
        int listY = cachedLangDropdownListY;
        int listW = cachedLangDropdownWidth;
        int listH = cachedLangDropdownListHeight;

        // Background
        graphics.fill(listX, listY, listX + listW, listY + listH, 0xF0202040);
        graphics.renderOutline(listX, listY, listW, listH, 0xFF505080);

        int itemY = listY + 1;
        for (LangInfo lang : SUPPORTED_LANGUAGES) {
            boolean itemHovered = mouseX >= listX && mouseX < listX + listW &&
                                  mouseY >= itemY && mouseY < itemY + LINE_HEIGHT + 1;
            boolean isSelected = lang.id().equals(selectedLangId);

            if (itemHovered) {
                graphics.fill(listX + 1, itemY, listX + listW - 1, itemY + LINE_HEIGHT + 1, 0xFF404070);
            }
            if (isSelected) {
                graphics.fill(listX + 1, itemY, listX + listW - 1, itemY + LINE_HEIGHT + 1, 0xFF505090);
            }

            String itemText = lang.displayName() + getLangIndicator(lang.id());
            graphics.drawString(font, itemText, listX + 3, itemY + 1, isSelected ? 0xFFFFFFFF : 0xFFCCCCCC, false);
            itemY += LINE_HEIGHT + 1;
        }
    }

    /**
     * Render the dropdown list on top of other elements.
     * The list opens UPWARD from the dropdown button.
     */
    private void renderDropdownList(GuiGraphics graphics, int mouseX, int mouseY, Font font,
                                    java.util.List<RemovableItem> itemList) {
        int visibleItems = cachedDropdownListHeight / DROPDOWN_HEIGHT;
        if (visibleItems <= 0) return;

        // Ensure we don't show more items than available
        visibleItems = Math.min(visibleItems, itemList.size());

        // Background for dropdown list
        graphics.fill(cachedDropdownX, cachedDropdownListY,
                     cachedDropdownX + cachedDropdownWidth,
                     cachedDropdownListY + cachedDropdownListHeight, 0xF0202020);
        graphics.renderOutline(cachedDropdownX, cachedDropdownListY,
                              cachedDropdownWidth, cachedDropdownListHeight, 0xFF606060);

        // Render items (from bottom to top visually, but index order)
        for (int i = 0; i < visibleItems && (i + dropdownScrollOffset) < itemList.size(); i++) {
            int idx = i + dropdownScrollOffset;
            RemovableItem item = itemList.get(idx);
            // Items are rendered from top of list area downward
            int itemY = cachedDropdownListY + i * DROPDOWN_HEIGHT;

            boolean itemHovered = mouseX >= cachedDropdownX && mouseX < cachedDropdownX + cachedDropdownWidth &&
                                  mouseY >= itemY && mouseY < itemY + DROPDOWN_HEIGHT;
            boolean itemSelected = idx == selectedBlockIndex;

            if (itemSelected) {
                graphics.fill(cachedDropdownX + 1, itemY,
                             cachedDropdownX + cachedDropdownWidth - 1, itemY + DROPDOWN_HEIGHT, 0xFF404060);
            } else if (itemHovered) {
                graphics.fill(cachedDropdownX + 1, itemY,
                             cachedDropdownX + cachedDropdownWidth - 1, itemY + DROPDOWN_HEIGHT, 0xFF353535);
            }

            // Display format: "Name extra" - entities shown with yellow text
            String itemText = truncate(item.displayName() + " " + item.extra(), cachedDropdownWidth - 15, font);
            int textColor = item.isEntity() ? 0xFFFFFF88 : 0xFFCCCCCC;  // Yellow for entities
            graphics.drawString(font, itemText, cachedDropdownX + 3, itemY + 3, textColor, false);
        }

        // Draw scrollbar if there are more items than visible
        if (itemList.size() > visibleItems) {
            int scrollbarWidth = 4;
            int scrollbarX = cachedDropdownX + cachedDropdownWidth - scrollbarWidth - 1;

            // Scrollbar track
            graphics.fill(scrollbarX, cachedDropdownListY + 1,
                         scrollbarX + scrollbarWidth, cachedDropdownListY + cachedDropdownListHeight - 1, 0xFF303030);

            // Scrollbar thumb
            int maxScroll = itemList.size() - visibleItems;
            int thumbHeight = Math.max(10, (visibleItems * cachedDropdownListHeight) / itemList.size());
            int thumbY = cachedDropdownListY + 1 +
                        (int)((cachedDropdownListHeight - 2 - thumbHeight) * ((float)dropdownScrollOffset / maxScroll));
            graphics.fill(scrollbarX, thumbY, scrollbarX + scrollbarWidth, thumbY + thumbHeight, 0xFF606060);
        }
    }

    /**
     * Render the room dropdown list on top of other elements (opens upward).
     */
    private void renderRoomDropdownList(GuiGraphics graphics, int mouseX, int mouseY, Font font,
                                        java.util.List<PanelManager.RoomInfo> roomList) {
        int totalItems = 1 + roomList.size(); // +1 for "+++ new +++"
        int visibleItems = cachedRoomDropdownListHeight / DROPDOWN_HEIGHT;
        if (visibleItems <= 0) return;
        visibleItems = Math.min(visibleItems, totalItems);

        // Background
        graphics.fill(cachedRoomDropdownX, cachedRoomDropdownListY,
                     cachedRoomDropdownX + cachedRoomDropdownWidth,
                     cachedRoomDropdownListY + cachedRoomDropdownListHeight, 0xF0202020);
        graphics.renderOutline(cachedRoomDropdownX, cachedRoomDropdownListY,
                              cachedRoomDropdownWidth, cachedRoomDropdownListHeight, 0xFF606060);

        // Render items
        for (int i = 0; i < visibleItems; i++) {
            int idx = i;
            int itemY = cachedRoomDropdownListY + i * DROPDOWN_HEIGHT;

            boolean itemHovered = mouseX >= cachedRoomDropdownX && mouseX < cachedRoomDropdownX + cachedRoomDropdownWidth &&
                                  mouseY >= itemY && mouseY < itemY + DROPDOWN_HEIGHT;
            boolean itemSelected = idx == selectedRoomIndex;

            if (itemSelected) {
                graphics.fill(cachedRoomDropdownX + 1, itemY,
                             cachedRoomDropdownX + cachedRoomDropdownWidth - 1, itemY + DROPDOWN_HEIGHT, 0xFF404060);
            } else if (itemHovered) {
                graphics.fill(cachedRoomDropdownX + 1, itemY,
                             cachedRoomDropdownX + cachedRoomDropdownWidth - 1, itemY + DROPDOWN_HEIGHT, 0xFF353535);
            }

            String itemText;
            int itemColor;
            if (idx == 0) {
                itemText = "+++ new +++";
                itemColor = 0xFF88FF88;
            } else {
                int roomIdx = idx - 1;
                if (roomIdx < roomList.size()) {
                    PanelManager.RoomInfo ri = roomList.get(roomIdx);
                    itemText = truncate(ri.name() + " (" + ri.blockCount() + "b)", cachedRoomDropdownWidth - 10, font);
                    itemColor = 0xFFCCCCCC;
                } else {
                    itemText = "???";
                    itemColor = 0xFF888888;
                }
            }
            graphics.drawString(font, itemText, cachedRoomDropdownX + 3, itemY + 3, itemColor, false);
        }
    }

    /**
     * Render the new room modal dialog.
     */
    private void renderNewRoomModal(GuiGraphics graphics, int mouseX, int mouseY, Font font) {
        Minecraft mc = Minecraft.getInstance();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        int modalW = 250;
        int modalH = 100;
        int modalX = (screenW - modalW) / 2;
        int modalY = (screenH - modalH) / 2;

        // Background
        graphics.fill(modalX, modalY, modalX + modalW, modalY + modalH, 0xF0101010);
        graphics.renderOutline(modalX, modalY, modalW, modalH, 0xFF606060);

        // Title
        graphics.drawString(font, "Create new room", modalX + 5, modalY + 5, 0xFFFFAA00, false);

        // Name label and input field using EditBoxHelper
        graphics.drawString(font, "Name:", modalX + 5, modalY + 22, 0xFF808080, false);
        int inputX = modalX + 40;
        int inputW = modalW - 45;

        // Position and render the name box
        newRoomNameBox.setPosition(inputX, modalY + 19);
        newRoomNameBox.setWidth(inputW);
        newRoomNameBox.render(graphics, mouseX, mouseY, 0);

        // Update ID preview when value changes
        String currentName = newRoomNameBox.getValue();
        newRoomIdPreview = generateRoomId(currentName);

        // ID preview
        graphics.drawString(font, "ID:", modalX + 5, modalY + 38, 0xFF808080, false);
        graphics.drawString(font, newRoomIdPreview.isEmpty() ? "(auto-generated)" : newRoomIdPreview, inputX + 2, modalY + 38, 0xFF88FF88, false);

        // Buttons
        int btnW = 60;
        int btnH = 18;
        int btnY = modalY + modalH - btnH - 8;

        // CREATE button
        int createBtnX = modalX + modalW / 2 - btnW - 10;
        boolean createHovered = mouseX >= createBtnX && mouseX < createBtnX + btnW &&
                               mouseY >= btnY && mouseY < btnY + btnH;
        boolean createDisabled = currentName.isEmpty();
        int createBgColor = createDisabled ? 0xFF202020 : (createHovered ? 0xFF406040 : 0xFF305030);
        int createTextColor = createDisabled ? 0xFF606060 : 0xFF88FF88;
        graphics.fill(createBtnX, btnY, createBtnX + btnW, btnY + btnH, createBgColor);
        graphics.renderOutline(createBtnX, btnY, btnW, btnH, createDisabled ? 0xFF404040 : 0xFF60A060);
        int createTextW = font.width("CREATE");
        graphics.drawString(font, "CREATE", createBtnX + (btnW - createTextW) / 2, btnY + 5, createTextColor, false);

        // CANCEL button
        int cancelBtnX = modalX + modalW / 2 + 10;
        boolean cancelHovered = mouseX >= cancelBtnX && mouseX < cancelBtnX + btnW &&
                               mouseY >= btnY && mouseY < btnY + btnH;
        graphics.fill(cancelBtnX, btnY, cancelBtnX + btnW, btnY + btnH, cancelHovered ? 0xFF604040 : 0xFF503030);
        graphics.renderOutline(cancelBtnX, btnY, btnW, btnH, 0xFFA06060);
        int cancelTextW = font.width("CANCEL");
        graphics.drawString(font, "CANCEL", cancelBtnX + (btnW - cancelTextW) / 2, btnY + 5, 0xFFFF8888, false);
    }

    private void renderShortDescModal(GuiGraphics graphics, int mouseX, int mouseY, Font font) {
        Minecraft mc = Minecraft.getInstance();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        int modalW = 400;
        int modalH = 240;
        int modalX = (screenW - modalW) / 2;
        int modalY = (screenH - modalH) / 2;

        // Background
        graphics.fill(modalX, modalY, modalX + modalW, modalY + modalH, 0xF0101010);
        graphics.renderOutline(modalX, modalY, modalW, modalH, 0xFF606060);

        // Title with language indicator
        String langDisplay = getLangDisplayName(selectedLangId);
        String modalTitle = "Edit Short Description (" + langDisplay + ")";
        graphics.drawString(font, modalTitle, modalX + 5, modalY + 5, 0xFFFFAA00, false);

        // Text area (leave space for scrollbar)
        int scrollbarW = 8;
        int textAreaX = modalX + 5;
        int textAreaY = modalY + 18;
        int textAreaW = modalW - 10 - scrollbarW - 2;
        int textAreaH = modalH - 55;
        int lineHeight = 10;

        graphics.fill(textAreaX, textAreaY, textAreaX + textAreaW, textAreaY + textAreaH, 0xFF202020);
        graphics.renderOutline(textAreaX, textAreaY, textAreaW, textAreaH, 0xFF404040);

        // Calculate wrapped lines and cursor position using character-by-character tracking
        java.util.List<String> lines = wrapText(shortDescText, textAreaW - 6, font);
        int totalLines = lines.size();
        int visibleLines = (textAreaH - 6) / lineHeight;

        // Calculate cursor line by tracking character positions
        int cursorLine = 0;
        int cursorPosInLine = 0;
        int charIndex = 0;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineStart = charIndex;
            int lineEnd = charIndex + line.length();

            if (shortDescCursorPos >= lineStart && shortDescCursorPos <= lineEnd) {
                cursorLine = i;
                cursorPosInLine = shortDescCursorPos - lineStart;
                break;
            }

            charIndex = lineEnd;
            // Check if next character in original text is a newline or space (word wrap separator)
            if (charIndex < shortDescText.length()) {
                char nextChar = shortDescText.charAt(charIndex);
                if (nextChar == '\n' || nextChar == ' ') {
                    charIndex++; // Skip the separator
                }
            }

            // If cursor is at very end
            if (i == lines.size() - 1) {
                cursorLine = i;
                cursorPosInLine = line.length();
            }
        }

        // Handle empty text case
        if (lines.isEmpty()) {
            cursorLine = 0;
            cursorPosInLine = 0;
        }

        // Auto-scroll to keep cursor visible
        if (cursorLine < shortDescScrollOffset) {
            shortDescScrollOffset = cursorLine;
        } else if (cursorLine >= shortDescScrollOffset + visibleLines) {
            shortDescScrollOffset = cursorLine - visibleLines + 1;
        }

        // Clamp scroll offset
        int maxScroll = Math.max(0, totalLines - visibleLines);
        shortDescScrollOffset = Math.max(0, Math.min(shortDescScrollOffset, maxScroll));

        // Render visible lines with selection highlighting
        int lineY = textAreaY + 3;
        int selStart = Math.min(shortDescSelectionStart, shortDescSelectionEnd);
        int selEnd = Math.max(shortDescSelectionStart, shortDescSelectionEnd);
        boolean hasSelection = shortDescSelectionStart >= 0 && shortDescSelectionEnd >= 0 && selStart != selEnd;

        // Build character position map for each line
        int[] lineStartPositions = new int[lines.size()];
        int[] lineEndPositions = new int[lines.size()];
        int charPos = 0;
        for (int i = 0; i < lines.size(); i++) {
            lineStartPositions[i] = charPos;
            charPos += lines.get(i).length();
            lineEndPositions[i] = charPos;
            // Account for separator (newline or space between wrapped lines)
            if (charPos < shortDescText.length()) {
                char sep = shortDescText.charAt(charPos);
                if (sep == '\n' || sep == ' ') {
                    charPos++;
                }
            }
        }

        for (int i = shortDescScrollOffset; i < lines.size() && i < shortDescScrollOffset + visibleLines; i++) {
            String line = lines.get(i);
            int lineStartPos = lineStartPositions[i];

            if (hasSelection) {
                // Check if this line intersects with selection
                int lineEndPos = lineStartPos + line.length();
                if (selStart < lineEndPos && selEnd > lineStartPos) {
                    // Calculate selection range within this line
                    int localSelStart = Math.max(0, selStart - lineStartPos);
                    int localSelEnd = Math.min(line.length(), selEnd - lineStartPos);

                    // Draw selection highlight
                    String beforeSel = line.substring(0, localSelStart);
                    String selected = line.substring(localSelStart, localSelEnd);
                    int selX1 = textAreaX + 3 + font.width(beforeSel);
                    int selX2 = selX1 + font.width(selected);
                    graphics.fill(selX1, lineY, selX2, lineY + lineHeight, 0xFF3060A0);
                }
            }

            graphics.drawString(font, line, textAreaX + 3, lineY, 0xFFCCCCCC, false);
            lineY += lineHeight;
        }

        // Draw cursor if visible
        if (System.currentTimeMillis() % 1000 < 500) {
            int cursorVisibleLine = cursorLine - shortDescScrollOffset;
            if (cursorVisibleLine >= 0 && cursorVisibleLine < visibleLines) {
                int cursorY = textAreaY + 3 + (cursorVisibleLine * lineHeight);
                String lineBeforeCursor = cursorLine < lines.size()
                    ? lines.get(cursorLine).substring(0, Math.min(cursorPosInLine, lines.get(cursorLine).length()))
                    : "";
                int cursorX = textAreaX + 3 + font.width(lineBeforeCursor);
                graphics.fill(cursorX, cursorY, cursorX + 1, cursorY + 9, 0xFFFFFFFF);
            }
        }

        // Draw scrollbar if needed
        if (totalLines > visibleLines) {
            int scrollbarX = textAreaX + textAreaW + 2;
            int scrollbarTrackH = textAreaH;

            // Scrollbar track
            graphics.fill(scrollbarX, textAreaY, scrollbarX + scrollbarW, textAreaY + scrollbarTrackH, 0xFF303030);

            // Scrollbar thumb
            float thumbRatio = (float) visibleLines / totalLines;
            int thumbH = Math.max(20, (int) (scrollbarTrackH * thumbRatio));
            float scrollRatio = maxScroll > 0 ? (float) shortDescScrollOffset / maxScroll : 0;
            int thumbY = textAreaY + (int) ((scrollbarTrackH - thumbH) * scrollRatio);

            boolean thumbHovered = mouseX >= scrollbarX && mouseX < scrollbarX + scrollbarW &&
                                   mouseY >= thumbY && mouseY < thumbY + thumbH;
            graphics.fill(scrollbarX, thumbY, scrollbarX + scrollbarW, thumbY + thumbH,
                         thumbHovered ? 0xFF606060 : 0xFF505050);
        }

        // Character count
        String charCount = shortDescText.length() + "/512";
        int countColor = shortDescText.length() > 512 ? 0xFFFF5555 : 0xFF888888;
        graphics.drawString(font, charCount, modalX + modalW - font.width(charCount) - 5, textAreaY + textAreaH + 3, countColor, false);

        // Buttons: SAVE and CANCEL
        int btnW = 80;
        int btnH = 20;
        int btnY = modalY + modalH - btnH - 8;

        // SAVE button
        int saveBtnX = modalX + modalW / 2 - btnW - 10;
        boolean saveHovered = mouseX >= saveBtnX && mouseX < saveBtnX + btnW &&
                             mouseY >= btnY && mouseY < btnY + btnH;
        graphics.fill(saveBtnX, btnY, saveBtnX + btnW, btnY + btnH, saveHovered ? 0xFF406040 : 0xFF305030);
        graphics.renderOutline(saveBtnX, btnY, btnW, btnH, 0xFF60A060);
        int saveTextW = font.width("SAVE");
        graphics.drawString(font, "SAVE", saveBtnX + (btnW - saveTextW) / 2, btnY + 6, 0xFF88FF88, false);

        // CANCEL button
        int cancelBtnX = modalX + modalW / 2 + 10;
        boolean cancelHovered = mouseX >= cancelBtnX && mouseX < cancelBtnX + btnW &&
                               mouseY >= btnY && mouseY < btnY + btnH;
        graphics.fill(cancelBtnX, btnY, cancelBtnX + btnW, btnY + btnH, cancelHovered ? 0xFF604040 : 0xFF503030);
        graphics.renderOutline(cancelBtnX, btnY, btnW, btnH, 0xFFA06060);
        int cancelTextW = font.width("CANCEL");
        graphics.drawString(font, "CANCEL", cancelBtnX + (btnW - cancelTextW) / 2, btnY + 6, 0xFFFF8888, false);
    }

    private java.util.List<String> wrapText(String text, int maxWidth, Font font) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (text.isEmpty()) {
            return lines;
        }

        // Split by explicit newlines first
        String[] paragraphs = text.split("\n", -1);
        for (String paragraph : paragraphs) {
            if (paragraph.isEmpty()) {
                lines.add("");
                continue;
            }

            StringBuilder currentLine = new StringBuilder();
            for (String word : paragraph.split(" ", -1)) {
                if (currentLine.length() > 0) {
                    String testLine = currentLine + " " + word;
                    if (font.width(testLine) <= maxWidth) {
                        currentLine.append(" ").append(word);
                    } else {
                        lines.add(currentLine.toString());
                        // Handle very long words that exceed maxWidth
                        if (font.width(word) > maxWidth) {
                            // Break long word into chunks
                            StringBuilder chunk = new StringBuilder();
                            for (char c : word.toCharArray()) {
                                if (font.width(chunk.toString() + c) > maxWidth) {
                                    lines.add(chunk.toString());
                                    chunk = new StringBuilder();
                                }
                                chunk.append(c);
                            }
                            currentLine = chunk;
                        } else {
                            currentLine = new StringBuilder(word);
                        }
                    }
                } else {
                    // Handle very long words at start of line
                    if (font.width(word) > maxWidth) {
                        StringBuilder chunk = new StringBuilder();
                        for (char c : word.toCharArray()) {
                            if (font.width(chunk.toString() + c) > maxWidth) {
                                lines.add(chunk.toString());
                                chunk = new StringBuilder();
                            }
                            chunk.append(c);
                        }
                        currentLine = chunk;
                    } else {
                        currentLine.append(word);
                    }
                }
            }
            if (currentLine.length() > 0) {
                lines.add(currentLine.toString());
            }
        }
        return lines;
    }

    private String truncate(String text, int maxWidth, Font font) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int ellipsisWidth = font.width(ellipsis);
        int availableWidth = maxWidth - ellipsisWidth;

        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (font.width(sb.toString() + c) > availableWidth) {
                break;
            }
            sb.append(c);
        }
        return sb + ellipsis;
    }

    /**
     * Move cursor up or down by the specified number of lines, preserving column position.
     */
    private void moveCursorVertically(int lineDelta) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        int modalW = 400;
        int modalH = 240;
        int scrollbarW = 8;
        int textAreaW = modalW - 10 - scrollbarW - 2;

        java.util.List<String> lines = wrapText(shortDescText, textAreaW - 6, font);
        if (lines.isEmpty()) return;

        // Find current line and position within line
        int currentLine = 0;
        int currentCol = 0;
        int charIndex = 0;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineStart = charIndex;
            int lineEnd = charIndex + line.length();

            if (shortDescCursorPos >= lineStart && shortDescCursorPos <= lineEnd) {
                currentLine = i;
                currentCol = shortDescCursorPos - lineStart;
                break;
            }

            charIndex = lineEnd;
            if (charIndex < shortDescText.length()) {
                char nextChar = shortDescText.charAt(charIndex);
                if (nextChar == '\n' || nextChar == ' ') {
                    charIndex++;
                }
            }

            if (i == lines.size() - 1) {
                currentLine = i;
                currentCol = line.length();
            }
        }

        // Calculate target line
        int targetLine = Math.max(0, Math.min(lines.size() - 1, currentLine + lineDelta));
        if (targetLine == currentLine) return;

        // Find character position at start of target line
        charIndex = 0;
        for (int i = 0; i < targetLine; i++) {
            charIndex += lines.get(i).length();
            if (charIndex < shortDescText.length()) {
                char nextChar = shortDescText.charAt(charIndex);
                if (nextChar == '\n' || nextChar == ' ') {
                    charIndex++;
                }
            }
        }

        // Apply column position (clamped to target line length)
        String targetLineText = lines.get(targetLine);
        int targetCol = Math.min(currentCol, targetLineText.length());
        shortDescCursorPos = charIndex + targetCol;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;

        // Handle new room modal clicks first
        if (newRoomModalOpen) {
            return handleNewRoomModalClick(mouseX, mouseY);
        }

        // Handle short desc modal clicks first
        if (shortDescModalOpen) {
            return handleShortDescModalClick(mouseX, mouseY);
        }

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        // Handle language dropdown clicks using cached positions
        if (langDropdownOpen) {
            // Check if click is on dropdown list
            if (mouseX >= cachedLangDropdownX && mouseX < cachedLangDropdownX + cachedLangDropdownWidth &&
                mouseY >= cachedLangDropdownListY && mouseY < cachedLangDropdownListY + cachedLangDropdownListHeight) {
                // Find which language was clicked
                int itemY = cachedLangDropdownListY + 1;
                for (LangInfo lang : SUPPORTED_LANGUAGES) {
                    if (mouseY >= itemY && mouseY < itemY + LINE_HEIGHT + 1) {
                        if (!lang.id().equals(selectedLangId)) {
                            selectedLangId = lang.id();
                            updateFieldsForSelectedLanguage();
                        }
                        langDropdownOpen = false;
                        return true;
                    }
                    itemY += LINE_HEIGHT + 1;
                }
            }
            // Click outside dropdown - close it
            langDropdownOpen = false;
            return true;
        }

        // Handle room dropdown clicks using cached positions (must be before other elements)
        if (roomDropdownOpen) {
            PanelManager pm = PanelManager.getInstance();
            java.util.List<PanelManager.RoomInfo> roomListData = pm.getRoomList();
            int totalItems = 1 + roomListData.size();
            int visibleItems = Math.min(totalItems, DROPDOWN_MAX_ITEMS);
            int listHeight = visibleItems * DROPDOWN_HEIGHT;

            if (mouseX >= cachedRoomDropdownX && mouseX < cachedRoomDropdownX + cachedRoomDropdownWidth &&
                mouseY >= cachedRoomDropdownListY && mouseY < cachedRoomDropdownListY + listHeight) {
                // Click on room dropdown list item
                int itemIndex = (int)((mouseY - cachedRoomDropdownListY) / DROPDOWN_HEIGHT);
                if (itemIndex >= 0 && itemIndex < totalItems) {
                    selectedRoomIndex = itemIndex;
                    roomDropdownOpen = false;
                }
                return true;
            }
            // Click outside dropdown - close it
            roomDropdownOpen = false;
            return true;
        }

        // Check language dropdown button click
        String langDisplay = getLangDisplayName(selectedLangId) + getLangIndicator(selectedLangId);
        int langWidth = font.width(langDisplay) + 12;
        int langX = x + WIDTH - PADDING - langWidth;
        int langY = y + PADDING - 1;
        int langH = LINE_HEIGHT + 2;

        if (mouseX >= langX && mouseX < langX + langWidth &&
            mouseY >= langY && mouseY < langY + langH) {
            langDropdownOpen = !langDropdownOpen;
            return true;
        }

        // Check if click is within panel bounds
        if (mouseX < x || mouseX > x + WIDTH || mouseY < y || mouseY > y + HEIGHT) {
            // Click outside - cancel editing and restore values
            if (rdnsBox != null && rdnsBox.isFocused()) {
                rdnsBox.setValue(originalRdns);
                rdnsBox.setFocused(false);
            }
            if (nameBox != null && nameBox.isFocused()) {
                nameBox.setValue(originalName);
                nameBox.setFocused(false);
            }
            return false;
        }

        PanelManager pm = PanelManager.getInstance();
        boolean inRoomEditing = pm.isInRoom();

        int currentY = y + PADDING + LINE_HEIGHT + 2;

        // ID field click
        if (inRoomEditing) {
            // Room ID field (editable)
            if (roomIdBox.mouseClicked(mouseX, mouseY, button)) {
                // Save original value for cancel
                originalRoomId = pm.getCurrentRoomId();
                roomNameBox.setFocused(false);
                roomRenameError = "";  // Clear error on new edit
                return true;
            }
        } else {
            // Construction ID field (editable)
            if (rdnsBox.mouseClicked(mouseX, mouseY, button)) {
                // Save original value for cancel
                originalRdns = pm.getEditingConstructionId();
                nameBox.setFocused(false);
                return true;
            }
        }
        currentY += LINE_HEIGHT + 2;

        // Name field click
        if (inRoomEditing) {
            // Room name field (editable)
            if (roomNameBox.mouseClicked(mouseX, mouseY, button)) {
                // Save original value for cancel
                originalRoomName = pm.getCurrentRoomName();
                roomIdBox.setFocused(false);
                roomRenameError = "";  // Clear error on new edit
                return true;
            }
        } else {
            // Construction name field (editable)
            if (nameBox.mouseClicked(mouseX, mouseY, button)) {
                // Save original value for cancel
                originalName = pm.getEditingTitle();
                rdnsBox.setFocused(false);
                return true;
            }
        }

        // Cancel field editing if clicking elsewhere in panel
        if (rdnsBox.isFocused()) {
            rdnsBox.setValue(originalRdns);
            rdnsBox.setFocused(false);
        }
        if (nameBox.isFocused()) {
            nameBox.setValue(originalName);
            nameBox.setFocused(false);
        }
        if (roomIdBox != null && roomIdBox.isFocused()) {
            roomIdBox.setValue(originalRoomId);
            roomIdBox.setFocused(false);
        }
        if (roomNameBox != null && roomNameBox.isFocused()) {
            roomNameBox.setValue(originalRoomName);
            roomNameBox.setFocused(false);
        }

        currentY += LINE_HEIGHT + 2;

        // Skip room rename error row if present
        if (inRoomEditing && !roomRenameError.isEmpty()) {
            currentY += LINE_HEIGHT + 2;
        }

        // Desc button click - only for building editing, not room
        if (!inRoomEditing) {
            String descLabel = "Short Desc.: ";
            int descLabelWidth = font.width(descLabel);
            int descBtnX = x + PADDING + descLabelWidth;
            int descBtnWidth = WIDTH - PADDING * 2 - descLabelWidth;
            if (mouseX >= descBtnX && mouseX < descBtnX + descBtnWidth &&
                mouseY >= currentY && mouseY < currentY + LINE_HEIGHT) {
                shortDescModalOpen = true;
                shortDescCursorPos = shortDescText.length();
                shortDescScrollOffset = 0; // Reset scroll when opening modal
                return true;
            }
            currentY += LINE_HEIGHT + PADDING;
        }

        // Skip stats section (3 rows for both room and building editing)
        currentY += LINE_HEIGHT * 3 + PADDING;

        // Block/Entity removal section: dropdown + REMOVE button (moved before Mode)
        java.util.List<RemovableItem> removableList = buildRemovableList(PanelManager.getInstance());
        int dropdownWidth = WIDTH - PADDING * 2 - 50 - 2;
        int removeBtnWidth = 50;
        int dropdownY = currentY;

        // Handle dropdown open list clicks first (list opens UPWARD)
        if (dropdownOpen && !removableList.isEmpty()) {
            // Use cached positions from last render
            int visibleItems = cachedDropdownListHeight / DROPDOWN_HEIGHT;
            visibleItems = Math.min(visibleItems, removableList.size());

            if (mouseX >= cachedDropdownX && mouseX < cachedDropdownX + cachedDropdownWidth &&
                mouseY >= cachedDropdownListY && mouseY < cachedDropdownListY + cachedDropdownListHeight) {
                // Click on dropdown list item
                int itemIndex = (int)((mouseY - cachedDropdownListY) / DROPDOWN_HEIGHT) + dropdownScrollOffset;
                if (itemIndex >= 0 && itemIndex < removableList.size()) {
                    selectedBlockIndex = itemIndex;
                    dropdownOpen = false;
                }
                return true;
            }
        }

        // Dropdown toggle click
        if (mouseX >= x + PADDING && mouseX < x + PADDING + dropdownWidth &&
            mouseY >= dropdownY && mouseY < dropdownY + DROPDOWN_HEIGHT) {
            if (!removableList.isEmpty()) {
                dropdownOpen = !dropdownOpen;
                if (dropdownOpen) {
                    // Position scroll to show selected item
                    int visibleItems = Math.min(removableList.size(), DROPDOWN_MAX_ITEMS);
                    int maxScroll = Math.max(0, removableList.size() - visibleItems);
                    // Center the selected item if possible
                    dropdownScrollOffset = Math.max(0, Math.min(maxScroll, selectedBlockIndex - visibleItems / 2));
                }
            }
            return true;
        }

        // REMOVE button click
        int removeBtnX = x + PADDING + dropdownWidth + 2;
        if (mouseX >= removeBtnX && mouseX < removeBtnX + removeBtnWidth &&
            mouseY >= dropdownY && mouseY < dropdownY + DROPDOWN_HEIGHT) {
            if (!removableList.isEmpty() && selectedBlockIndex < removableList.size()) {
                RemovableItem item = removableList.get(selectedBlockIndex);
                // Send appropriate action based on item type
                String action = item.isEntity() ? "remove_entity" : "remove_block";
                ClientPlayNetworking.send(new GuiActionPacket(action, item.id(), ""));
                dropdownOpen = false;
                // Reset selection to first item if current was removed
                if (selectedBlockIndex >= removableList.size() - 1) {
                    selectedBlockIndex = Math.max(0, removableList.size() - 2);
                }
            }
            return true;
        }

        // Close dropdown if clicking elsewhere
        if (dropdownOpen) {
            dropdownOpen = false;
            return true;
        }

        currentY += DROPDOWN_HEIGHT + PADDING;

        // Mode button
        if (mouseY >= currentY && mouseY < currentY + BUTTON_HEIGHT &&
            mouseX >= x + PADDING && mouseX < x + WIDTH - PADDING) {
            // Toggle mode
            sendSelectionAction(SelectionKeyPacket.Action.MODE_TOGGLE);
            return true;
        }
        currentY += BUTTON_HEIGHT + PADDING;

        // Selection buttons - with custom widths matching render
        String selLabel = "Selection:";
        int selLabelWidth = Minecraft.getInstance().font.width(selLabel);
        int buttonsStartX = x + PADDING + selLabelWidth + 4;
        int availableWidth = WIDTH - PADDING - (buttonsStartX - x);
        int totalGaps = 4 * 2; // 5 buttons = 4 gaps
        // Custom widths: small (1,2,X), smaller (↓), wider (↓Air)
        int smallBtnWidth = 14;
        int arrowBtnWidth = 12;
        int airBtnWidth = availableWidth - totalGaps - (smallBtnWidth * 3) - arrowBtnWidth;
        int[] btnWidths = {smallBtnWidth, smallBtnWidth, smallBtnWidth, arrowBtnWidth, airBtnWidth};

        // Button 0: "1" (POS1)
        int btnX = buttonsStartX;
        if (mouseX >= btnX && mouseX < btnX + btnWidths[0] &&
            mouseY >= currentY && mouseY < currentY + BUTTON_HEIGHT) {
            Minecraft.getInstance().setScreen(null);
            sendSelectionAction(SelectionKeyPacket.Action.POS1);
            return true;
        }

        // Button 1: "2" (POS2)
        btnX += btnWidths[0] + 2;
        if (mouseX >= btnX && mouseX < btnX + btnWidths[1] &&
            mouseY >= currentY && mouseY < currentY + BUTTON_HEIGHT) {
            Minecraft.getInstance().setScreen(null);
            sendSelectionAction(SelectionKeyPacket.Action.POS2);
            return true;
        }

        // Button 2: "X" (CLEAR)
        btnX += btnWidths[1] + 2;
        if (mouseX >= btnX && mouseX < btnX + btnWidths[2] &&
            mouseY >= currentY && mouseY < currentY + BUTTON_HEIGHT) {
            sendSelectionAction(SelectionKeyPacket.Action.CLEAR);
            return true;
        }

        // Button 3: "↓" (APPLY)
        btnX += btnWidths[2] + 2;
        if (mouseX >= btnX && mouseX < btnX + btnWidths[3] &&
            mouseY >= currentY && mouseY < currentY + BUTTON_HEIGHT) {
            Minecraft.getInstance().setScreen(null);
            sendSelectionAction(SelectionKeyPacket.Action.APPLY);
            return true;
        }

        // Button 4: "↓Air" (APPLY ALL)
        btnX += btnWidths[3] + 2;
        if (mouseX >= btnX && mouseX < btnX + btnWidths[4] &&
            mouseY >= currentY && mouseY < currentY + BUTTON_HEIGHT) {
            Minecraft.getInstance().setScreen(null);
            sendSelectionAction(SelectionKeyPacket.Action.APPLYALL);
            return true;
        }

        // Entrance anchor row (only for base construction editing, not rooms)
        if (!inRoomEditing) {
            currentY += BUTTON_HEIGHT + PADDING;

            // Check if player is at the entrance position (only coordinates, not yaw)
            boolean playerAtEntrance = false;
            if (pm.hasEntrance()) {
                var player = Minecraft.getInstance().player;
                if (player != null) {
                    int[] entrance = pm.getEntrance();
                    int playerX = player.blockPosition().getX();
                    int playerY = player.blockPosition().getY();
                    int playerZ = player.blockPosition().getZ();
                    // Player is at entrance if same block position
                    playerAtEntrance = playerX == entrance[0] && playerY == entrance[1] && playerZ == entrance[2];
                }
            }

            // SET and TP buttons (right-aligned, same layout as render)
            int setBtnWidth = 24;
            int tpBtnWidth = 20;
            int gap = 2;
            int tpBtnX = x + WIDTH - PADDING - tpBtnWidth;
            int setBtnX = tpBtnX - gap - setBtnWidth;

            // SET button click (disabled if player already at entrance)
            if (!playerAtEntrance &&
                mouseX >= setBtnX && mouseX < setBtnX + setBtnWidth &&
                mouseY >= currentY && mouseY < currentY + BUTTON_HEIGHT) {
                // Send action to server to set entrance at player's current position
                ClientPlayNetworking.send(new GuiActionPacket("set_entrance", "", ""));
                return true;
            }

            // TP button click (disabled if no entrance or player already at entrance)
            if (pm.hasEntrance() && !playerAtEntrance &&
                mouseX >= tpBtnX && mouseX < tpBtnX + tpBtnWidth &&
                mouseY >= currentY && mouseY < currentY + BUTTON_HEIGHT) {
                // Send action to server to teleport player to entrance
                ClientPlayNetworking.send(new GuiActionPacket("tp_entrance", "", ""));
                return true;
            }
        }

        // === BOTTOM SECTION: Click handling using cached Y positions ===

        // DONE button (full width, at very bottom) - uses cachedDoneY
        if (mouseX >= x + PADDING && mouseX < x + WIDTH - PADDING &&
            mouseY >= cachedDoneY && mouseY < cachedDoneY + BUTTON_HEIGHT) {
            if (inRoomEditing) {
                // Exit room and return to building editing
                ClientPlayNetworking.send(new GuiActionPacket("room_exit", "", ""));
            } else {
                // Exit building editing completely
                ClientPlayNetworking.send(new GuiActionPacket("done", "", ""));
            }
            return true;
        }

        // Room dropdown with EDIT/DEL buttons (only when NOT in room editing) - uses cachedRoomDropdownY
        if (!inRoomEditing) {
            java.util.List<PanelManager.RoomInfo> roomListData = pm.getRoomList();
            int roomDropdownWidth = WIDTH - PADDING * 2 - 50 - 25 - 4;
            int editBtnWidth = 25;
            int delBtnWidth = 50;

            // Room dropdown toggle click
            if (mouseX >= x + PADDING && mouseX < x + PADDING + roomDropdownWidth &&
                mouseY >= cachedRoomDropdownY && mouseY < cachedRoomDropdownY + DROPDOWN_HEIGHT) {
                roomDropdownOpen = !roomDropdownOpen;
                return true;
            }

            // EDIT button click
            int editBtnX = x + PADDING + roomDropdownWidth + 2;
            if (mouseX >= editBtnX && mouseX < editBtnX + editBtnWidth &&
                mouseY >= cachedRoomDropdownY && mouseY < cachedRoomDropdownY + DROPDOWN_HEIGHT) {
                if (selectedRoomIndex == 0) {
                    // Open new room modal
                    newRoomModalOpen = true;
                    newRoomNameBox.setValue("");
                    newRoomNameBox.setFocused(true);
                    newRoomIdPreview = "";
                } else {
                    // Edit existing room
                    int roomIdx = selectedRoomIndex - 1;
                    if (roomIdx < roomListData.size()) {
                        String roomId = roomListData.get(roomIdx).id();
                        ClientPlayNetworking.send(new GuiActionPacket("room_edit", roomId, ""));
                    }
                }
                return true;
            }

            // DEL button click
            int delBtnX = editBtnX + editBtnWidth + 2;
            if (selectedRoomIndex > 0 && mouseX >= delBtnX && mouseX < delBtnX + delBtnWidth &&
                mouseY >= cachedRoomDropdownY && mouseY < cachedRoomDropdownY + DROPDOWN_HEIGHT) {
                int roomIdx = selectedRoomIndex - 1;
                if (roomIdx < roomListData.size()) {
                    String roomId = roomListData.get(roomIdx).id();
                    ClientPlayNetworking.send(new GuiActionPacket("room_delete", roomId, ""));
                    // Reset selection
                    selectedRoomIndex = 0;
                }
                return true;
            }
        }

        return true; // Consume click within panel
    }

    /**
     * Handle clicks on the new room modal.
     */
    private boolean handleNewRoomModalClick(double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        int modalW = 250;
        int modalH = 100;
        int modalX = (screenW - modalW) / 2;
        int modalY = (screenH - modalH) / 2;

        // Check if click is outside modal
        if (mouseX < modalX || mouseX > modalX + modalW ||
            mouseY < modalY || mouseY > modalY + modalH) {
            // Close modal
            newRoomModalOpen = false;
            newRoomNameBox.setValue("");
            newRoomNameBox.setFocused(false);
            newRoomIdPreview = "";
            return true;
        }

        // Handle EditBox click
        if (newRoomNameBox.mouseClicked(mouseX, mouseY, 0)) {
            return true;
        }

        int btnW = 60;
        int btnH = 18;
        int btnY = modalY + modalH - btnH - 8;

        // CREATE button
        int createBtnX = modalX + modalW / 2 - btnW - 10;
        if (mouseX >= createBtnX && mouseX < createBtnX + btnW &&
            mouseY >= btnY && mouseY < btnY + btnH) {
            String roomName = newRoomNameBox.getValue();
            if (!roomName.isEmpty() && !newRoomIdPreview.isEmpty()) {
                // Send create room command
                ClientPlayNetworking.send(new GuiActionPacket("room_create", newRoomIdPreview, roomName));
                newRoomModalOpen = false;
                newRoomNameBox.setValue("");
                newRoomNameBox.setFocused(false);
                newRoomIdPreview = "";
            }
            return true;
        }

        // CANCEL button
        int cancelBtnX = modalX + modalW / 2 + 10;
        if (mouseX >= cancelBtnX && mouseX < cancelBtnX + btnW &&
            mouseY >= btnY && mouseY < btnY + btnH) {
            newRoomModalOpen = false;
            newRoomNameBox.setValue("");
            newRoomNameBox.setFocused(false);
            newRoomIdPreview = "";
            return true;
        }

        return true; // Consume click within modal
    }

    private boolean handleShortDescModalClick(double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        int modalW = 400;
        int modalH = 240;
        int modalX = (screenW - modalW) / 2;
        int modalY = (screenH - modalH) / 2;

        // Scrollbar dimensions (must match render)
        int scrollbarW = 8;
        int textAreaX = modalX + 5;
        int textAreaY = modalY + 18;
        int textAreaW = modalW - 10 - scrollbarW - 2;
        int textAreaH = modalH - 55;
        int lineHeight = 10;

        // Check scrollbar click
        java.util.List<String> lines = wrapText(shortDescText, textAreaW - 6, font);
        int totalLines = lines.size();
        int visibleLines = (textAreaH - 6) / lineHeight;

        if (totalLines > visibleLines) {
            int scrollbarX = textAreaX + textAreaW + 2;
            int scrollbarTrackH = textAreaH;
            int maxScroll = Math.max(0, totalLines - visibleLines);

            float thumbRatio = (float) visibleLines / totalLines;
            int thumbH = Math.max(20, (int) (scrollbarTrackH * thumbRatio));
            float scrollRatio = maxScroll > 0 ? (float) shortDescScrollOffset / maxScroll : 0;
            int thumbY = textAreaY + (int) ((scrollbarTrackH - thumbH) * scrollRatio);

            // Check if click is on scrollbar thumb
            if (mouseX >= scrollbarX && mouseX < scrollbarX + scrollbarW &&
                mouseY >= thumbY && mouseY < thumbY + thumbH) {
                shortDescDraggingScrollbar = true;
                shortDescDragStartY = (int) mouseY;
                shortDescDragStartScrollOffset = shortDescScrollOffset;
                return true;
            }

            // Check if click is on scrollbar track (jump to position)
            if (mouseX >= scrollbarX && mouseX < scrollbarX + scrollbarW &&
                mouseY >= textAreaY && mouseY < textAreaY + scrollbarTrackH) {
                // Calculate target scroll position
                float clickRatio = (float) (mouseY - textAreaY - thumbH / 2) / (scrollbarTrackH - thumbH);
                clickRatio = Math.max(0, Math.min(1, clickRatio));
                shortDescScrollOffset = (int) (clickRatio * maxScroll);
                return true;
            }
        }

        int btnW = 80;
        int btnH = 20;
        int btnY = modalY + modalH - btnH - 8;

        // SAVE button
        int saveBtnX = modalX + modalW / 2 - btnW - 10;
        if (mouseX >= saveBtnX && mouseX < saveBtnX + btnW &&
            mouseY >= btnY && mouseY < btnY + btnH) {
            // Save the short description
            if (shortDescText.length() <= 512) {
                ClientPlayNetworking.send(new GuiActionPacket("short_desc", selectedLangId, shortDescText));
                // Update local translations map
                allShortDescriptions.put(selectedLangId, shortDescText);
            }
            shortDescModalOpen = false;
            return true;
        }

        // CANCEL button
        int cancelBtnX = modalX + modalW / 2 + 10;
        if (mouseX >= cancelBtnX && mouseX < cancelBtnX + btnW &&
            mouseY >= btnY && mouseY < btnY + btnH) {
            shortDescModalOpen = false;
            return true;
        }

        // Click outside modal closes it
        if (mouseX < modalX || mouseX > modalX + modalW ||
            mouseY < modalY || mouseY > modalY + modalH) {
            shortDescModalOpen = false;
            clearShortDescSelection();
            return true;
        }

        // Click within text area - position cursor and start selection
        if (mouseX >= textAreaX && mouseX < textAreaX + textAreaW &&
            mouseY >= textAreaY && mouseY < textAreaY + textAreaH) {
            int clickedPos = getCharPositionFromClick(mouseX, mouseY, textAreaX, textAreaY, textAreaW, lineHeight, font);
            shortDescCursorPos = clickedPos;
            // Start selection on mouse down
            shortDescSelectionStart = clickedPos;
            shortDescSelectionEnd = clickedPos;
            shortDescDraggingSelection = true;
            return true;
        }

        return true; // Consume click within modal
    }

    /**
     * Get character position from mouse click coordinates.
     */
    private int getCharPositionFromClick(double mouseX, double mouseY, int textAreaX, int textAreaY, int textAreaW, int lineHeight, Font font) {
        java.util.List<String> lines = wrapText(shortDescText, textAreaW - 6, font);

        // Calculate which line was clicked
        int clickedVisibleLine = (int) ((mouseY - textAreaY - 3) / lineHeight);
        int clickedLine = clickedVisibleLine + shortDescScrollOffset;

        if (clickedLine < 0) clickedLine = 0;
        if (clickedLine >= lines.size()) clickedLine = Math.max(0, lines.size() - 1);

        // Calculate character position at start of clicked line
        int charPos = 0;
        for (int i = 0; i < clickedLine && i < lines.size(); i++) {
            charPos += lines.get(i).length();
            // Account for separator
            if (charPos < shortDescText.length()) {
                char sep = shortDescText.charAt(charPos);
                if (sep == '\n' || sep == ' ') {
                    charPos++;
                }
            }
        }

        // Find position within line
        if (clickedLine < lines.size()) {
            String line = lines.get(clickedLine);
            int relativeX = (int) mouseX - textAreaX - 3;

            // Binary search for character position
            int posInLine = 0;
            for (int i = 0; i <= line.length(); i++) {
                int width = font.width(line.substring(0, i));
                if (width > relativeX) {
                    // Check if closer to previous char
                    if (i > 0) {
                        int prevWidth = font.width(line.substring(0, i - 1));
                        if (relativeX - prevWidth < width - relativeX) {
                            posInLine = i - 1;
                        } else {
                            posInLine = i;
                        }
                    }
                    break;
                }
                posInLine = i;
            }
            charPos += posInLine;
        }

        return Math.min(charPos, shortDescText.length());
    }

    /**
     * Clear text selection.
     */
    private void clearShortDescSelection() {
        shortDescSelectionStart = -1;
        shortDescSelectionEnd = -1;
    }

    /**
     * Get the selected text, or empty string if no selection.
     */
    private String getShortDescSelectedText() {
        if (shortDescSelectionStart < 0 || shortDescSelectionEnd < 0) {
            return "";
        }
        int start = Math.min(shortDescSelectionStart, shortDescSelectionEnd);
        int end = Math.max(shortDescSelectionStart, shortDescSelectionEnd);
        if (start == end) {
            return "";
        }
        return shortDescText.substring(start, end);
    }

    /**
     * Delete selected text and return true if there was a selection.
     */
    private boolean deleteShortDescSelection() {
        if (shortDescSelectionStart < 0 || shortDescSelectionEnd < 0) {
            return false;
        }
        int start = Math.min(shortDescSelectionStart, shortDescSelectionEnd);
        int end = Math.max(shortDescSelectionStart, shortDescSelectionEnd);
        if (start == end) {
            clearShortDescSelection();
            return false;
        }
        shortDescText = shortDescText.substring(0, start) + shortDescText.substring(end);
        shortDescCursorPos = start;
        clearShortDescSelection();
        return true;
    }

    /**
     * Start or extend selection from current cursor position.
     */
    private void startOrExtendSelection(boolean isShiftHeld) {
        if (isShiftHeld) {
            // Extend selection
            if (shortDescSelectionStart < 0) {
                // Start new selection from current cursor
                shortDescSelectionStart = shortDescCursorPos;
            }
            // selectionEnd will be set after cursor move
        } else {
            // Clear selection
            clearShortDescSelection();
        }
    }

    /**
     * Complete selection after cursor move.
     */
    private void updateSelectionEnd() {
        if (shortDescSelectionStart >= 0) {
            shortDescSelectionEnd = shortDescCursorPos;
        }
    }

    private void sendSelectionAction(SelectionKeyPacket.Action action) {
        ClientPlayNetworking.send(new SelectionKeyPacket(action));
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Handle new room modal
        if (newRoomModalOpen) {
            // Escape - close modal
            if (keyCode == 256) {
                newRoomModalOpen = false;
                newRoomNameBox.setValue("");
                newRoomNameBox.setFocused(false);
                newRoomIdPreview = "";
                return true;
            }
            // Enter - create room
            String roomName = newRoomNameBox.getValue();
            if (keyCode == 257 && !roomName.isEmpty() && !newRoomIdPreview.isEmpty()) {
                ClientPlayNetworking.send(new GuiActionPacket("room_create", newRoomIdPreview, roomName));
                newRoomModalOpen = false;
                newRoomNameBox.setValue("");
                newRoomNameBox.setFocused(false);
                newRoomIdPreview = "";
                return true;
            }
            // Delegate to EditBox
            if (newRoomNameBox.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
            return true;
        }

        // Handle short desc modal
        if (shortDescModalOpen) {
            boolean isCtrl = (modifiers & 2) != 0;  // GLFW_MOD_CONTROL = 2
            boolean isShift = (modifiers & 1) != 0; // GLFW_MOD_SHIFT = 1

            // Escape - close modal
            if (keyCode == 256) {
                shortDescModalOpen = false;
                clearShortDescSelection();
                return true;
            }

            // CTRL+A - select all
            if (isCtrl && keyCode == 65) {
                shortDescSelectionStart = 0;
                shortDescSelectionEnd = shortDescText.length();
                shortDescCursorPos = shortDescText.length();
                return true;
            }

            // CTRL+C - copy
            if (isCtrl && keyCode == 67) {
                String selected = getShortDescSelectedText();
                if (!selected.isEmpty()) {
                    Minecraft.getInstance().keyboardHandler.setClipboard(selected);
                }
                return true;
            }

            // CTRL+X - cut
            if (isCtrl && keyCode == 88) {
                String selected = getShortDescSelectedText();
                if (!selected.isEmpty()) {
                    Minecraft.getInstance().keyboardHandler.setClipboard(selected);
                    deleteShortDescSelection();
                }
                return true;
            }

            // CTRL+V - paste
            if (isCtrl && keyCode == 86) {
                String clipboard = Minecraft.getInstance().keyboardHandler.getClipboard();
                if (clipboard != null && !clipboard.isEmpty()) {
                    // Delete selection first if any
                    deleteShortDescSelection();
                    // Check length limit
                    int availableSpace = 512 - shortDescText.length();
                    if (availableSpace > 0) {
                        String toInsert = clipboard.length() > availableSpace
                            ? clipboard.substring(0, availableSpace)
                            : clipboard;
                        shortDescText = shortDescText.substring(0, shortDescCursorPos)
                                      + toInsert
                                      + shortDescText.substring(shortDescCursorPos);
                        shortDescCursorPos += toInsert.length();
                    }
                }
                return true;
            }

            // Enter - insert newline (deletes selection first)
            if (keyCode == 257 && shortDescText.length() < 512) {
                deleteShortDescSelection();
                if (shortDescText.length() < 512) {
                    shortDescText = shortDescText.substring(0, shortDescCursorPos)
                                  + "\n"
                                  + shortDescText.substring(shortDescCursorPos);
                    shortDescCursorPos++;
                }
                return true;
            }

            // Backspace - delete selection or char before cursor
            if (keyCode == 259) {
                if (!deleteShortDescSelection() && shortDescCursorPos > 0) {
                    shortDescText = shortDescText.substring(0, shortDescCursorPos - 1)
                                  + shortDescText.substring(shortDescCursorPos);
                    shortDescCursorPos--;
                }
                return true;
            }

            // Delete - delete selection or char after cursor
            if (keyCode == 261) {
                if (!deleteShortDescSelection() && shortDescCursorPos < shortDescText.length()) {
                    shortDescText = shortDescText.substring(0, shortDescCursorPos)
                                  + shortDescText.substring(shortDescCursorPos + 1);
                }
                return true;
            }

            // Left arrow (with optional Shift for selection)
            if (keyCode == 263 && shortDescCursorPos > 0) {
                startOrExtendSelection(isShift);
                shortDescCursorPos--;
                if (isShift) {
                    updateSelectionEnd();
                }
                return true;
            }

            // Right arrow (with optional Shift for selection)
            if (keyCode == 262 && shortDescCursorPos < shortDescText.length()) {
                startOrExtendSelection(isShift);
                shortDescCursorPos++;
                if (isShift) {
                    updateSelectionEnd();
                }
                return true;
            }

            // Up arrow - move to previous line (with optional Shift for selection)
            if (keyCode == 265) {
                startOrExtendSelection(isShift);
                moveCursorVertically(-1);
                if (isShift) {
                    updateSelectionEnd();
                }
                return true;
            }

            // Down arrow - move to next line (with optional Shift for selection)
            if (keyCode == 264) {
                startOrExtendSelection(isShift);
                moveCursorVertically(1);
                if (isShift) {
                    updateSelectionEnd();
                }
                return true;
            }

            // Home (with optional Shift for selection)
            if (keyCode == 268) {
                startOrExtendSelection(isShift);
                shortDescCursorPos = 0;
                if (isShift) {
                    updateSelectionEnd();
                }
                return true;
            }

            // End (with optional Shift for selection)
            if (keyCode == 269) {
                startOrExtendSelection(isShift);
                shortDescCursorPos = shortDescText.length();
                if (isShift) {
                    updateSelectionEnd();
                }
                return true;
            }
            return true;
        }

        // Handle RDNS box
        if (rdnsBox != null && rdnsBox.isFocused()) {
            // Escape - cancel editing and restore
            if (keyCode == 256) {
                rdnsBox.setValue(originalRdns);
                rdnsBox.setFocused(false);
                return true;
            }
            // Enter - confirm editing
            if (keyCode == 257) {
                ClientPlayNetworking.send(new GuiActionPacket("rename", rdnsBox.getValue(), ""));
                rdnsBox.setFocused(false);
                return true;
            }
            return rdnsBox.keyPressed(keyCode, scanCode, modifiers);
        }

        // Handle Name box
        if (nameBox != null && nameBox.isFocused()) {
            // Escape - cancel editing and restore
            if (keyCode == 256) {
                nameBox.setValue(originalName);
                nameBox.setFocused(false);
                return true;
            }
            // Enter - confirm editing
            if (keyCode == 257) {
                ClientPlayNetworking.send(new GuiActionPacket("title", selectedLangId, nameBox.getValue()));
                // Update local translations map
                allTitles.put(selectedLangId, nameBox.getValue());
                nameBox.setFocused(false);
                return true;
            }
            return nameBox.keyPressed(keyCode, scanCode, modifiers);
        }

        // Handle Room ID box (editing existing room)
        if (roomIdBox != null && roomIdBox.isFocused()) {
            // Escape - cancel editing and restore
            if (keyCode == 256) {
                roomIdBox.setValue(originalRoomId);
                roomIdBox.setFocused(false);
                roomRenameError = "";
                return true;
            }
            // Enter - confirm editing (rename room by ID)
            if (keyCode == 257) {
                String newId = roomIdBox.getValue();
                if (!newId.isEmpty() && !newId.equals(originalRoomId)) {
                    // Send room rename: action = room_rename, targetId = oldId, extraData = newId|newName
                    String newName = roomNameBox != null ? roomNameBox.getValue() : originalRoomName;
                    ClientPlayNetworking.send(new GuiActionPacket("room_rename", originalRoomId, newId + "|" + newName));
                }
                roomIdBox.setFocused(false);
                return true;
            }
            return roomIdBox.keyPressed(keyCode, scanCode, modifiers);
        }

        // Handle Room Name box (editing existing room)
        if (roomNameBox != null && roomNameBox.isFocused()) {
            // Escape - cancel editing and restore
            if (keyCode == 256) {
                roomNameBox.setValue(originalRoomName);
                roomNameBox.setFocused(false);
                roomRenameError = "";
                return true;
            }
            // Enter - confirm editing (rename room by name, also updates ID)
            if (keyCode == 257) {
                String newName = roomNameBox.getValue();
                if (!newName.isEmpty()) {
                    // Generate new ID from name
                    String newId = generateRoomId(newName);
                    // Send room rename: action = room_rename, targetId = oldId, extraData = newId|newName
                    ClientPlayNetworking.send(new GuiActionPacket("room_rename", originalRoomId, newId + "|" + newName));
                }
                roomNameBox.setFocused(false);
                return true;
            }
            // When typing in name box, update the ID box to show the generated ID preview
            boolean result = roomNameBox.keyPressed(keyCode, scanCode, modifiers);
            if (result && roomIdBox != null) {
                String newId = generateRoomId(roomNameBox.getValue());
                roomIdBox.setValue(newId);
            }
            return result;
        }

        return false;
    }

    public boolean charTyped(char chr, int modifiers) {
        // Handle new room modal
        if (newRoomModalOpen) {
            if (newRoomNameBox.charTyped(chr, modifiers)) {
                return true;
            }
            return true;
        }

        // Handle short desc modal
        if (shortDescModalOpen) {
            if (chr >= 32) {
                // Delete selection first if any
                deleteShortDescSelection();
                // Then insert character if within limit
                if (shortDescText.length() < 512) {
                    shortDescText = shortDescText.substring(0, shortDescCursorPos)
                                  + chr
                                  + shortDescText.substring(shortDescCursorPos);
                    shortDescCursorPos++;
                }
                return true;
            }
            return true;
        }

        // Handle RDNS box - force lowercase
        if (rdnsBox != null && rdnsBox.isFocused()) {
            return rdnsBox.charTyped(Character.toLowerCase(chr), modifiers);
        }

        // Handle Name box
        if (nameBox != null && nameBox.isFocused()) {
            return nameBox.charTyped(chr, modifiers);
        }

        // Handle Room ID box - force lowercase
        if (roomIdBox != null && roomIdBox.isFocused()) {
            return roomIdBox.charTyped(Character.toLowerCase(chr), modifiers);
        }

        // Handle Room Name box - also update ID box when typing
        if (roomNameBox != null && roomNameBox.isFocused()) {
            boolean result = roomNameBox.charTyped(chr, modifiers);
            if (result && roomIdBox != null) {
                String newId = generateRoomId(roomNameBox.getValue());
                roomIdBox.setValue(newId);
            }
            return result;
        }

        return false;
    }

    /**
     * Handle mouse scroll for dropdown list and short desc modal.
     */
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // Handle scroll in short desc modal
        if (shortDescModalOpen) {
            Minecraft mc = Minecraft.getInstance();
            Font font = mc.font;
            int screenW = mc.getWindow().getGuiScaledWidth();
            int screenH = mc.getWindow().getGuiScaledHeight();

            int modalW = 400;
            int modalH = 240;
            int modalX = (screenW - modalW) / 2;
            int modalY = (screenH - modalH) / 2;

            int scrollbarW = 8;
            int textAreaX = modalX + 5;
            int textAreaY = modalY + 18;
            int textAreaW = modalW - 10 - scrollbarW - 2;
            int textAreaH = modalH - 55;
            int lineHeight = 10;

            // Check if mouse is over text area
            if (mouseX >= textAreaX && mouseX < textAreaX + textAreaW + scrollbarW + 2 &&
                mouseY >= textAreaY && mouseY < textAreaY + textAreaH) {

                java.util.List<String> lines = wrapText(shortDescText, textAreaW - 6, font);
                int totalLines = lines.size();
                int visibleLines = (textAreaH - 6) / lineHeight;
                int maxScroll = Math.max(0, totalLines - visibleLines);

                if (verticalAmount > 0) {
                    shortDescScrollOffset = Math.max(0, shortDescScrollOffset - 1);
                } else if (verticalAmount < 0) {
                    shortDescScrollOffset = Math.min(maxScroll, shortDescScrollOffset + 1);
                }

                return true;
            }
            return true; // Consume scroll in modal
        }

        // Only handle scroll when dropdown is open
        if (!dropdownOpen) {
            return false;
        }

        java.util.List<RemovableItem> removableList = buildRemovableList(PanelManager.getInstance());
        if (removableList.isEmpty()) {
            return false;
        }

        // Check if mouse is over the dropdown list area
        if (mouseX >= cachedDropdownX && mouseX < cachedDropdownX + cachedDropdownWidth &&
            mouseY >= cachedDropdownListY && mouseY < cachedDropdownListY + cachedDropdownListHeight) {

            int visibleItems = cachedDropdownListHeight / DROPDOWN_HEIGHT;
            visibleItems = Math.min(visibleItems, removableList.size());
            int maxScroll = Math.max(0, removableList.size() - visibleItems);

            // Scroll: negative = scroll down (show items with higher index), positive = scroll up
            if (verticalAmount > 0) {
                dropdownScrollOffset = Math.max(0, dropdownScrollOffset - 1);
            } else if (verticalAmount < 0) {
                dropdownScrollOffset = Math.min(maxScroll, dropdownScrollOffset + 1);
            }

            return true;
        }

        return false;
    }
}
