package com.github.WearifulCupid0.lavanode.player.voice;

import com.github.WearifulCupid0.lavanode.player.PlayerSession;
import moe.kyokobot.koe.KoeEventAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

public class KoeEventHandler extends KoeEventAdapter {
    private static final Logger log = LoggerFactory.getLogger(KoeEventHandler.class);

    private final PlayerSession playerSession;

    public KoeEventHandler(PlayerSession playerSession) {
        this.playerSession = playerSession;
    }

    @Override
    public void gatewayError(Throwable cause) {
        log.error("An error occurred while connecting to Discord gateway: ", cause);
        playerSession.getListener().onGatewayError(playerSession, cause);
    }

    @Override
    public void gatewayReady(InetSocketAddress address, int ssrc) {
        log.debug("Websocket ready with ssrc {}, Player ID {}", ssrc, playerSession.getId());
        playerSession.setConnected();
        playerSession.getListener().onGatewayReady(playerSession, address, ssrc);
    }

    @Override
    public void gatewayClosed(int code, String reason, boolean byRemote) {
        log.debug("Websocket closed with code {} and reason {}, Player ID {}", code, reason, playerSession.getId());
        playerSession.setDisconnected();
        playerSession.getListener().onGatewayClosed(playerSession, code, reason, byRemote);
    }
}
