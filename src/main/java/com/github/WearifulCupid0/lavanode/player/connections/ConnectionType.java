package com.github.WearifulCupid0.lavanode.player.connections;

public enum ConnectionType {
    DISCORD("discord"),
    HTTP("http");

    private final String jsonName;

    ConnectionType(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }

    public static ConnectionType fromJson(String value) {
        if (value == null) {
            return null;
        }

        for (ConnectionType type : values()) {
            if (type.jsonName.equalsIgnoreCase(value) || type.name().equalsIgnoreCase(value)) {
                return type;
            }
        }

        return null;
    }
}
