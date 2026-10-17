package dev.robb.streamerreplay.replay;

import dev.robb.streamerreplay.StreamerReplay;
import dev.robb.streamerreplay.record.RecordingManager;
import dev.robb.streamerreplay.render.VideoExporter;
import dev.robb.streamerreplay.ui.Notifications;
import dev.robb.streamerreplay.ui.ReplayBrowserScreen;
import io.netty.channel.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;

/** Entry point for everything replay related: opening, per-frame driving and leaving a replay. */
public final class ReplayManager {
	private static @Nullable ReplaySession session;

	private ReplayManager() {
	}

	public static @Nullable ReplaySession session() {
		return session;
	}

	public static boolean isActive() {
		return session != null;
	}

	/** True if the session exists and the replay world is currently shown. */
	public static boolean isInReplayWorld() {
		ReplaySession current = session;
		return current != null && current.isInWorld(Minecraft.getInstance());
	}

	public static boolean isReplayChannel(Channel channel) {
		return ReplayChannel.isReplayChannel(channel);
	}

	/** Opens a replay. Leaves the current world first. */
	public static void open(Path file, Screen parent) {
		Minecraft mc = Minecraft.getInstance();
		if (session != null) {
			exit();
		}
		try {
			ReplaySession opened = ReplaySession.open(file, parent);
			if (mc.level != null) {
				mc.disconnectFromWorld(Component.empty());
			}
			RecordingManager.stopActive();
			session = opened;
			opened.connect();
		} catch (IOException | RuntimeException e) {
			StreamerReplay.LOGGER.error("Could not open replay {}", file, e);
			session = null;
			Notifications.replay(Component.translatable("streamerreplay.toast.open_failed"), Component.literal(String.valueOf(e.getMessage())));
			mc.gui.setScreen(parent);
		}
	}

	/** Called from the Minecraft mixin once per frame, before ticks are computed. */
	public static void onFrame() {
		ReplaySession current = session;
		if (current != null) {
			Minecraft mc = Minecraft.getInstance();
			VideoExporter.beforeFrame(mc); // steps the camera path before packets are delivered
			current.onFrame(mc);
		}
	}

	/** Leaves the replay and returns to the replay browser. */
	public static void exit() {
		ReplaySession current = session;
		if (current == null) {
			return;
		}
		VideoExporter.cancel();
		session = null;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) {
			mc.level.disconnect(Component.translatable("streamerreplay.replay.closed"));
		}
		mc.disconnect(new ReplayBrowserScreen(new TitleScreen()), false);
		current.close();
	}

	/** The fake connection closed unexpectedly (e.g. a packet failed); vanilla shows the disconnect screen. */
	static void onSessionLost(ReplaySession lost) {
		if (session == lost) {
			session = null;
			VideoExporter.cancel();
			lost.close();
		}
	}
}
