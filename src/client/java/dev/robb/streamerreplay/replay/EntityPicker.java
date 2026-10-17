package dev.robb.streamerreplay.replay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Left click in a replay: follow the entity under the crosshair (long range, blocks stop the ray). */
public final class EntityPicker {
	private static final double RANGE = 96;

	private EntityPicker() {
	}

	/** Fabric pre-attack callback: attacking makes no sense in a replay, so swallow it there. */
	public static boolean onAttack(Minecraft mc, LocalPlayer player, int clickCount) {
		ReplaySession session = ReplayManager.session();
		return session != null && session.isInWorld(mc);
	}

	/** Per frame while a replay is shown (also while paused): a left click follows the entity under the crosshair. */
	public static void handleClicks(Minecraft mc, ReplaySession session) {
		while (mc.options.keyAttack.consumeClick()) {
			if (mc.player != null && !ReplayCamera.isSpectatingOther(mc)) {
				Entity target = pick(mc, mc.player, session.camera());
				if (target != null) {
					session.camera().follow(mc, target);
				}
			}
		}
	}

	public static @Nullable Entity pick(Minecraft mc, LocalPlayer player, ReplayCamera camera) {
		if (mc.level == null) {
			return null;
		}
		var pose = camera.pose();
		Vec3 from = new Vec3(pose.x(), pose.y(), pose.z());
		Vec3 dir = Vec3.directionFromRotation(pose.pitch(), pose.yaw());
		Vec3 to = from.add(dir.scale(RANGE));
		HitResult block = mc.level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		double max = block.getType() == HitResult.Type.MISS ? RANGE * RANGE : block.getLocation().distanceToSqr(from);
		AABB area = new AABB(from, to).inflate(1.0);
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, from, to, area,
			e -> e != player && !e.isSpectator() && (e.isPickable() || e instanceof Player), max);
		return hit == null ? null : hit.getEntity();
	}
}
