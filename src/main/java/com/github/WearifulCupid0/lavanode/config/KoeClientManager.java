package com.github.WearifulCupid0.lavanode.config;

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
}
