package dev.robb.streamerreplay.input;

import com.mojang.blaze3d.platform.InputConstants;
import dev.robb.streamerreplay.StreamerReplay;
import dev.robb.streamerreplay.record.RecordingManager;
import dev.robb.streamerreplay.replay.ReplaySession;
import dev.robb.streamerreplay.ui.Notifications;
import dev.robb.streamerreplay.ui.ReplayEditorScreen;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** All key bindings of the mod, in their own category in the vanilla controls screen. */
public final class ReplayKeys {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(StreamerReplay.id("main"));

	public static final KeyMapping MARKER = key("marker", InputConstants.KEY_M);
	public static final KeyMapping STOP_RECORDING = key("stop_recording", InputConstants.UNKNOWN.getValue());
	public static final KeyMapping PLAY_PAUSE = key("play_pause", InputConstants.KEY_K);
	public static final KeyMapping EDITOR = key("editor", InputConstants.KEY_O);
	public static final KeyMapping KEYFRAME = key("keyframe", InputConstants.KEY_I);
	public static final KeyMapping SEEK_BACK = key("seek_back", InputConstants.KEY_LEFT);
	public static final KeyMapping SEEK_FORWARD = key("seek_forward", InputConstants.KEY_RIGHT);

	public static final double SEEK_STEP_MS = 5_000;

	private ReplayKeys() {
	}

	private static KeyMapping key(String name, int key) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.streamerreplay." + name, InputConstants.Type.KEYBOARD, key, CATEGORY));
	}

	/** Forces class loading (and therefore registration) during client init. */
	public static void register() {
	}

	/** End of every client tick: recording keys (these work in any world). */
	public static void handle(Minecraft mc) {
		while (MARKER.consumeClick()) {
			RecordingManager.addMarker();
		}
		while (STOP_RECORDING.consumeClick()) {
			if (RecordingManager.isRecording()) {
				RecordingManager.stopActive();
			} else {
				Notifications.recording(Component.translatable("streamerreplay.toast.not_recording"), null);
			}
		}
	}

	/**
	 * Every frame while a replay world is shown. Driven per frame rather than per tick because a paused replay
	 * runs no game ticks, and K must still be able to resume it. Key presses only arrive while no screen is open.
	 */
	public static void handleReplayKeys(Minecraft mc, ReplaySession session) {
		dev.robb.streamerreplay.replay.EntityPicker.handleClicks(mc, session);
		while (PLAY_PAUSE.consumeClick()) {
			togglePlayback(session);
		}
		while (EDITOR.consumeClick()) {
			if (mc.gui.screen() == null) {
				mc.gui.setScreen(new ReplayEditorScreen(session));
			}
		}
		while (KEYFRAME.consumeClick()) {
			addKeyframe(session);
		}
		while (SEEK_BACK.consumeClick()) {
			session.seek(Math.max(0, session.clock().timeMs() - SEEK_STEP_MS));
		}
		while (SEEK_FORWARD.consumeClick()) {
			session.seek(Math.min(session.clock().durationMs(), session.clock().timeMs() + SEEK_STEP_MS));
		}
	}

	/** Play/pause; while a camera path preview runs it stops the preview instead. */
	public static void togglePlayback(ReplaySession session) {
		if (session.isPlayingPath()) {
			session.stopPath();
		} else {
			session.togglePlaying();
		}
	}

	public static int addKeyframe(ReplaySession session) {
		int index = session.addKeyframeAtCamera();
		Notifications.replay(Component.translatable("streamerreplay.toast.keyframe_added", index + 1, session.path().size()), null);
		return index;
	}
}
