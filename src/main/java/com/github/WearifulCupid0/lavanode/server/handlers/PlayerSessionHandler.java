package com.github.WearifulCupid0.lavanode.server.handlers;

import com.github.WearifulCupid0.lavanode.Main;
import com.github.WearifulCupid0.lavanode.player.PlayerSession;
import com.github.WearifulCupid0.lavanode.player.PlayerSessionManager;
import com.github.WearifulCupid0.lavanode.server.auth.JWTUserHandler;
import com.github.WearifulCupid0.lavanode.util.RequestUtil;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

public final class PlayerSessionHandler implements Handler<RoutingContext> {
    private final Main main;

    public PlayerSessionHandler(Main main) {
        this.main = main;
    }

    @Override
    public void handle(RoutingContext context) {
        String playerId = context.get("playerId");
        if (playerId == null) {
            RequestUtil.handleError(context, 500, "Player id was not resolved");
            return;
        }

        String identifier = context.get(JWTUserHandler.IDENTIFIER_CONTEXT_KEY);
        if (identifier == null || identifier.isBlank()) {
            RequestUtil.handleError(context, 401, "Missing authenticated identifier");
            return;
        }

        PlayerSessionManager manager = main.getPlayerManager().getOrCreate(identifier);
        PlayerSession player = manager.get(playerId);
        if (player == null) {
            RequestUtil.handleError(context, 404, "Unknown player");
            return;
        }

        player.touch();
        context.put("playerSessionManager", manager);
        context.put("playerSession", player);
        context.next();
    }
}
