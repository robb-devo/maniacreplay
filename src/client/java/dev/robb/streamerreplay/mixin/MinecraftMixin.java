package dev.robb.streamerreplay.mixin;

import dev.robb.streamerreplay.replay.ReplayManager;
import dev.robb.streamerreplay.replay.ReplaySession;
import dev.robb.streamerreplay.ui.ReplayPauseScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Shadow
	@Final
	public Gui gui;

	/** Drives the replay once per frame, right before the game decides how many ticks to run. No-op outside replays. */
	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/DeltaTracker$Timer;advanceGameTime(J)I"))
	private void streamerreplay$replayFrame(boolean advanceGameTime, CallbackInfo ci) {
		if (ReplayManager.isActive()) {
			ReplayManager.onFrame();
		}
	}

	/** Esc in a replay opens the replay menu instead of the vanilla pause menu. */
	@Inject(method = "pauseGame", at = @At("HEAD"), cancellable = true)
	private void streamerreplay$replayPauseMenu(boolean suppressPauseMenu, CallbackInfo ci) {
		ReplaySession session = ReplayManager.session();
		if (session != null && session.isInWorld((Minecraft) (Object) this)) {
			if (!suppressPauseMenu && gui.screen() == null) {
				gui.setScreen(new ReplayPauseScreen(session));
			}
			ci.cancel();
		}
	}
}
