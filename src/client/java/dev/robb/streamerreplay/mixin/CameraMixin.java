package dev.robb.streamerreplay.mixin;

import dev.robb.streamerreplay.path.CameraState;
import dev.robb.streamerreplay.replay.ReplayManager;
import dev.robb.streamerreplay.replay.ReplaySession;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Places the vanilla camera at the replay camera's pose (sub-tick smooth position, roll, FOV).
 * Runs after vanilla's own placement and only while a replay is shown, so other camera mods keep working elsewhere.
 */
@Mixin(Camera.class)
abstract class CameraMixin {
	@Shadow
	@Final
	private static Vector3fc FORWARDS;
	@Shadow
	@Final
	private static Vector3fc UP;
	@Shadow
	@Final
	private static Vector3fc LEFT;
	@Shadow
	@Final
	private Quaternionf rotation;
	@Shadow
	@Final
	private Vector3f forwards;
	@Shadow
	@Final
	private Vector3f up;
	@Shadow
	@Final
	private Vector3f left;
	@Shadow
	private int matrixPropertiesDirty;

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void streamerreplay$applyReplayPose(float partialTicks, CallbackInfo ci) {
		CameraState pose = streamerreplay$pose();
		if (pose == null) {
			return;
		}
		setRotation(pose.yaw(), pose.pitch());
		setPosition(pose.x(), pose.y(), pose.z());
		if (pose.roll() != 0) {
			rotation.rotateZ((float) Math.toRadians(pose.roll()));
			FORWARDS.rotate(rotation, forwards);
			UP.rotate(rotation, up);
			LEFT.rotate(rotation, left);
			matrixPropertiesDirty |= 3;
		}
	}

	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void streamerreplay$replayFov(float partialTicks, CallbackInfoReturnable<Float> cir) {
		CameraState pose = streamerreplay$pose();
		if (pose != null) {
			cir.setReturnValue(pose.fov());
		}
	}

	private static CameraState streamerreplay$pose() {
		ReplaySession session = ReplayManager.session();
		if (session == null) {
			return null;
		}
		Minecraft mc = Minecraft.getInstance();
		return session.isInWorld(mc) ? session.camera().renderOverride(mc) : null;
	}
}
