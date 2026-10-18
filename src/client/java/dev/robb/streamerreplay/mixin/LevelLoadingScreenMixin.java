package dev.robb.streamerreplay.mixin;

import dev.robb.streamerreplay.replay.ReplayManager;
import dev.robb.streamerreplay.replay.ReplaySession;
import dev.robb.streamerreplay.ui.RewindOverlay;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While a replay rewinds, the world-loading screen shows the last picture instead of "Loading terrain". */
@Mixin(LevelLoadingScreen.class)
abstract class LevelLoadingScreenMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void streamerreplay$rewindPicture(GuiGraphicsExtractor g, int mouseX, int mouseY, float a, CallbackInfo ci) {
		ReplaySession session = ReplayManager.session();
		if (session != null && RewindOverlay.isShown()) {
			RewindOverlay.draw(g, g.guiWidth(), g.guiHeight(), session.catchUpProgress());
			ci.cancel();
		}
	}
}
