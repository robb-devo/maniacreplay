package dev.robb.streamerreplay.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Small toast helper. Safe to call from any thread. */
public final class Notifications {
	public static final SystemToast.SystemToastId RECORDING = new SystemToast.SystemToastId(4000L);
	private static final SystemToast.SystemToastId REPLAY = new SystemToast.SystemToastId(5000L);

	private Notifications() {
	}

	public static void recording(Component title, @Nullable Component message) {
		show(RECORDING, title, message);
	}

	public static void replay(Component title, @Nullable Component message) {
		show(REPLAY, title, message);
	}

	/** Toasts that arrived while the game was still loading its resources (see {@link #tick}). */
	private static final java.util.Queue<Runnable> PENDING = new java.util.concurrent.ConcurrentLinkedQueue<>();

	private static void show(SystemToast.SystemToastId id, Component title, @Nullable Component message) {
		// A toast lays out its text when it is created, so one created during the start-up resource load would
		// show raw translation keys. Hold it until the loading overlay is gone.
		PENDING.add(() -> SystemToast.addOrUpdate(Minecraft.getInstance().gui.toastManager(), id, title, message));
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> tick(mc));
	}

	/** Render thread: shows queued toasts once no resource reload is running. Also called every client tick. */
	public static void tick(Minecraft mc) {
		if (mc.gui.overlay() instanceof net.minecraft.client.gui.screens.LoadingOverlay) {
			return;
		}
		for (Runnable toast; (toast = PENDING.poll()) != null; ) {
			toast.run();
		}
	}
}
