package dev.robb.streamerreplay.record;

import com.mojang.datafixers.util.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The server never sends us packets about our own movement, equipment or arm swings, so without help the
 * recording player would be invisible in the replay. Once per tick this class compares the local player with
 * what it last wrote and synthesises the packets a server would send to other players.
 *
 * <p>Only changed values are written, so standing still costs nothing.
 */
final class SelfPlayerRecorder {
	private static final double POSITION_EPSILON = 1.0E-4;
	private static final float ANGLE_EPSILON = 0.01F;

	private final Recorder recorder;
	private final Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
	private LocalPlayer lastPlayer;
	private Level lastLevel;
	private Vec3 lastPosition = Vec3.ZERO;
	private float lastYaw;
	private float lastPitch;
	private float lastHeadYaw;
	private boolean lastOnGround;
	private LivingEntity.SwingDescription lastSwing;

	SelfPlayerRecorder(Recorder recorder) {
		this.recorder = recorder;
	}

	void tick() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.getConnection() == null || !recorder.isPlayPhase()) {
			return;
		}
		if (player != lastPlayer || player.level() != lastLevel) {
			// The tab-list entry must exist before a player entity can be spawned on the replay client.
			if (mc.getConnection().getPlayerInfo(player.getUUID()) == null) {
				return;
			}
			spawn(player);
			return;
		}
		writeMovement(player);
		writeEquipment(player, false);
		writeSwing(player);
	}

	private void spawn(LocalPlayer player) {
		lastPlayer = player;
		lastLevel = player.level();
		Vec3 pos = player.position();
		recorder.writeSynthetic(new ClientboundAddEntityPacket(
			player.getId(), player.getUUID(), pos.x, pos.y, pos.z, player.getXRot(), player.getYRot(),
			EntityType.PLAYER, 0, Vec3.ZERO, player.getYHeadRot()));
		List<SynchedEntityData.DataValue<?>> data = player.getEntityData().getNonDefaultValues();
		if (data != null && !data.isEmpty()) {
			recorder.writeSynthetic(new ClientboundSetEntityDataPacket(player.getId(), data));
		}
		equipment.clear();
		writeEquipment(player, true);
		remember(player);
		lastSwing = player.getCurrentSwing();
	}

	private void writeMovement(LocalPlayer player) {
		Vec3 pos = player.position();
		boolean moved = pos.distanceToSqr(lastPosition) > POSITION_EPSILON * POSITION_EPSILON;
		boolean rotated = Math.abs(player.getYRot() - lastYaw) > ANGLE_EPSILON || Math.abs(player.getXRot() - lastPitch) > ANGLE_EPSILON;
		if (moved || rotated || player.onGround() != lastOnGround) {
			recorder.writeSynthetic(new ClientboundEntityPositionSyncPacket(
				player.getId(), PositionPath.of(pos), player.getYRot(), player.getXRot(), player.onGround()));
		}
		if (Math.abs(player.getYHeadRot() - lastHeadYaw) > ANGLE_EPSILON) {
			recorder.writeSynthetic(new ClientboundRotateHeadPacket(player, Mth.packDegrees(player.getYHeadRot())));
		}
		remember(player);
	}

	private void remember(LocalPlayer player) {
		lastPosition = player.position();
		lastYaw = player.getYRot();
		lastPitch = player.getXRot();
		lastHeadYaw = player.getYHeadRot();
		lastOnGround = player.onGround();
	}

	private void writeEquipment(LocalPlayer player, boolean force) {
		List<Pair<EquipmentSlot, ItemStack>> changed = null;
		for (EquipmentSlot slot : EquipmentSlot.VALUES) {
			ItemStack current = player.getItemBySlot(slot);
			ItemStack previous = equipment.getOrDefault(slot, ItemStack.EMPTY);
			if ((force && !current.isEmpty()) || !ItemStack.matches(previous, current)) {
				if (changed == null) {
					changed = new ArrayList<>(2);
				}
				ItemStack copy = current.copy();
				changed.add(Pair.of(slot, copy));
				equipment.put(slot, copy);
			}
		}
		if (changed != null) {
			recorder.writeSynthetic(new ClientboundSetEquipmentPacket(player.getId(), changed));
		}
	}

	private void writeSwing(LocalPlayer player) {
		LivingEntity.SwingDescription swing = player.getCurrentSwing();
		if (swing != null && swing != lastSwing) {
			recorder.writeSynthetic(new ClientboundSwingAnimationPacket(player.getId(), swing.hand(), swing.animation()));
		}
		lastSwing = swing;
	}
}
