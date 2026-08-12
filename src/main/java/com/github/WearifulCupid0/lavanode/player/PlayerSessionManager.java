package com.github.WearifulCupid0.lavanode.player;

import com.github.WearifulCupid0.lavanode.player.connections.PlayerConnection;
import com.github.WearifulCupid0.lavanode.player.connections.discord.DiscordPlayerConnection;
import com.github.WearifulCupid0.lavanode.player.connections.http.StreamTokenManager;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import io.vertx.core.json.JsonArray;
import moe.kyokobot.koe.KoeClient;
import moe.kyokobot.koe.VoiceServerInfo;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class PlayerSessionManager {
    private final AudioPlayerManager audioPlayerManager;
    private final PlayerEventListener listener;
    private final Map<String, PlayerSession> players = new ConcurrentHashMap<>();
    private final Map<String, PlayerSession> playersByConnectionId = new ConcurrentHashMap<>();
    private final Map<String, String> connectionByGuildId = new ConcurrentHashMap<>();
    private final StreamTokenManager streamTokenManager;
    private final String identifier;

    private final ExecutorService preloadExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            new NamedDaemonThreadFactory("lavanode-preload")
    );

    private final ScheduledExecutorService frameDispatchExecutor = Executors.newScheduledThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            new NamedDaemonThreadFactory("lavanode-frame-dispatch")
    );

    public PlayerSessionManager(
            AudioPlayerManager audioPlayerManager,
            PlayerEventListener listener,
            StreamTokenManager streamTokenManager,
            String identifier
    ) {
        this.audioPlayerManager = audioPlayerManager;
        this.listener = listener;
        this.streamTokenManager = streamTokenManager;
        this.identifier = identifier;
    }

    public PlayerSession create(PlayerSettings settings) {
        String id;
        do {
            id = UUID.randomUUID().toString();
        } while (players.containsKey(id));

        PlayerSession session = new PlayerSession(
                id,
                getUserId(),
                this,
                listener,
                preloadExecutor,
                frameDispatchExecutor,
                streamTokenManager,
                settings
        );

        players.put(id, session);
        return session;
    }

    public List<PlayerSession> getPlayersSnapshot() {
        return List.copyOf(players.values());
    }

    public AudioPlayerManager getAudioPlayerManager() {
        return audioPlayerManager;
    }

    public String getUserId() {
        return this.identifier;
    }

    public PlayerSession get(String playerId) {
        return playerId == null ? null : players.get(playerId);
    }

    public boolean exists(String playerId) {
        return playerId != null && players.containsKey(playerId);
    }

    public PlayerSession findByConnectionId(String connectionId) {
        return connectionId == null ? null : playersByConnectionId.get(connectionId);
    }

    public PlayerSession findByGuildId(String guildId) {
        String normalizedGuildId = normalizeDiscordId(guildId, "guildId");
        String connectionId = connectionByGuildId.get(normalizedGuildId);
        return connectionId == null ? null : playersByConnectionId.get(connectionId);
    }

    public DiscordPlayerConnection createDiscordConnection(
            PlayerSession player,
            String guildId,
            String channelId,
            String userId,
            String endpoint,
            VoiceServerInfo serverInfo
    ) {
        if (player == null || player.isDestroyed()) {
            throw new IllegalStateException("Player is no longer available");
        }

        long parsedGuildId = parseDiscordId(guildId, "guildId");
        long parsedChannelId = parseDiscordId(channelId, "channelId");
        long parsedUserId = parseDiscordId(userId, "userId");

        String normalizedUserId = Long.toUnsignedString(parsedUserId);
        String normalizedGuildId = Long.toUnsignedString(parsedGuildId);

        if (connectionByGuildId.containsKey(normalizedGuildId + normalizedUserId)) {
            throw new IllegalStateException("A Discord connection already exists for guild " + normalizedGuildId + " with user " + normalizedUserId);
        }

        DiscordPlayerConnection connection = new DiscordPlayerConnection(
                player,
                parsedUserId,
                parsedGuildId,
                parsedChannelId,
                endpoint,
                serverInfo
        );

        player.registerConnection(connection);

        try {
            connection.connect();
        } catch (RuntimeException exception) {
            player.notifyConnectionError(connection, exception);
            connection.disconnect("connectFailed");
            player.unregisterConnection(connection, "connectFailed");
            throw exception;
        }

        return connection;
    }

    public void indexConnection(PlayerSession player, PlayerConnection connection) {
        PlayerSession previous = playersByConnectionId.putIfAbsent(connection.getId(), player);
        if (previous != null) {
            throw new IllegalStateException("Connection id already belongs to a player: " + connection.getId());
        }

        if (connection instanceof DiscordPlayerConnection discord) {
            String existing = connectionByGuildId.putIfAbsent(discord.getGuildId(), connection.getId());
            if (existing != null) {
                playersByConnectionId.remove(connection.getId(), player);
                throw new IllegalStateException("A Discord connection already exists for guild " + discord.getGuildId());
            }
        }
    }

    public void unindexConnection(PlayerSession player, PlayerConnection connection) {
        playersByConnectionId.remove(connection.getId(), player);

        if (connection instanceof DiscordPlayerConnection discord) {
            connectionByGuildId.remove(discord.getGuildId(), connection.getId());
        }
    }

    public void destroy(String playerId) {
        PlayerSession session = players.remove(playerId);

        if (session != null) {
            session.destroy();
            listener.onPlayerDestroy(session);
        }
    }

    public void shutdown() {
        for (PlayerSession session : getPlayersSnapshot()) {
            destroy(session.getId());
        }

        players.clear();
        playersByConnectionId.clear();
        connectionByGuildId.clear();
        preloadExecutor.shutdownNow();
        frameDispatchExecutor.shutdownNow();
    }

    public Collection<PlayerSession> getAll() {
        return players.values();
    }

    public int size() {
        return players.size();
    }

    public JsonArray toJson(String guildId, String connectionId) {
        JsonArray json = new JsonArray();

        if (connectionId != null && !connectionId.isBlank()) {
            PlayerSession player = findByConnectionId(connectionId);
            if (player != null) {
                json.add(player.toJson(audioPlayerManager));
            }
            return json;
        }

        if (guildId != null && !guildId.isBlank()) {
            PlayerSession player = findByGuildId(guildId);
            if (player != null) {
                json.add(player.toJson(audioPlayerManager));
            }
            return json;
        }

        for (PlayerSession player : getAll()) {
            json.add(player.toJson(audioPlayerManager));
        }

        return json;
    }

    public JsonArray toJson() {
        return toJson(null, null);
    }

    private static String normalizeDiscordId(String value, String fieldName) {
        return Long.toUnsignedString(parseDiscordId(value, fieldName));
    }

    private static long parseDiscordId(String value, String fieldName) {
        if (value == null || !value.matches("\\d{1,20}")) {
            throw new IllegalArgumentException("Invalid Discord " + fieldName + ": " + value);
        }

        try {
            return Long.parseUnsignedLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid Discord " + fieldName + ": " + value, exception);
        }
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
