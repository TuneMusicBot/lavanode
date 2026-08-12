package com.github.WearifulCupid0.lavanode.config.koe;

import moe.kyokobot.koe.Koe;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class KoeClientManager {
    private static final Map<Long, KoeClientItem> clients = new ConcurrentHashMap<>();

    private static Koe koe;

    public static void setKoe(Koe koe) {
        KoeClientManager.koe = koe;
    }

    public static KoeClientItem getClient(long userId) {
        if (koe == null)
            throw new NullPointerException("Koe not defined");
        return clients.computeIfAbsent(userId, (id) -> new KoeClientItem(koe.newClient(id)));
    }

    public static void cleanup() {
        for (KoeClientItem item : clients.values())
            if (item.shoudBeDeleted()) item.shutdown();
    }
}
