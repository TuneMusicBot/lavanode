package com.github.WearifulCupid0.lavanode.player;

import com.github.WearifulCupid0.lavanode.player.connections.ConnectionType;
import io.vertx.core.json.JsonObject;

import java.util.Map;

public class PlayerSettings {
    private final int queueSize;
    private final int historySize;
    private final Map<ConnectionType, Integer> limits;
    private boolean filtersEnabled;

    public PlayerSettings(
            int queueSize,
            int historySize,
            Map<ConnectionType, Integer> limits,
            boolean filtersEnabled
    ) {
        this.queueSize = queueSize;
        this.historySize = historySize;
        this.limits = limits;
        this.filtersEnabled = filtersEnabled;
    }

    public int getQueueSize() { return queueSize; }

    public int getHistorySize() { return historySize; }

    public Map<ConnectionType, Integer> getLimits() { return limits; }

    public int getDiscordLimits() { return limits.get(ConnectionType.DISCORD); }

    public int getHttpLimits() { return limits.get(ConnectionType.HTTP); }

    public boolean isFiltersEnabled() { return filtersEnabled; }

    public JsonObject toJson() {
        JsonObject limits = new JsonObject();

        for (ConnectionType type : this.limits.keySet())
            limits.put(type.jsonName(), this.limits.get(type));

        return new JsonObject()
                .put("queueSize", queueSize)
                .put("historySize", historySize)
                .put("filtersEnabled", filtersEnabled)
                .put("limits", limits);
    }
}
