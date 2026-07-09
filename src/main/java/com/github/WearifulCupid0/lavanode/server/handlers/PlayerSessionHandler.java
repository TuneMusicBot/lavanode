package com.github.WearifulCupid0.lavanode.server.handlers;

import com.github.WearifulCupid0.lavanode.Main;
import com.github.WearifulCupid0.lavanode.player.PlayerSession;
import com.github.WearifulCupid0.lavanode.player.PlayerSessionManager;
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
        String guildId = context.get("guildId");

        if (guildId == null) {
            RequestUtil.handleError(context, 500, "Guild id was not resolved");
            return;
        }

        PlayerSessionManager playerSessionManager = main.getPlayerManager().getOrCreate(context.get("user-id"));
        PlayerSession playerSession = playerSessionManager.getOrCreate(guildId);

        if (playerSession == null) {
            RequestUtil.handleError(context, 404, "Unknown player");
            return;
        }

        context.put("playerSessionManager", playerSessionManager);
        context.put("playerSession", playerSession);
        context.next();
    }
}
