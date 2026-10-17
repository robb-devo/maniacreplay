package dev.robb.streamerreplay;

import dev.robb.streamerreplay.config.ReplayConfig;
import dev.robb.streamerreplay.input.ReplayKeys;
import dev.robb.streamerreplay.library.RecoveryService;
import dev.robb.streamerreplay.record.RecordingManager;
import dev.robb.streamerreplay.render.PathPreviewRenderer;
import dev.robb.streamerreplay.ui.TitleScreenButton;
import dev.robb.streamerreplay.ui.hud.RecordingIndicatorHud;
import dev.robb.streamerreplay.ui.hud.ReplayHud;
import dev.robb.streamerreplay.ui.hud.ReplayHudHider;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import dev.robb.streamerreplay.replay.EntityPicker;

/** Client entrypoint: wires the modules together. Everything else is event driven and idle until needed. */
public final class StreamerReplayClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ReplayConfig.load();
		ReplayKeys.register();

		HudElementRegistry.addLast(StreamerReplay.id("recording_indicator"), new RecordingIndicatorHud());
		HudElementRegistry.addLast(StreamerReplay.id("replay_hud"), new ReplayHud());
		ReplayHudHider.register();

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			RecordingManager.tick(mc);
			ReplayKeys.handle(mc);
		});
		ScreenEvents.AFTER_INIT.register(TitleScreenButton::onScreenInit);
		ClientPreAttackCallback.EVENT.register(EntityPicker::onAttack);
		LevelExtractionEvents.AFTER_BLOCK_OUTLINE_EXTRACTION.register((context, hit) -> PathPreviewRenderer.extract());

		ClientLifecycleEvents.CLIENT_STARTED.register(mc -> RecoveryService.recoverAsync());
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> RecordingManager.shutdown());
		StreamerReplay.LOGGER.info("Streamer Replay {} ready", StreamerReplay.version());
	}
}
