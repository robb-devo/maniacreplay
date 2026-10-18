package dev.robb.streamerreplay.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.robb.streamerreplay.render.CleanFrames;
import dev.robb.streamerreplay.render.FrameHooks;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets the exporter and thumbnail grabber read the finished world image before the GUI is drawn over it. */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Shadow
	public abstract RenderTarget mainRenderTarget();

	@Shadow
	public abstract GameRenderState gameRenderState();

	/** Clean export frames: no hand, screen overlays or 3D debug crosshair (the same switch F1 uses). */
	@Inject(method = "render", at = @At("HEAD"))
	private void streamerreplay$cleanFrame(CallbackInfo ci) {
		if (CleanFrames.active()) {
			gameRenderState().guiRenderState.isHudHidden = true;
		}
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V", shift = At.Shift.AFTER))
	private void streamerreplay$afterWorld(CallbackInfo ci) {
		FrameHooks.onWorldRendered(mainRenderTarget());
	}
}
