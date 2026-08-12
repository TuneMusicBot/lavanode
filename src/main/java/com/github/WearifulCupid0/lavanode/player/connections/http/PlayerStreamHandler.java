package com.github.WearifulCupid0.lavanode.player.connections.http;

import com.github.WearifulCupid0.lavanode.Main;
import com.github.WearifulCupid0.lavanode.player.PlayerSession;
import com.github.WearifulCupid0.lavanode.player.connections.OpusFrameSubscription;
import com.github.WearifulCupid0.lavanode.util.RequestUtil;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

import java.util.List;

/** Exposes a player through a short-lived stream bearer token. */
public final class PlayerStreamHandler implements Handler<RoutingContext> {
    private static final int STREAM_BUFFER_FRAMES = 50;
    private static final String BEARER_PREFIX = "Bearer ";

    private final Main main;

    public PlayerStreamHandler(Main main) {
        this.main = main;
    }

    @Override
    public void handle(RoutingContext context) {
        String playerId = context.get("playerId");
        String rawToken = readToken(context);
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

        try {
            if (!connection.start() && !context.response().ended()) {
                RequestUtil.handleError(context, 401, "Invalid or expired stream token");
            }
        } catch (IllegalStateException exception) {
            subscription.close();
            if (!context.response().ended()) {
                RequestUtil.handleError(
                        context,
                        player.isDestroyed() ? 410 : 429,
                        player.isDestroyed() ? "Player is no longer available" : exception.getMessage()
                );
            }
        }
    }

    private static String readToken(RoutingContext context) {
        String authorization = context.request().getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String bearer = authorization.substring(BEARER_PREFIX.length()).trim();
            if (!bearer.isEmpty()) {
                return bearer;
            }
        }

        // Backwards compatibility. New clients should use Authorization: Bearer.
        return firstQueryParam(context, "token");
    }

    private static String firstQueryParam(RoutingContext context, String name) {
        List<String> values = context.queryParam(name);
        return values.isEmpty() ? null : values.get(0);
    }
}
