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

        String userId = context.user().principal().getString("sub");

        if (userId == null || userId.isBlank()) {
            userId = context.user().principal().getString("user_id");
        }

        if (userId == null || userId.isBlank()) {
            RequestUtil.handleError(context, 400, "Missing user id in token");
            return;
        }

        try {
            context.put("user-id", Long.toUnsignedString(Long.parseUnsignedLong(userId)));
        } catch (Exception e) {
            RequestUtil.handleError(context, 400, "Invalid user id in token");
            return;
        }

        context.next();
    }
}