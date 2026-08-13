package com.github.WearifulCupid0.lavanode.config.koe;

import moe.kyokobot.koe.Koe;
import moe.kyokobot.koe.KoeClient;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class KoeClientManager {
    private static final Map<Long, KoeClient> clients = new ConcurrentHashMap<>();
    private static volatile Koe koe;

    private KoeClientManager() {
    }

    public static void setKoe(Koe koe) {
        KoeClientManager.koe = Objects.requireNonNull(koe, "koe");
    }

    public static boolean isInitialized() {
        return koe != null;
    }

    public static KoeClient getClient(long userId) {
        Koe currentKoe = koe;
        if (currentKoe == null) {
            throw new IllegalStateException(
                    "Koe has not been initialized. Main must initialize Koe before accepting Discord connections."
            );
        }

        return clients.computeIfAbsent(userId, currentKoe::newClient);
    }

    public static void cleanup() {
        clients.forEach((userId, client) -> {
            if (!client.getConnections().isEmpty()) {
                return;
            }

            if (clients.remove(userId, client)) {
                client.close();
            }
        });
    }
}
