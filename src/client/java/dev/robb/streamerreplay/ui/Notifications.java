package dev.robb.streamerreplay.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Small toast helper. Safe to call from any thread. */
public final class Notifications {
	private static final SystemToast.SystemToastId RECORDING = new SystemToast.SystemToastId(4000L);
	private static final SystemToast.SystemToastId REPLAY = new SystemToast.SystemToastId(5000L);

	private Notifications() {
	}

	public static void recording(Component title, @Nullable Component message) {
		show(RECORDING, title, message);
	}

	public static void replay(Component title, @Nullable Component message) {
		show(REPLAY, title, message);
	}

	private static void show(SystemToast.SystemToastId id, Component title, @Nullable Component message) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> SystemToast.addOrUpdate(mc.gui.toastManager(), id, title, message));
	}
}
