package dev.robb.streamerreplay.mixin;

import dev.robb.streamerreplay.render.CleanFrames;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clean export frames: no hitboxes, chunk borders or other debug renderers. */
@Mixin(DebugRenderer.class)
abstract class DebugRendererMixin {
	@Inject(method = "emitGizmos", at = @At("HEAD"), cancellable = true)
	private void streamerreplay$hideDebug(Frustum frustum, double camX, double camY, double camZ, float partialTicks, CallbackInfo ci) {
		if (CleanFrames.active()) {
			ci.cancel();
		}
	}
}
