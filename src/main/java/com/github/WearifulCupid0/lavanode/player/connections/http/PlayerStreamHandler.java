package com.github.WearifulCupid0.lavanode.player.connections.http;

import com.github.WearifulCupid0.lavanode.Main;
import com.github.WearifulCupid0.lavanode.player.PlayerSession;
import com.github.WearifulCupid0.lavanode.player.connections.OpusFrameSubscription;
import com.github.WearifulCupid0.lavanode.util.RequestUtil;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

import java.util.List;

/** Exposes a player through a stream token supplied as a query parameter. */
public final class PlayerStreamHandler implements Handler<RoutingContext> {
    private static final int STREAM_BUFFER_FRAMES = 50;

    private final Main main;

    public PlayerStreamHandler(Main main) {
        this.main = main;
    }

    @Override
    public void handle(RoutingContext context) {
        String playerId = context.get("playerId");
        String rawToken = firstQueryParam(context, "token");

        if (rawToken == null || rawToken.isBlank()) {
            RequestUtil.handleError(context, 401, "Missing stream token");
            return;
        }

        StreamTokenManager.StreamToken token = main
                .getStreamTokenManager()
                .resolve(rawToken, playerId);

        if (token == null) {
            RequestUtil.handleError(context, 401, "Invalid or expired stream token");
            return;
        }

        PlayerSession player = token.getPlayerSession();
        final OpusFrameSubscription subscription;

        try {
            subscription = player.openOpusFrameSubscription(STREAM_BUFFER_FRAMES);
        } catch (IllegalStateException exception) {
            RequestUtil.handleError(context, 410, "Player is no longer available");
            return;
        }

        String ip = context.request().remoteAddress() == null
                ? "unknown"
                : context.request().remoteAddress().host();

        HttpPlayerConnection connection = new HttpPlayerConnection(
                main,
                main.getStreamTokenManager(),
                token,
                subscription,
                context.response(),
                ip
        );

        if (!connection.start() && !context.response().ended()) {
            RequestUtil.handleError(context, 401, "Invalid or expired stream token");
        }
    }

    private static String firstQueryParam(RoutingContext context, String name) {
        List<String> values = context.queryParam(name);
        return values.isEmpty() ? null : values.get(0);
    }
}
