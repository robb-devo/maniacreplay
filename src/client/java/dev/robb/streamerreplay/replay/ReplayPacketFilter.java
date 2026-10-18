package dev.robb.streamerreplay.replay;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundClearDialogPacket;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.network.protocol.configuration.ClientboundCodeOfConductPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Rewrites the recorded packet stream so the local client becomes a free spectator camera instead of the
 * recorded player: the recorded player keeps its entity id and is rendered like any other player, while the
 * camera gets its own id and UUID. Packets that would control the camera, open screens or end the session are
 * dropped. While fast-forwarding, effect-only packets (sounds, particles) are skipped as well. Chat is dropped
 * unless the player turned it on: a replay shown on stream should not show private messages.
 *
 * <p>Runs on the render thread (the replay channel is driven from there).
 */
public final class ReplayPacketFilter {
	/** Server entity ids are always >= 0, so a negative id can never collide. */
	public static final int CAMERA_ENTITY_ID = -1_337_000;
	public static final UUID CAMERA_UUID = UUID.fromString("5e1f0000-c0de-4a11-9e9e-000000000001");
	public static final String CAMERA_NAME = "Camera";

	private static final Set<Class<?>> ALWAYS_DROPPED = Set.of(
		ClientboundDisconnectPacket.class,
		ClientboundTransferPacket.class,
		ClientboundResourcePackPushPacket.class,
		ClientboundResourcePackPopPacket.class,
		ClientboundShowDialogPacket.class,
		ClientboundClearDialogPacket.class,
		ClientboundCodeOfConductPacket.class,
		ClientboundPlayerAbilitiesPacket.class,
		ClientboundSetHealthPacket.class,
		ClientboundSetExperiencePacket.class,
		ClientboundPlayerCombatKillPacket.class,
		ClientboundPlayerLookAtPacket.class,
		ClientboundPlayerRotationPacket.class,
		ClientboundSetCameraPacket.class,
		ClientboundMoveVehiclePacket.class,
		ClientboundOpenScreenPacket.class,
		ClientboundMountScreenOpenPacket.class,
		ClientboundOpenBookPacket.class,
		ClientboundOpenSignEditorPacket.class,
		ClientboundMerchantOffersPacket.class,
		ClientboundContainerClosePacket.class,
		ClientboundContainerSetContentPacket.class,
		ClientboundContainerSetDataPacket.class,
		ClientboundContainerSetSlotPacket.class,
		ClientboundSetCursorItemPacket.class,
		ClientboundSetPlayerInventoryPacket.class,
		ClientboundSetHeldSlotPacket.class,
		ClientboundPlaceGhostRecipePacket.class,
		ClientboundCooldownPacket.class,
		ClientboundTickingStatePacket.class,
		ClientboundTickingStepPacket.class,
		ClientboundLowDiskSpaceWarningPacket.class
	);

	private static final Set<Class<?>> EFFECTS = Set.of(
		ClientboundSoundPacket.class,
		ClientboundSoundEntityPacket.class,
		ClientboundLevelParticlesPacket.class,
		ClientboundLevelEventPacket.class,
		ClientboundExplodePacket.class,
		ClientboundDamageEventPacket.class,
		ClientboundHurtAnimationPacket.class
	);

	private static final Set<Class<?>> CHAT = Set.of(
		ClientboundPlayerChatPacket.class,
		ClientboundDisguisedChatPacket.class,
		ClientboundDeleteChatPacket.class
	);

	private final boolean showChat;
	private boolean acceptNextPosition = true;
	private boolean skipEffects;

	public ReplayPacketFilter(boolean showChat) {
		this.showChat = showChat;
	}

	/** While fast-forwarding, skip sounds and particles to avoid a burst of noise and wasted work. */
	public void setSkipEffects(boolean skip) {
		this.skipEffects = skip;
	}

	/** Returns the packet to deliver (possibly rewritten) or {@code null} to drop it. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public @Nullable Packet<?> filter(Packet<?> packet) {
		Class<?> type = packet.getClass();
		if (ALWAYS_DROPPED.contains(type)) {
			return null;
		}
		if (skipEffects && EFFECTS.contains(type)) {
			return null;
		}
		if (!showChat && (CHAT.contains(type) || packet instanceof ClientboundSystemChatPacket system && !system.overlay())) {
			return null; // the action bar (overlay) is part of the game, not chat
		}
		return switch (packet) {
			case ClientboundBundlePacket bundle -> filterBundle(bundle);
			case ClientboundLoginFinishedPacket login -> new ClientboundLoginFinishedPacket(new GameProfile(CAMERA_UUID, CAMERA_NAME), login.sessionId());
			case ClientboundLoginPacket login -> {
				acceptNextPosition = true;
				yield new ClientboundLoginPacket(CAMERA_ENTITY_ID, login.hardcore(), login.levels(), login.maxPlayers(), login.chunkRadius(),
					login.simulationDistance(), login.reducedDebugInfo(), false, login.doLimitedCrafting(),
					asSpectator(login.commonPlayerSpawnInfo()), login.onlineMode(), login.enforcesSecureChat());
			}
			case ClientboundRespawnPacket respawn -> {
				acceptNextPosition = true;
				yield new ClientboundRespawnPacket(asSpectator(respawn.commonPlayerSpawnInfo()), respawn.dataToKeep());
			}
			case ClientboundPlayerPositionPacket position -> {
				// Only the spawn position is useful: it places the camera where the recording started.
				if (acceptNextPosition) {
					acceptNextPosition = false;
					yield position;
				}
				yield null;
			}
			case ClientboundGameEventPacket event -> {
				ClientboundGameEventPacket.Type kind = event.getEvent();
				boolean drop = kind == ClientboundGameEventPacket.CHANGE_GAME_MODE
					|| kind == ClientboundGameEventPacket.WIN_GAME
					|| kind == ClientboundGameEventPacket.DEMO_EVENT
					|| kind == ClientboundGameEventPacket.IMMEDIATE_RESPAWN
					|| (skipEffects && (kind == ClientboundGameEventPacket.PLAY_ARROW_HIT_SOUND || kind == ClientboundGameEventPacket.GUARDIAN_ELDER_EFFECT
					|| kind == ClientboundGameEventPacket.PUFFER_FISH_STING));
				yield drop ? null : event;
			}
			default -> packet;
		};
	}

	@SuppressWarnings("unchecked")
	private @Nullable Packet<?> filterBundle(ClientboundBundlePacket bundle) {
		List<Packet<? super ClientGamePacketListener>> kept = new ArrayList<>();
		boolean changed = false;
		for (Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
			Packet<?> filtered = filter(sub);
			if (filtered != sub) {
				changed = true;
			}
			if (filtered != null) {
				kept.add((Packet<? super ClientGamePacketListener>) filtered);
			}
		}
		if (!changed) {
			return bundle;
		}
		return kept.isEmpty() ? null : new ClientboundBundlePacket(kept);
	}

	private static CommonPlayerSpawnInfo asSpectator(CommonPlayerSpawnInfo info) {
		return new CommonPlayerSpawnInfo(info.dimensionType(), info.dimension(), info.seed(), GameType.SPECTATOR,
			Optional.of(GameType.SPECTATOR), info.isDebug(), info.isFlat(), Optional.empty(), info.portalCooldown(), info.seaLevel());
	}
}
