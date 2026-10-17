package dev.robb.streamerreplay.mixin;

import dev.robb.streamerreplay.replay.ReplayManager;
import dev.robb.streamerreplay.replay.ReplaySession;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Animated textures (water, lava, fire) stand still while a replay is paused, like everything else in the world. */
@Mixin(TextureManager.class)
abstract class TextureManagerMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void streamerreplay$freezeAnimations(CallbackInfo ci) {
		ReplaySession session = ReplayManager.session();
		if (session != null && session.isFrozenTick()) {
			ci.cancel();
		}
	}
}
