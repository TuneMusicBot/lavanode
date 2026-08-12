package com.github.WearifulCupid0.lavanode.config.koe;

import moe.kyokobot.koe.Koe;
import moe.kyokobot.koe.KoeClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class KoeClientManager {
    private static final Map<Long, KoeClient> clients = new ConcurrentHashMap<>();

    private static Koe koe;

    public static void setKoe(Koe koe) {
        KoeClientManager.koe = koe;
    }

    public static KoeClient getClient(long userId) {
        if (koe == null)
            throw new NullPointerException("Koe not defined");
        return clients.computeIfAbsent(userId, koe::newClient);
    }

    public static void cleanup() {
        for (KoeClient item : clients.values()) {
            if (item.getConnections().isEmpty()) {
                item.close();
                clients.remove(item.getClientId());
            }
        }
    }
}
