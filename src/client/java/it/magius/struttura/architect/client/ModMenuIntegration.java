package it.magius.struttura.architect.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import it.magius.struttura.architect.client.gui.StrutturaSettingsScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * ModMenu integration for STRUTTURA.
 * Provides a configuration screen factory that opens StrutturaSettingsScreen.
 * This class is only loaded if ModMenu is present.
 */
@Environment(EnvType.CLIENT)
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new StrutturaSettingsScreen(parent);
    }
}
