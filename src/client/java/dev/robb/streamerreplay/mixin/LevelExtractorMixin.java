package dev.robb.streamerreplay.mixin;

import dev.robb.streamerreplay.render.CleanFrames;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clean export frames: no block selection outline. */
@Mixin(LevelExtractor.class)
abstract class LevelExtractorMixin {
	@Inject(method = "extractBlockOutline", at = @At("TAIL"))
	private void streamerreplay$hideBlockOutline(Camera camera, LevelRenderState levelRenderState, CallbackInfo ci) {
		if (CleanFrames.active()) {
			levelRenderState.blockOutlineRenderState = null;
		}
	}
}
