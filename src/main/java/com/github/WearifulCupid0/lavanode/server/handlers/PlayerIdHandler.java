package com.github.WearifulCupid0.lavanode.server.handlers;

import com.github.WearifulCupid0.lavanode.util.RequestUtil;
import com.sedmelluq.lavaplayer.extensions.thirdpartysources.SourceTools;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

import java.util.UUID;

public final class PlayerIdHandler implements Handler<RoutingContext> {
    @Override
    public void handle(RoutingContext context) {
        String playerId = context.pathParam("playerId");

        if (SourceTools.isBlank(playerId)) {
            RequestUtil.handleError(context, 400, "Missing player id parameter");
            return;
        }

        try {
            playerId = UUID.fromString(playerId).toString();
        } catch (IllegalArgumentException exception) {
            RequestUtil.handleError(context, 400, "Invalid player id parameter");
            return;
        }

        context.put("playerId", playerId);
        context.next();
    }
}
