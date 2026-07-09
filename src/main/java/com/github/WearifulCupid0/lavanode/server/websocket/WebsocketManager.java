package com.github.WearifulCupid0.lavanode.server.websocket;

import com.github.WearifulCupid0.lavanode.Main;
import io.vertx.core.http.ServerWebSocket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WebsocketManager {
    private final Main main;

    private final Map<String, WebsocketConnection> connections = new ConcurrentHashMap<>();

    public WebsocketManager(Main main) {
        this.main = main;
    }

    public WebsocketConnection createNewConnection(String userId, ServerWebSocket ws) {
        String connectionId = UUID.randomUUID().toString();
        WebsocketConnection connection = new WebsocketConnection(userId, connectionId, ws, main);

        this.connections.put(connectionId, connection);

        return connection;
    }

    public boolean resumeConnection(String userId, String connectionId, ServerWebSocket ws) {
        WebsocketConnection connection = this.connections.get(connectionId);

        if (connection == null) {
            return false;
        }

        if (!connection.getUserId().equals(userId)) {
            return false;
        }

        connection.resumeConnection(ws);
        return true;
    }

    public List<WebsocketConnection> getConnections(String identifier) {
        List<WebsocketConnection> conns = new ArrayList<>();

        for (WebsocketConnection connection : connections.values()) {
            if (connection.getUserId().equals(identifier)) {
                conns.add(connection);
            }
        }

        return conns;
    }

    public void deleteConnection(String connectionId) {
        WebsocketConnection conn = connections.remove(connectionId);

        if (conn != null) {
            conn.destroy();
        }
    }
}
