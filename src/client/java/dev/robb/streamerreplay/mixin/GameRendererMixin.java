package dev.robb.streamerreplay.mixin;

import dev.robb.streamerreplay.render.FrameHooks;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.pipeline.RenderTarget;

/** Lets the exporter and thumbnail grabber read the finished world image before the GUI is drawn over it. */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Shadow
	public abstract RenderTarget mainRenderTarget();

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V", shift = At.Shift.AFTER))
	private void streamerreplay$afterWorld(CallbackInfo ci) {
		FrameHooks.onWorldRendered(mainRenderTarget());
	}
}
