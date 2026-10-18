package dev.robb.streamerreplay.mixin;

import dev.robb.streamerreplay.record.RecordingTap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.BandwidthDebugMonitor;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.PacketFlow;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the (normally idle) recording tap behind the frame splitter of every client-side connection and keeps it
 * informed about protocol switches. Pure additions; nothing vanilla is replaced.
 */
@Mixin(Connection.class)
abstract class ConnectionMixin {
	@Shadow
	private Channel channel;

	@Inject(method = "configureSerialization", at = @At("TAIL"))
	private static void streamerreplay$addTap(ChannelPipeline pipeline, PacketFlow inboundDirection, boolean local,
											   @Nullable BandwidthDebugMonitor monitor, CallbackInfo ci) {
		if (inboundDirection == PacketFlow.CLIENTBOUND && pipeline.get("splitter") != null && pipeline.get(RecordingTap.NAME) == null) {
			pipeline.addAfter("splitter", RecordingTap.NAME, new RecordingTap());
		}
	}

	@Inject(method = "setupInboundProtocol", at = @At("HEAD"))
	private <T extends PacketListener> void streamerreplay$trackProtocol(ProtocolInfo<T> protocol, T listener, CallbackInfo ci) {
		Channel ch = channel;
		if (ch != null && ch.pipeline().get(RecordingTap.NAME) instanceof RecordingTap tap) {
			tap.onInboundProtocol(protocol);
		}
	}
}
