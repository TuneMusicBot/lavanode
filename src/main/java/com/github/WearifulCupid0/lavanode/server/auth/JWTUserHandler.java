package com.github.WearifulCupid0.lavanode.server.auth;

import com.github.WearifulCupid0.lavanode.util.RequestUtil;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

public final class JWTUserHandler implements Handler<RoutingContext> {
    @Override
    public void handle(RoutingContext context) {
        if (context.user() == null) {
            RequestUtil.handleError(context, 401, "Unauthorized");
            return;
        }

        String identifier = context.user().principal().getString("sub");

        if (identifier == null || identifier.isBlank()) {
            identifier = context.user().principal().getString("identifier");
        }

        if (identifier == null || identifier.isBlank()) {
            RequestUtil.handleError(context, 400, "Missing identifier in token");
            return;
        }

        try {
            context.put("identifier", identifier);
        } catch (Exception e) {
            RequestUtil.handleError(context, 400, "Invalid identifier in token");
            return;
        }

        context.next();
    }
}