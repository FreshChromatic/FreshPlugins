package github.freshchromatic.freshlib.util;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

/**
 * Utility for monitoring packet traffic using NMS and Netty.
 */
public final class PacketMonitor {
    private static final String HANDLER_NAME = "freshlib_traffic_monitor";

    private PacketMonitor() {
    }

    /**
     * Injects a traffic monitor into the player's network pipeline.
     *
     * @param player          The player to monitor
     * @param outboundBytes   AtomicLong for Server -> Player bytes
     * @param inboundBytes    AtomicLong for Player -> Server bytes
     * @param outboundPackets AtomicLong for Server -> Player packet count
     * @param inboundPackets  AtomicLong for Player -> Server packet count
     */
    public static void inject(
            final Player player,
            final AtomicLong outboundBytes,
            final AtomicLong inboundBytes,
            final AtomicLong outboundPackets,
            final AtomicLong inboundPackets
    ) {
        if (player == null) {
            return;
        }
        final ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
        final Channel channel = serverPlayer.connection.connection.channel;

        if (channel == null || !channel.isOpen() || channel.pipeline().get(HANDLER_NAME) != null) {
            return;
        }

        channel.pipeline().addBefore("packet_handler", HANDLER_NAME, new ChannelDuplexHandler() {
            @Override
            public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
                outboundPackets.incrementAndGet();
                if (msg instanceof ByteBuf buf) {
                    outboundBytes.addAndGet(buf.readableBytes());
                }
                super.write(ctx, msg, promise);
            }

            @Override
            public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                inboundPackets.incrementAndGet();
                if (msg instanceof ByteBuf buf) {
                    inboundBytes.addAndGet(buf.readableBytes());
                }
                super.channelRead(ctx, msg);
            }
        });
    }

    /**
     * Removes the traffic monitor from the player's network pipeline.
     *
     * @param player The player to uninject
     */
    public static void uninject(final Player player) {
        if (player == null) {
            return;
        }
        final ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
        final Channel channel = serverPlayer.connection.connection.channel;

        if (channel != null && channel.pipeline().get(HANDLER_NAME) != null) {
            channel.pipeline().remove(HANDLER_NAME);
        }
    }
}
