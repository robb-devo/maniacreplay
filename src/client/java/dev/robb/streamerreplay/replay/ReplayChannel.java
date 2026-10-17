package dev.robb.streamerreplay.replay;

import dev.robb.streamerreplay.record.RecordingTap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.flow.FlowControlHandler;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * A local, in-process "server connection" that is fed from a replay file instead of a socket.
 *
 * <p>The pipeline mirrors the vanilla in-memory pipeline (so vanilla code switches protocols exactly as usual),
 * with three differences: a sink at the head swallows everything the client sends, a dispatcher hands decoded
 * packets through {@link ReplayPacketFilter} to the client's normal packet queue, and no recording tap.
 */
public final class ReplayChannel {
	public static final String DISPATCH_NAME = "streamerreplay_dispatch";
	private static final String SINK_NAME = "streamerreplay_sink";

	private final EmbeddedChannel channel;
	private final Connection connection;

	public ReplayChannel(ReplayPacketFilter filter) {
		this.connection = new Connection(PacketFlow.CLIENTBOUND);
		Connection conn = this.connection;
		this.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
			@Override
			protected void initChannel(Channel ch) {
				ChannelPipeline pipeline = ch.pipeline();
				pipeline.addLast(SINK_NAME, new OutboundSink());
				Connection.configureSerialization(pipeline, PacketFlow.CLIENTBOUND, true, null);
				if (pipeline.get(RecordingTap.NAME) != null) {
					pipeline.remove(RecordingTap.NAME);
				}
				// We feed exactly one frame per call and stop at protocol switches ourselves.
				if (pipeline.get(FlowControlHandler.class) != null) {
					pipeline.remove(FlowControlHandler.class);
				}
				pipeline.addLast(DISPATCH_NAME, new Dispatcher(conn, filter));
				conn.configurePacketHandler(pipeline);
			}
		});
	}

	public Connection connection() {
		return connection;
	}

	public boolean isOpen() {
		return channel.isOpen();
	}

	/** True while the decoder was removed after a terminal packet and the client has not set up the next protocol. */
	public boolean awaitingProtocolSwitch() {
		return channel.pipeline().get("inbound_config") != null;
	}

	/** Delivers one recorded frame. The data is copied, so the caller may reuse its buffer. */
	public void deliver(byte[] data, int length) {
		ByteBuf buf = Unpooled.copiedBuffer(data, 0, length);
		channel.writeInbound(buf);
	}

	/** Runs queued event-loop tasks and drops anything that reached the end of the pipeline. */
	public void pump() {
		channel.runPendingTasks();
		channel.releaseInbound();
		channel.releaseOutbound();
	}

	public void close() {
		channel.close();
		channel.releaseInbound();
		channel.releaseOutbound();
	}

	public static boolean isReplayChannel(Channel channel) {
		return channel.pipeline().get(DISPATCH_NAME) != null;
	}

	/** Swallows everything the client sends to the (non-existent) server. */
	private static final class OutboundSink extends ChannelOutboundHandlerAdapter {
		@Override
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
			ReferenceCountUtil.release(msg);
			promise.trySuccess();
		}

		@Override
		public void flush(ChannelHandlerContext ctx) {
		}
	}

	/**
	 * Hands decoded packets to the game's packet processor, which is exactly how packets from a real server are
	 * processed (on the render thread, with the game's own error handling).
	 */
	private static final class Dispatcher extends ChannelInboundHandlerAdapter {
		private final Connection connection;
		private final ReplayPacketFilter filter;

		Dispatcher(Connection connection, ReplayPacketFilter filter) {
			this.connection = connection;
			this.filter = filter;
		}

		@Override
		@SuppressWarnings({"unchecked", "rawtypes"})
		public void channelRead(ChannelHandlerContext ctx, Object msg) {
			if (!(msg instanceof Packet<?> packet)) {
				ctx.fireChannelRead(msg);
				return;
			}
			Packet<?> filtered = filter.filter(packet);
			if (filtered == null) {
				return;
			}
			PacketListener listener = connection.getPacketListener();
			if (listener != null && listener.shouldHandleMessage(filtered)) {
				Minecraft.getInstance().packetProcessor().scheduleIfPossible((PacketListener) listener, (Packet) filtered);
			}
		}
	}
}
