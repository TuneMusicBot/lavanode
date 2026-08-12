package com.github.WearifulCupid0.lavanode.player;

import com.github.WearifulCupid0.lavanode.player.connections.ConnectionType;
import io.vertx.core.json.JsonObject;

import java.util.EnumMap;
import java.util.Map;

public class PlayerSettings {
    private static final int MAX_QUEUE_SIZE = readServerInt("MAX_PLAYER_QUEUE_SIZE", 5_000, 1, 100_000);
    private static final int MAX_HISTORY_SIZE = readServerInt("MAX_PLAYER_HISTORY_SIZE", 1_000, 0, 100_000);
    private static final int MAX_DISCORD_CONNECTIONS = readServerInt("MAX_DISCORD_CONNECTIONS_PER_PLAYER", 8, 0, 100);
    private static final int MAX_HTTP_CONNECTIONS = readServerInt("MAX_HTTP_CONNECTIONS_PER_PLAYER", 8, 0, 10_000);

    private static final int DEFAULT_QUEUE_SIZE = Math.min(
            readServerInt("DEFAULT_PLAYER_QUEUE_SIZE", 1_000, 0, 100_000),
            MAX_QUEUE_SIZE
    );
    private static final int DEFAULT_HISTORY_SIZE = Math.min(
            readServerInt("DEFAULT_PLAYER_HISTORY_SIZE", 100, 0, 100_000),
            MAX_HISTORY_SIZE
    );
    private static final int DEFAULT_DISCORD_CONNECTIONS = Math.min(
            readServerInt("DEFAULT_DISCORD_CONNECTIONS_PER_PLAYER", 4, 0, 100),
            MAX_DISCORD_CONNECTIONS
    );
    private static final int DEFAULT_HTTP_CONNECTIONS = Math.min(
            readServerInt("DEFAULT_HTTP_CONNECTIONS_PER_PLAYER", 4, 0, 10_000),
            MAX_HTTP_CONNECTIONS
    );

    private final int queueSize;
    private final int historySize;
    private final Map<ConnectionType, Integer> limits;
    private final boolean filtersEnabled;

    public static PlayerSettings fromJson(JsonObject input) {
        if (input == null) {
            input = new JsonObject();
        }

        int queueSize = boundedClientInt(input.getValue("queueSize"), DEFAULT_QUEUE_SIZE, MAX_QUEUE_SIZE, "queueSize");
        int historySize = boundedClientInt(input.getValue("historySize"), DEFAULT_HISTORY_SIZE, MAX_HISTORY_SIZE, "historySize");

        Map<ConnectionType, Integer> limits = new EnumMap<>(ConnectionType.class);
        limits.put(ConnectionType.DISCORD, DEFAULT_DISCORD_CONNECTIONS);
        limits.put(ConnectionType.HTTP, DEFAULT_HTTP_CONNECTIONS);

        Object rawLimits = input.getValue("limits");
        if (rawLimits != null && !(rawLimits instanceof JsonObject)) {
            throw new IllegalArgumentException("limits must be an object");
        }
        JsonObject limitsJson = (JsonObject) rawLimits;
        if (limitsJson != null) {
            for (String key : limitsJson.fieldNames()) {
                ConnectionType type = ConnectionType.fromJson(key);
                if (type == null) {
                    continue;
                }

                int serverMaximum = switch (type) {
                    case DISCORD -> MAX_DISCORD_CONNECTIONS;
                    case HTTP -> MAX_HTTP_CONNECTIONS;
                };
                int defaultValue = limits.getOrDefault(type, serverMaximum);
                limits.put(type, boundedClientInt(limitsJson.getValue(key), defaultValue, serverMaximum, "limits." + key));
            }
        }

        Object rawFiltersEnabled = input.getValue("filtersEnabled");
        boolean filtersEnabled;
        if (rawFiltersEnabled == null) {
            filtersEnabled = true;
        } else if (rawFiltersEnabled instanceof Boolean value) {
            filtersEnabled = value;
        } else {
            throw new IllegalArgumentException("filtersEnabled must be a boolean");
        }

        return new PlayerSettings(queueSize, historySize, limits, filtersEnabled);
    }

    public PlayerSettings(
            int queueSize,
            int historySize,
            Map<ConnectionType, Integer> limits,
            boolean filtersEnabled
    ) {
        this.queueSize = Math.max(0, Math.min(queueSize, MAX_QUEUE_SIZE));
        this.historySize = Math.max(0, Math.min(historySize, MAX_HISTORY_SIZE));
        EnumMap<ConnectionType, Integer> normalizedLimits = new EnumMap<>(ConnectionType.class);
        normalizedLimits.put(ConnectionType.DISCORD, DEFAULT_DISCORD_CONNECTIONS);
        normalizedLimits.put(ConnectionType.HTTP, DEFAULT_HTTP_CONNECTIONS);
        if (limits != null) {
            limits.forEach((type, value) -> {
                if (type == null || value == null) {
                    return;
                }
                int maximum = type == ConnectionType.DISCORD ? MAX_DISCORD_CONNECTIONS : MAX_HTTP_CONNECTIONS;
                normalizedLimits.put(type, Math.max(0, Math.min(value, maximum)));
            });
        }
        this.limits = Map.copyOf(normalizedLimits);
        this.filtersEnabled = filtersEnabled;
    }

    public int getQueueSize() {
        return queueSize;
    }

    public int getHistorySize() {
        return historySize;
    }

    public Map<ConnectionType, Integer> getLimits() {
        return limits;
    }

    public int getConnectionLimit(ConnectionType type) {
        if (type == null) {
            return 0;
        }
        return limits.getOrDefault(type, 0);
    }

    public int getDiscordLimits() {
        return getConnectionLimit(ConnectionType.DISCORD);
    }

    public int getHttpLimits() {
        return getConnectionLimit(ConnectionType.HTTP);
    }

    public boolean isFiltersEnabled() {
        return filtersEnabled;
    }

    public JsonObject toJson() {
        JsonObject limits = new JsonObject();
        for (Map.Entry<ConnectionType, Integer> entry : this.limits.entrySet()) {
            limits.put(entry.getKey().jsonName(), entry.getValue());
        }

        return new JsonObject()
                .put("queueSize", queueSize)
                .put("historySize", historySize)
                .put("filtersEnabled", filtersEnabled)
                .put("limits", limits);
    }

    private static int boundedClientInt(Object raw, int defaultValue, int maximum, String field) {
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException(field + " must be a number");
        }

        long value = number.longValue();
        if (value < 0L) {
            // Legacy -1/unlimited requests are converted to the server-side cap.
            return maximum;
        }
        return (int) Math.min(value, maximum);
    }

    private static int readServerInt(String name, int defaultValue, int minValue, int maxValue) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            value = System.getenv(name);
        }
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(minValue, Math.min(maxValue, parsed));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}
