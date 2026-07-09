package com.github.WearifulCupid0.lavanode.server.handlers;

import com.github.WearifulCupid0.lavanode.util.RequestUtil;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

public final class JsonBodyHandler implements Handler<RoutingContext> {
    private static final String JSON_BODY_KEY = "jsonBody";

    @Override
    public void handle(RoutingContext context) {
        HttpMethod method = context.request().method();

        if (method != HttpMethod.POST &&
                method != HttpMethod.PUT &&
                method != HttpMethod.PATCH) {
            context.next();
            return;
        }

        String contentType = context.request().getHeader("Content-Type");

        if (contentType == null || !contentType.toLowerCase().contains("application/json")) {
            RequestUtil.handleError(context, 415, "Content-Type must be application/json");
            return;
        }

        try {
            JsonObject body = context.body().asJsonObject();

            if (body == null) {
                RequestUtil.handleError(context, 400, "Request body must be a JSON object");
                return;
            }

            context.put(JSON_BODY_KEY, body);
            context.next();
        } catch (DecodeException | IllegalArgumentException e) {
            context.fail(400, e);
        }
    }

    public static JsonObject getBody(RoutingContext context) {
        return context.get(JSON_BODY_KEY);
    }
}