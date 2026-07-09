package com.github.WearifulCupid0.lavanode.server;

import com.github.WearifulCupid0.lavanode.Main;
import com.github.WearifulCupid0.lavanode.server.websocket.WebsocketConnection;
import com.github.WearifulCupid0.lavanode.server.websocket.WebsocketManager;
import com.sedmelluq.lavaplayer.extensions.thirdpartysources.SourceTools;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebsocketHandler {
    private static final Logger log = LoggerFactory.getLogger(WebsocketHandler.class);

    public static void setup(Main main, Router router) {
        log.debug("Creating websocket handler...");

        router.route("/v1/websocket").handler(handler(main));

        log.debug("Websocket route registered!");
    }

    private static Handler<RoutingContext> handler(Main main) {
        return context -> {
            HttpServerRequest req = context.request();
            if("websocket".equalsIgnoreCase(req.getHeader("upgrade"))) {
                Future<ServerWebSocket> socketFuture = req.toWebSocket();

                String userId = context.get("user-id");
                log.debug("Incoming websocket connection from user {}", userId);

                socketFuture.andThen(serverSocket -> {
                    if (serverSocket.failed()) {
                        log.debug("Connection from user {} failed before ready", userId);
                        return;
                    }

                    log.debug("New connection from user {}", userId);
                    ServerWebSocket ws = serverSocket.result();

                    String resumeId = context.request().getHeader("Resume-Key");

                    WebsocketManager websocketManager = main.getWebsocketManager();
                    if (!SourceTools.isBlank(resumeId)) {
                        log.debug("Trying to resume websocket connection from user {}", userId);
                        boolean resumed = websocketManager.resumeConnection(userId, resumeId, ws);

                        if (resumed) {
                            log.info("Websocket connection from user {} resumed", userId);

                            return;
                        }
                        log.debug("Unknown connection, failed to resume connection from user {}", userId);
                    }

                    WebsocketConnection connection = websocketManager.createNewConnection(userId, ws);
                    connection.start();
                });

                return;
            }

            context.next();
        };
    }
}
