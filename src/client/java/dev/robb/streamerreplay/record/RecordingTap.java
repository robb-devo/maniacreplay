package dev.robb.streamerreplay.record;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.network.ProtocolInfo;
import org.jspecify.annotations.Nullable;

/**
 * Pass-through handler that sits right behind the frame splitter of every client connection.
 *
 * <p>It sees each inbound packet frame exactly as the vanilla decoder will (decrypted, decompressed; for
 * singleplayer the in-memory frames, which are serialised as well since 1.20.5). While no recording is
 * attached it costs a single volatile read per packet.
 */
public final class RecordingTap extends ChannelInboundHandlerAdapter {
	public static final String NAME = "streamerreplay_tap";

	private volatile @Nullable Recorder recorder;
	private volatile @Nullable ProtocolInfo<?> inboundProtocol;

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		Recorder current = recorder;
		if (current != null && msg instanceof ByteBuf buf) {
			current.onInboundFrame(buf);
		}
		ctx.fireChannelRead(msg);
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		Recorder current = recorder;
		if (current != null) {
			current.onConnectionClosed();
		}
		super.channelInactive(ctx);
	}

	void attach(@Nullable Recorder recorder) {
		this.recorder = recorder;
	}

	/** Called (via mixin) whenever the connection switches its inbound protocol. */
	public void onInboundProtocol(ProtocolInfo<?> protocol) {
		this.inboundProtocol = protocol;
	}

	public @Nullable ProtocolInfo<?> inboundProtocol() {
		return inboundProtocol;
	}
}
