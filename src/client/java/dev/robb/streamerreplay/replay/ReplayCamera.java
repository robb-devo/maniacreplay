package dev.robb.streamerreplay.replay;

import com.mojang.blaze3d.platform.InputConstants;
import dev.robb.streamerreplay.config.ReplayConfig;
import dev.robb.streamerreplay.path.CameraState;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * The replay camera. Moves every rendered frame (not every tick), so it stays perfectly smooth and keeps
 * working while the replay is paused or slowed down.
 *
 * <p>Modes: free flight (WASD + mouse, scroll wheel = speed like vanilla spectator), path playback (pose set
 * from the camera path) and spectating an entity (vanilla first-person view of that entity).
 */
public final class ReplayCamera {
	private static final double BASE_SPEED = 10.0; // blocks per second at default fly speed
	private static final float DEFAULT_FLY_SPEED = 0.05F;

	private double x;
	private double y;
	private double z;
	private float roll;
	private float fov = CameraState.DEFAULT_FOV;
	private double vx;
	private double vy;
	private double vz;
	private @Nullable CameraState pathPose;
	private boolean initialized;
	private @Nullable UUID followUuid;
	private @Nullable ResourceKey<Level> dimension;

	/** Places the camera at the eyes of the given entity (on spawn and when leaving spectate mode). */
	public void moveTo(Entity entity) {
		Vec3 eye = entity.getEyePosition();
		x = eye.x;
		y = eye.y;
		z = eye.z;
		vx = vy = vz = 0;
		initialized = true;
	}

	/**
	 * First placement when a replay opens: a few blocks behind and above the recorded player, looking at them,
	 * instead of inside their head.
	 */
	private void placeBehind(LocalPlayer player, Minecraft mc) {
		placeBehind(player, player);
		fov = mc.options.fov().get();
	}

	/** Puts the camera a few blocks behind and above {@code anchor}, looking the same way, slightly down. */
	private void placeBehind(Entity anchor, LocalPlayer player) {
		moveTo(anchor);
		float yawDeg = anchor.getYRot();
		double yaw = Math.toRadians(yawDeg);
		x += Math.sin(yaw) * 3.5;
		z -= Math.cos(yaw) * 3.5;
		y += 1.2;
		player.setYRot(yawDeg);
		player.setXRot(18);
	}

	/** Moves the camera to a pose without touching rotation smoothing state. */
	public void moveToPose(CameraState pose) {
		x = pose.x();
		y = pose.y();
		z = pose.z();
		vx = vy = vz = 0;
		initialized = true;
	}

