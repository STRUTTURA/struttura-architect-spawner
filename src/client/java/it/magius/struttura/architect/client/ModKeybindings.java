package it.magius.struttura.architect.client;

import com.mojang.blaze3d.platform.InputConstants;
import it.magius.struttura.architect.Architect;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Registra i keybindings per la mod Architect.
 * L'utente può personalizzare questi tasti nelle impostazioni di Minecraft.
 */
@Environment(EnvType.CLIENT)
public class ModKeybindings {

    // Categoria personalizzata per Struttura
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
        Identifier.fromNamespaceAndPath(Architect.MOD_ID, "struttura")
    );

    // Keybindings per la selezione
    public static KeyMapping SELECT_POS1;
    public static KeyMapping SELECT_POS2;
    public static KeyMapping SELECT_CLEAR;
    public static KeyMapping SELECT_APPLY;
    public static KeyMapping SELECT_APPLYALL;

    /**
     * Registra tutti i keybindings della mod.
     */
    public static void register() {
        SELECT_POS1 = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.struttura.select_pos1",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Z,
            CATEGORY
        ));

        SELECT_POS2 = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.struttura.select_pos2",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_X,
            CATEGORY
        ));

        SELECT_CLEAR = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.struttura.select_clear",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_C,
            CATEGORY
        ));

        SELECT_APPLY = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.struttura.select_apply",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            CATEGORY
        ));

        SELECT_APPLYALL = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.struttura.select_applyall",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            CATEGORY
        ));
    }
}
