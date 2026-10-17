package dev.robb.streamerreplay.mixin;

import dev.robb.streamerreplay.record.RecordingManager;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The start of a session: login succeeded, configuration is about to begin. Recording starts here. */
@Mixin(ClientHandshakePacketListenerImpl.class)
abstract class ClientHandshakePacketListenerImplMixin {
	@Shadow
	@Final
	private Connection connection;

	@Shadow
	@Final
	private @Nullable ServerData serverData;

	@Inject(method = "handleLoginFinished", at = @At("HEAD"))
	private void streamerreplay$startRecording(ClientboundLoginFinishedPacket packet, CallbackInfo ci) {
		RecordingManager.onLoginFinished(connection, packet, serverData, ((ConnectionAccessor) connection).streamerreplay$channel());
	}
}
