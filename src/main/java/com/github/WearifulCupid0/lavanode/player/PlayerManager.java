package com.github.WearifulCupid0.lavanode.player;

import com.github.WearifulCupid0.lavanode.Main;
import com.github.WearifulCupid0.lavanode.config.koe.KoeClientManager;
import com.github.WearifulCupid0.lavanode.server.websocket.WebsocketConnection;
import com.github.WearifulCupid0.lavanode.server.websocket.WebsocketOpCodes;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import io.vertx.core.json.JsonObject;
import moe.kyokobot.koe.KoeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class PlayerManager {
    private static final Logger log = LoggerFactory.getLogger(PlayerManager.class);

    private final Map<String, PlayerSessionManager> sessionMap = new ConcurrentHashMap<>();
    private final Main main;
    private final ExecutorService preloadExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            new NamedDaemonThreadFactory("lavanode-preload")
    );
    private final ScheduledExecutorService frameDispatchExecutor = Executors.newScheduledThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            new NamedDaemonThreadFactory("lavanode-frame-dispatch")
    );

    public PlayerManager(Main main) {
        this.main = main;
    }

    public PlayerSessionManager getOrCreate(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Player manager identifier is required");
        }

        String normalizedIdentifier = identifier.trim();
        return this.sessionMap.computeIfAbsent(normalizedIdentifier, id ->
                new PlayerSessionManager(
                        this.main.getAudioPlayerManager(),
                        new PlayerSessionEventListener(this),
                        this.main.getStreamTokenManager(),
                        id,
                        preloadExecutor,
                        frameDispatchExecutor
                )
        );
    }

    public void destroy(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return;
        }

        String normalizedIdentifier = identifier.trim();
        PlayerSessionManager session = this.sessionMap.remove(normalizedIdentifier);
        if (session != null) {
            log.debug("Destroying all players from identifier: {}", normalizedIdentifier);
            session.shutdown();
        }
    }

    public KoeClient getKoeClient(String userId) {
        return this.main.getKoe().newClient(Long.parseUnsignedLong(userId));
    }

    public Collection<PlayerSessionManager> getAll() {
        return sessionMap.values();
    }

    public AudioPlayerManager getAudioPlayerManager() {
        return main.getAudioPlayerManager();
    }

    public void dispatchEvent(WebsocketOpCodes op, JsonObject json, String userId) {
        List<WebsocketConnection> conn = main.getWebsocketManager().getConnections(userId);
        conn.forEach(c -> c.send(op, json));
    }

    public List<PlayerSession> getPlayersSnapshot() {
        List<PlayerSession> array = new LinkedList<>();
        List<List<PlayerSession>> players = this.sessionMap.values().stream()
                .map(PlayerSessionManager::getPlayersSnapshot)
                .toList();
        for (List<PlayerSession> p : players) {
            array.addAll(p);
        }
        return array;
    }

    public void cleanupIdleSessions() {
        for (PlayerSessionManager manager : sessionMap.values()) {
            manager.cleanupIdleSessions();
        }
    }

    public void shutdown() {
        for (PlayerSessionManager manager : sessionMap.values()) {
            manager.shutdown();
        }
        sessionMap.clear();
        preloadExecutor.shutdownNow();
        frameDispatchExecutor.shutdownNow();
        KoeClientManager.cleanup();
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(1);

        private NamedDaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
