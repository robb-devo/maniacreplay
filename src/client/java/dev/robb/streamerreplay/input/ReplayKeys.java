package dev.robb.streamerreplay.input;

import com.mojang.blaze3d.platform.InputConstants;
import dev.robb.streamerreplay.StreamerReplay;
import dev.robb.streamerreplay.config.ReplayConfig;
import dev.robb.streamerreplay.format.Marker;
import dev.robb.streamerreplay.record.RecordingManager;
import dev.robb.streamerreplay.replay.EntityPicker;
import dev.robb.streamerreplay.replay.ReplaySession;
import dev.robb.streamerreplay.ui.Notifications;
import dev.robb.streamerreplay.ui.ReplayEditorScreen;
import dev.robb.streamerreplay.ui.hud.ReplayHud;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/** All key bindings of the mod, in their own category in the vanilla controls screen. */
public final class ReplayKeys {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(StreamerReplay.id("main"));

	// Defaults avoid every vanilla 26.3 binding (including F3 debug combos and creative toolbar keys) and the
	// defaults of Iris (R, K, I), Sodium and Mod Menu. J/U sit together on QWERTY and QWERTZ alike.
	public static final KeyMapping MARKER = key("marker", InputConstants.KEY_M);
	public static final KeyMapping TOGGLE_RECORDING = key("toggle_recording", InputConstants.UNKNOWN.getValue());
	public static final KeyMapping PLAY_PAUSE = key("play_pause", InputConstants.KEY_DOWN);
	public static final KeyMapping EDITOR = key("editor", InputConstants.KEY_J);
	public static final KeyMapping KEYFRAME = key("keyframe", InputConstants.KEY_U);
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
		while (TOGGLE_RECORDING.consumeClick()) {
			boolean on = !ReplayConfig.get().recordingEnabled;
			RecordingManager.setRecordingEnabled(on);
			if (!on) {
				Notifications.recording(Component.translatable("streamerreplay.toast.recording_off"), null);
			}
		}
	}

	/**
	 * Every frame while a replay world is shown. Driven per frame rather than per tick so that keys respond at once
	 * even in slow motion, when game ticks are rare. Key presses only arrive while no screen is open.
	 */
	public static void handleReplayKeys(Minecraft mc, ReplaySession session) {
		EntityPicker.handleClicks(mc, session);
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
			seekStep(session, -1);
		}
		while (SEEK_FORWARD.consumeClick()) {
			seekStep(session, 1);
		}
	}

	/** ← / →: 5 seconds. With Ctrl held: to the previous / next marker (a little before it, like the browser). */
	public static void seekStep(ReplaySession session, int direction) {
		if (InputConstants.isKeyDown(InputConstants.KEY_LCONTROL) || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL)) {
			jumpToMarker(session, direction);
		} else {
			session.nudge(direction * SEEK_STEP_MS);
		}
	}

	private static void jumpToMarker(ReplaySession session, int direction) {
		double now = session.clock().timeMs();
		List<Marker> markers = session.markers();
		Marker target = null;
		if (direction > 0) {
			for (Marker marker : markers) {
				if (marker.startMs() > now + 500) {
					target = marker;
					break;
				}
			}
		} else {
			for (int i = markers.size() - 1; i >= 0; i--) {
				if (markers.get(i).startMs() < now - 1500) { // a little slack, so pressing again goes further back
					target = markers.get(i);
					break;
				}
			}
		}
		if (target != null) {
			session.seek(target.startMs());
			ReplayHud.showMarker(target);
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
