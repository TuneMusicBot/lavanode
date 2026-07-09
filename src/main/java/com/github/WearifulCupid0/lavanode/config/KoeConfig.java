package com.github.WearifulCupid0.lavanode.config;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.lava.common.natives.architecture.DefaultArchitectureTypes;
import com.sedmelluq.lava.common.natives.architecture.DefaultOperatingSystemTypes;
import com.sedmelluq.lava.common.natives.architecture.SystemType;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueDatagramChannel;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import moe.kyokobot.koe.Koe;
import moe.kyokobot.koe.KoeOptions;
import moe.kyokobot.koe.codec.CodecRegistry;
import moe.kyokobot.koe.codec.DefaultCodecRegistry;
import moe.kyokobot.koe.gateway.GatewayVersion;
import moe.kyokobot.koe.poller.FramePollerFactory;
import moe.kyokobot.koe.poller.netty.NettyFramePollerFactory;
import moe.kyokobot.koe.poller.udpqueue.QueueManagerPool;
import moe.kyokobot.koe.poller.udpqueue.UdpQueueFramePollerFactory;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

public class KoeConfig implements KoeOptions {
    private static final Logger log = LoggerFactory.getLogger(KoeConfig.class);

    private static final List<SystemType> SUPPORTED_SYSTEMS = List.of(
            new SystemType(DefaultArchitectureTypes.ARM, DefaultOperatingSystemTypes.LINUX),
            new SystemType(DefaultArchitectureTypes.X86_64, DefaultOperatingSystemTypes.LINUX),
            new SystemType(DefaultArchitectureTypes.X86_32, DefaultOperatingSystemTypes.LINUX),
            new SystemType(DefaultArchitectureTypes.ARMv8_64, DefaultOperatingSystemTypes.LINUX),

            //new SystemType(DefaultArchitectureTypes.X86_64, DefaultOperatingSystemTypes.LINUX_MUSL),
            //new SystemType(DefaultArchitectureTypes.ARMv8_64, DefaultOperatingSystemTypes.LINUX_MUSL),

            new SystemType(DefaultArchitectureTypes.X86_64, DefaultOperatingSystemTypes.WINDOWS),
            new SystemType(DefaultArchitectureTypes.X86_32, DefaultOperatingSystemTypes.WINDOWS),
            new SystemType(DefaultArchitectureTypes.ARMv8_64, DefaultOperatingSystemTypes.WINDOWS),

            new SystemType(DefaultArchitectureTypes.X86_64, DefaultOperatingSystemTypes.DARWIN),
            new SystemType(DefaultArchitectureTypes.ARMv8_64, DefaultOperatingSystemTypes.DARWIN)
    );

    private EventLoopGroup eventLoopGroup;
    private Class<? extends SocketChannel> socketChannelClass;
    private Class<? extends DatagramChannel> datagramChannelClass;
    private FramePollerFactory framePollerFactory = new NettyFramePollerFactory();

    private CodecRegistry codecRegistry = new DefaultCodecRegistry();

    public static Koe createKoe() {
        return Koe.koe(new KoeConfig());
    }

    public KoeConfig() {
        if (Epoll.isAvailable()) {
            log.info("Epoll Netty supported! Setting up...");

            eventLoopGroup = new EpollEventLoopGroup();
            socketChannelClass = EpollSocketChannel.class;
            datagramChannelClass = EpollDatagramChannel.class;
        } else if (KQueue.isAvailable()) {
            log.info("Kqueue Netty supported! Setting up...");

            eventLoopGroup = new KQueueEventLoopGroup();
            socketChannelClass = KQueueSocketChannel.class;
            datagramChannelClass = KQueueDatagramChannel.class;
        } else {
            log.info("Epoll and Kqueue Netty are not supported! Setting up nio...");

            eventLoopGroup = new NioEventLoopGroup();
            socketChannelClass = NioSocketChannel.class;
            datagramChannelClass = NioDatagramChannel.class;
        }

        log.debug("Checking if UDP Queue is available...");

        SystemType systemType;

        try {
            systemType = new SystemType(
                    DefaultArchitectureTypes.detect(),
                    DefaultOperatingSystemTypes.detect()
            );
        } catch (IllegalArgumentException exception) {
            systemType = null;
        }

        log.info(
                "OS: {}, Arch: {}",
                systemType != null ? systemType.osType : "unknown",
                systemType != null ? systemType.architectureType : "unknown"
        );

        SystemType finalSystemType = systemType;
        boolean nasSupported = systemType != null && SUPPORTED_SYSTEMS.stream().anyMatch(supportedSystem ->
                Objects.equals(supportedSystem.osType, finalSystemType.osType)
                        && Objects.equals(supportedSystem.architectureType, finalSystemType.architectureType)
        );

        if (nasSupported) {
            log.info("NAS is supported! Setting up UDP Queue...");

            framePollerFactory = new UdpQueueFramePollerFactory(new QueueManagerPool(Runtime.getRuntime().availableProcessors(), QueueManagerPool.DEFAULT_BUFFER_DURATION));
        } else {
            log.info("NAS is not supported! Using NettyFrame poller may cause stuttering during playback.");
        }
    }

    @Override
    public @NotNull EventLoopGroup getEventLoopGroup() {
        return eventLoopGroup;
    }

    @Override
    public @NotNull Class<? extends SocketChannel> getSocketChannelClass() {
        return socketChannelClass;
    }

    @Override
    public @NotNull Class<? extends DatagramChannel> getDatagramChannelClass() {
        return datagramChannelClass;
    }

    @Override
    public @NotNull ByteBufAllocator getByteBufAllocator() {
        return ByteBufAllocator.DEFAULT;
    }

    @Override
    public @NotNull GatewayVersion getGatewayVersion() {
        return GatewayVersion.V8;
    }

    @Override
    public @NotNull FramePollerFactory getFramePollerFactory() {
        return framePollerFactory;
    }

    @Override
    public @NotNull CodecRegistry getCodecRegistry() {
        return codecRegistry;
    }

    @Override
    public boolean isExperimental() {
        return false;
    }

    @Override
    public boolean isHighPacketPriority() {
        return true;
    }

    @Override
    public boolean isDeafened() {
        return true;
    }

    @Override
    public boolean isEnableWSSPortOverride() {
        return false;
    }

    @Override
    public boolean isVerifyWSSHostname() {
        return true;
    }

    @Override
    public boolean isEnableDAVE() {
        return true;
    }

    @Override
    public boolean isEnableDAVELogSink() {
        return true;
    }
}
