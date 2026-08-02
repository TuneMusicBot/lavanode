package com.github.WearifulCupid0.lavanode.player.connections.discord;

import moe.kyokobot.koe.KoeEventAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/** Maps Koe transport callbacks to generic connection events. */
public final class KoeEventHandler extends KoeEventAdapter {
    private static final Logger log = LoggerFactory.getLogger(KoeEventHandler.class);

    private final DiscordPlayerConnection connection;

    public KoeEventHandler(DiscordPlayerConnection connection) {
        this.connection = connection;
    }

    @Override
    public void gatewayError(Throwable cause) {
        log.error("Discord gateway error for connection {}", connection.getId(), cause);
        connection.gatewayError(cause);
    }

    @Override
    public void gatewayReady(InetSocketAddress address, int ssrc) {
        log.debug("Discord gateway ready for connection {}, ssrc {}", connection.getId(), ssrc);
        connection.gatewayReady(address, ssrc);
    }

    @Override
    public void gatewayClosed(int code, String reason, boolean byRemote) {
        log.debug(
                "Discord gateway closed for connection {}, code {}, reason {}",
                connection.getId(),
                code,
                reason
        );
        connection.gatewayClosed(code, reason, byRemote);
    }
}
