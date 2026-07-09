package com.github.WearifulCupid0.lavanode.server.handlers;

import com.github.WearifulCupid0.lavanode.util.RequestUtil;
import com.sedmelluq.lavaplayer.extensions.thirdpartysources.SourceTools;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

public final class GuildIdHandler implements Handler<RoutingContext> {
    @Override
    public void handle(RoutingContext context) {
        String guildId = context.pathParam("guildId");

        if (SourceTools.isBlank(guildId)) {
            RequestUtil.handleError(context, 400, "Missing guild id parameter");
            return;
        }

        try {
            guildId = Long.toUnsignedString(Long.parseUnsignedLong(guildId));
        } catch (Exception e) {
            RequestUtil.handleError(context, 400, "Invalid guild id parameter");
            return;
        }

        context.put("guildId", guildId);
        context.next();
    }
}
