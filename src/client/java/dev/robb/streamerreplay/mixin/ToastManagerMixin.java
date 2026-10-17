package dev.robb.streamerreplay.mixin;

import dev.robb.streamerreplay.replay.ReplayManager;
import net.minecraft.client.gui.components.toasts.RecipeToast;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.components.toasts.TutorialToast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While watching a replay, vanilla's onboarding toasts (movement tutorial, social interactions, "chat messages
 * can't be verified") and recipe unlock toasts replayed from the recording are not shown: they are about the
 * live game, not the replay. Everything else, including this mod's own notifications, still appears.
 */
@Mixin(ToastManager.class)
abstract class ToastManagerMixin {
	@Inject(method = "addToast", at = @At("HEAD"), cancellable = true)
	private void streamerreplay$suppressOnboarding(Toast toast, CallbackInfo ci) {
		if (ReplayManager.isActive() && isOnboarding(toast)) {
			ci.cancel();
		}
	}

	private static boolean isOnboarding(Toast toast) {
		return toast instanceof TutorialToast || toast instanceof RecipeToast
			|| toast instanceof SystemToast system && system.getToken() == SystemToast.SystemToastId.UNSECURE_SERVER_WARNING;
	}
}
