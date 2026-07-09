package com.github.WearifulCupid0.lavanode.server.handlers;

import com.github.WearifulCupid0.lavanode.util.RequestUtil;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RestFailureHandler {
    private static final Logger log = LoggerFactory.getLogger(RestFailureHandler.class);

    public static void handle(RoutingContext context) {
        Throwable throwable = context.failure();

        int statusCode = context.statusCode();

        if (statusCode < 400) {
            statusCode = 500;
        }

        if (context.response().ended()) {
            return;
        }

        if (statusCode >= 500) {
            log.error("Unhandled REST error", throwable);
        } else {
            log.debug("REST failure {}: {}", statusCode, throwable == null ? null : throwable.getMessage());
        }

        JsonObject json = new JsonObject()
                .put("error", true)
                .put("status", statusCode)
                .put("message", throwable != null && throwable.getMessage() != null
                        ? throwable.getMessage()
                        : defaultMessage(statusCode));

        if (throwable != null) {
            json.put("exception", RequestUtil.encodeThrowable(throwable));
        }

        context.response()
                .setStatusCode(statusCode)
                .putHeader("Content-Type", "application/json")
                .end(json.encodePrettily());
    }

    private static String defaultMessage(int statusCode) {
        return switch (statusCode) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            default -> "Internal Server Error";
        };
    }
}