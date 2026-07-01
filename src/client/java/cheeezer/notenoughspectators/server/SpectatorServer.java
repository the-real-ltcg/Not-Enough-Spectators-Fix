package cheeezer.notenoughspectators.server;

import cheeezer.notenoughspectators.NotEnoughSpectators;
import com.google.common.collect.Lists;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.flow.FlowControlHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import net.minecraft.SharedConstants;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.UnconfiguredPipelineHandler;
import net.minecraft.network.Varint21FrameDecoder;
import net.minecraft.network.Varint21LengthFieldPrepender;
import net.minecraft.network.protocol.handshake.HandshakeProtocols;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.network.chat.Component;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class SpectatorServer extends Thread {
    private final int port;
    private final int rateLimit;
    private boolean isSetup = false;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ChannelFuture future;
    final List<Connection> connections = Collections.synchronizedList(Lists.newArrayList());

    public SpectatorServer(int port) {
        this(port, 0);
    }

    public SpectatorServer(int port, int rateLimit) {
        this.port = port;
        this.rateLimit = rateLimit;
    }

    public void setup() throws Exception {
        if (isSetup) throw new IllegalStateException("Server is already set up");
        SpectatorServerNetworkHandler.resetSpectatorCount();
        bossGroup = new NioEventLoopGroup();
        workerGroup = new NioEventLoopGroup();
        isSetup = true;
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel channel)
                                throws Exception {
                            try {
                                channel.config().setOption(ChannelOption.TCP_NODELAY, true);
                            } catch (ChannelException ignored) {
                            }

                            channel.pipeline().addLast("timeout", new ReadTimeoutHandler(30))
                                    .addLast("splitter", new Varint21FrameDecoder(null))
                                    .addLast(new FlowControlHandler())
                                    .addLast("decoder", new PacketDecoder<>(HandshakeProtocols.SERVERBOUND))
                                    .addLast("prepender", new Varint21LengthFieldPrepender())
                                    .addLast("outbound_config", new UnconfiguredPipelineHandler.Outbound())
                                    .addLast("handler", new SpectatorServerNetworkHandler(SpectatorServer.this));
                        }
                    }).option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            future = bootstrap.bind(port).sync();
        } catch (Exception e) {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            throw e;
        }
    }

    public void run() {
        if (!isSetup) throw new IllegalStateException("Server is not set up");
        try {
            future.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            NotEnoughSpectators.LOGGER.error("Server interrupted: {}", e.getMessage());
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    public ServerStatus getServerMetadata() {
        return new ServerStatus(Component.literal("A NotEnoughSpectators server"), Optional.empty(), Optional.of(new ServerStatus.Version(SharedConstants.getCurrentVersion().name(), SharedConstants.getProtocolVersion())), Optional.empty(), false);
    }
}