	public void setPose(CameraState pose) {
		x = pose.x();
		y = pose.y();
		z = pose.z();
		roll = pose.roll();
		fov = pose.fov();
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			player.setYRot(pose.yaw());
			player.setXRot(pose.pitch());
		}
	}

	/** While a path is playing, the camera follows these poses; {@code null} returns to free flight. */
	public void setPathPose(@Nullable CameraState pose) {
		this.pathPose = pose;
		if (pose != null) {
			setPose(pose);
		}
		vx = vy = vz = 0;
	}

	public boolean isInitialized() {
		return initialized;
	}

	/** Current pose (eye position) for keyframes. */
	public CameraState pose() {
		LocalPlayer player = Minecraft.getInstance().player;
		float yaw = player != null ? player.getYRot() : 0;
		float pitch = player != null ? player.getXRot() : 0;
		return new CameraState(x, y, z, Mth.wrapDegrees(yaw), pitch, roll, fov);
	}

	public float roll() {
		return roll;
	}

	public void setRoll(float roll) {
		this.roll = Mth.wrapDegrees(roll);
	}

	public float fov() {
		return fov;
	}

	public void setFov(float fov) {
		this.fov = Mth.clamp(fov, 10, 150);
	}

	/** Watches the world through the eyes of {@code target} until Shift is pressed. */
	public void follow(Minecraft mc, Entity target) {
		followUuid = target.getUUID();
		mc.setCameraEntity(target);
	}

	public void stopFollowing(Minecraft mc) {
		followUuid = null;
		if (mc.player != null) {
			Entity target = mc.getCameraEntity();
			if (target != null && target != mc.player) {
				placeBehind(target, mc.player); // continue from just behind the followed entity
			}
			mc.setCameraEntity(mc.player);
		}
	}

	private void reacquireFollowTarget(Minecraft mc) {
		if (mc.level == null) {
			return;
		}
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (followUuid.equals(entity.getUUID()) && entity != mc.player) {
				mc.setCameraEntity(entity);
				return;
			}
		}
	}

	/**
	 * Called every frame. When the world switches to another dimension during normal playback (the recorded player
	 * went through a portal or respawned elsewhere), the camera would be left at the old coordinates inside the new
	 * world, and any entity it followed is gone. So it follows the recorded player instead; Shift returns to the free
	 * camera as usual. Switches replayed while rebuilding the world after a jump, or while a camera path plays, only
	 * update the dimension, so the camera stays where the user put it.
	 */
	void trackDimension(Minecraft mc, @Nullable UUID recordedPlayer, boolean normalPlayback) {
		if (mc.level == null) {
			return;
		}
		ResourceKey<Level> current = mc.level.dimension();
		if (current.equals(dimension)) {
			return;
		}
		boolean switched = dimension != null;
		dimension = current;
		if (switched && normalPlayback && recordedPlayer != null && pathPose == null) {
			if (isSpectatingOther(mc) && mc.player != null) {
				mc.setCameraEntity(mc.player); // the followed entity stayed behind in the old dimension
			}
			followUuid = recordedPlayer; // reacquireFollowTarget attaches as soon as the player appears in the new world
		}
	}

	/** Whether vanilla should render from another entity's eyes (spectate mode). */
	public static boolean isSpectatingOther(Minecraft mc) {
		Entity cameraEntity = mc.getCameraEntity();
		return cameraEntity != null && cameraEntity != mc.player;
	}

	/** Render thread, once per frame before the game ticks. */
	public void update(Minecraft mc, double dtSeconds) {
		LocalPlayer player = mc.player;
		if (player == null) {
			return;
		}
		if (!initialized) {
			placeBehind(player, mc);
		}
		if (followUuid != null && !isSpectatingOther(mc)) {
			reacquireFollowTarget(mc); // e.g. after a seek rebuilt the world
		}
		if (isSpectatingOther(mc)) {
			Entity target = mc.getCameraEntity();
			if ((mc.gui.screen() == null && mc.options.keyShift.isDown()) || target == null || target.isRemoved()) {
				stopFollowing(mc);
			} else {
				moveTo(target);
			}
			syncPlayer(player);
			return;
		}
		if (pathPose != null) {
			setPose(pathPose);
		} else if (mc.gui.screen() == null) {
			fly(mc.options, player, dtSeconds, false);
		} else if (mc.gui.screen() instanceof CameraControlScreen control && control.allowsCameraMovement()) {
			fly(mc.options, player, dtSeconds, true);
		} else {
			vx = vy = vz = 0;
		}
		syncPlayer(player);
	}

	private void fly(Options options, LocalPlayer player, double dt, boolean physical) {
		double forward = axis(down(options.keyUp, physical), down(options.keyDown, physical));
		double strafe = axis(down(options.keyLeft, physical), down(options.keyRight, physical));
		double vertical = axis(down(options.keyJump, physical), down(options.keyShift, physical));
		double speed = BASE_SPEED * (player.getAbilities().getFlyingSpeed() / DEFAULT_FLY_SPEED);
		if (down(options.keySprint, physical)) {
			speed *= 3;
		}
		double yawRad = Math.toRadians(player.getYRot());
		double sin = Math.sin(yawRad);
		double cos = Math.cos(yawRad);
		double length = Math.sqrt(forward * forward + strafe * strafe);
		if (length > 1) {
			forward /= length;
			strafe /= length;
		}
		double targetX = (strafe * cos - forward * sin) * speed;
		double targetZ = (forward * cos + strafe * sin) * speed;
		double targetY = vertical * speed;
		// Frame-rate independent exponential smoothing: 0 = instant, 0.95 = very floaty.
		double smoothing = ReplayConfig.get().cameraSmoothing;
		double blend = smoothing <= 0 ? 1 : 1 - Math.pow(smoothing, dt * 10);
		vx += (targetX - vx) * blend;
		vy += (targetY - vy) * blend;
		vz += (targetZ - vz) * blend;
		x += vx * dt;
		y += vy * dt;
		z += vz * dt;
	}

	/** Key state; with a screen open the physical keyboard is polled since vanilla stops updating mappings. */
	private static boolean down(KeyMapping mapping, boolean physical) {
		if (!physical) {
			return mapping.isDown();
		}
		InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(mapping);
		return key.getType() == InputConstants.Type.KEYBOARD && key.getValue() > 0 && InputConstants.isKeyDown(key.getValue());
	}

	private static double axis(boolean positive, boolean negative) {
		return (positive ? 1 : 0) - (negative ? 1 : 0);
	}

	/** Keeps the (invisible) local player at the camera so chunk loading, sounds and culling follow it. */
	private void syncPlayer(LocalPlayer player) {
		double eyeHeight = player.getEyeHeight();
		player.setPos(x, y - eyeHeight, z);
		player.setOldPosAndRot();
		player.setDeltaMovement(Vec3.ZERO);
		player.getAbilities().flying = true;
	}

	/** Pose override applied by the camera mixin, or {@code null} to let vanilla decide. */
	public @Nullable CameraState renderOverride(Minecraft mc) {
		if (!initialized || isSpectatingOther(mc)) {
			return null;
		}
		return pose();
	}
}
