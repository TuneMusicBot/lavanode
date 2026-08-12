package com.github.WearifulCupid0.lavanode.server.handlers;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

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

        String requestId = UUID.randomUUID().toString();
        if (statusCode >= 500) {
            log.error("Unhandled REST error [{}]", requestId, throwable);
        } else {
            log.debug(
                    "REST failure {} [{}]: {}",
                    statusCode,
                    requestId,
                    throwable == null ? null : throwable.getMessage()
            );
        }

        JsonObject json = new JsonObject()
                .put("error", true)
                .put("status", statusCode)
                .put("message", defaultMessage(statusCode))
                .put("requestId", requestId);

        context.response()
                .setStatusCode(statusCode)
                .putHeader("Content-Type", "application/json")
                .end(json.encode());
    }

    private static String defaultMessage(int statusCode) {
        return switch (statusCode) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 409 -> "Conflict";
            case 410 -> "Gone";
            case 413 -> "Payload Too Large";
            case 415 -> "Unsupported Media Type";
            case 429 -> "Too Many Requests";
            default -> "Internal Server Error";
        };
    }
}
