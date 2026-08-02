package com.github.WearifulCupid0.lavanode.player.connections;

import io.vertx.core.json.JsonObject;

public interface PlayerConnection extends AutoCloseable {
    String getId();

    ConnectionType getType();

    ConnectionState getState();

    long getCreatedAt();

    Long getConnectedAt();

    JsonObject toJson();

    void disconnect(String reason);

    @Override
    default void close() {
        disconnect("closed");
    }
}
