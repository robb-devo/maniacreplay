package dev.robb.streamerreplay.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.robb.streamerreplay.ui.ReplayConfigScreen;

/** Loaded only by Mod Menu (optional dependency, "modmenu" entrypoint). */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return ReplayConfigScreen::new;
	}
}
