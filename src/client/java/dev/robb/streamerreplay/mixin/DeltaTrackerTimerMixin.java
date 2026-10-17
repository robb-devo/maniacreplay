package dev.robb.streamerreplay.mixin;

import dev.robb.streamerreplay.replay.ReplayManager;
import dev.robb.streamerreplay.replay.ReplaySession;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While watching a replay, game ticks follow replay time (pause, slow motion, fast forward) instead of wall time.
 * Only the game timer is affected; UI animations keep using real time.
 */
@Mixin(DeltaTracker.Timer.class)
abstract class DeltaTrackerTimerMixin {
	@Shadow
	private float deltaTicks;
	@Shadow
	private float deltaTickResidual;
	@Shadow
	private long lastMs;

	@Inject(method = "advanceGameTime", at = @At("HEAD"), cancellable = true)
	private void streamerreplay$replayTime(long currentMs, CallbackInfoReturnable<Integer> cir) {
		ReplaySession session = ReplayManager.session();
		Minecraft mc = Minecraft.getInstance();
		if (session == null || (Object) this != mc.getDeltaTracker() || !session.isInWorld(mc)
			|| mc.gui.screen() instanceof LevelLoadingScreen) {
			return; // the loading screen only closes from a game tick, so it keeps real time
		}
		int ticks = session.advanceTicks();
		deltaTicks = session.deltaTicks();
		deltaTickResidual = session.partialTick();
		lastMs = currentMs;
		cir.setReturnValue(ticks);
	}
}
