package com.github.WearifulCupid0.lavanode.player.connections;

public enum ConnectionState {
    CREATED("created"),
    CONNECTING("connecting"),
    CONNECTED("connected"),
    DISCONNECTED("disconnected"),
    CLOSING("closing"),
    CLOSED("closed"),
    ERROR("error");

    private final String jsonName;

    ConnectionState(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }
}
