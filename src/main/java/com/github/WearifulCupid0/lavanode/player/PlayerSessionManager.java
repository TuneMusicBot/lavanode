package com.github.WearifulCupid0.lavanode.player;
import com.github.WearifulCupid0.lavanode.player.connections.PlayerConnection;
import com.github.WearifulCupid0.lavanode.player.connections.discord.DiscordPlayerConnection;
import com.github.WearifulCupid0.lavanode.player.connections.http.StreamTokenManager;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import io.vertx.core.json.JsonArray;
import moe.kyokobot.koe.KoeClient;
import moe.kyokobot.koe.VoiceServerInfo;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
public final class PlayerSessionManager {
    private final AudioPlayerManager audioPlayerManager;
    private final PlayerEventListener listener;
    private final Map<String, PlayerSession> players = new ConcurrentHashMap<>();
    private final Map<String, PlayerSession> playersByConnectionId = new ConcurrentHashMap<>();
    private final Map<DiscordConnectionKey, String> discordConnectionByGuildAndUser = new ConcurrentHashMap<>();
    private final StreamTokenManager streamTokenManager;
    private final String identifier;
    private final ExecutorService preloadExecutor;
    private final ScheduledExecutorService frameDispatchExecutor;
    public PlayerSessionManager(
            AudioPlayerManager audioPlayerManager,
            PlayerEventListener listener,
            StreamTokenManager streamTokenManager,
            String identifier,
            ExecutorService preloadExecutor,
            ScheduledExecutorService frameDispatchExecutor
    ) {
        this.audioPlayerManager = audioPlayerManager;
        this.listener = listener;
        this.streamTokenManager = streamTokenManager;
        this.identifier = identifier;
        this.preloadExecutor = preloadExecutor;
        this.frameDispatchExecutor = frameDispatchExecutor;
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
    public List<PlayerSession> findByGuildId(String guildId) {
        String normalizedGuildId = normalizeDiscordId(guildId, "guildId");
        Set<PlayerSession> matches = new LinkedHashSet<>();

        for (Map.Entry<DiscordConnectionKey, String> entry : discordConnectionByGuildAndUser.entrySet()) {
            if (!entry.getKey().guildId().equals(normalizedGuildId)) {
                continue;
            }

            PlayerSession player = playersByConnectionId.get(entry.getValue());
            if (player != null) {
                matches.add(player);
            }
        }

        return List.copyOf(matches);
    }

    public PlayerSession findByGuildAndUserId(String guildId, String userId) {
        DiscordConnectionKey key = discordConnectionKey(guildId, userId);
        String connectionId = discordConnectionByGuildAndUser.get(key);
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
        DiscordConnectionKey discordKey = new DiscordConnectionKey(normalizedGuildId, normalizedUserId);
        if (discordConnectionByGuildAndUser.containsKey(discordKey)) {
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
            DiscordConnectionKey key = discordConnectionKey(discord.getGuildId(), discord.getUserId());
            String existing = discordConnectionByGuildAndUser.putIfAbsent(key, connection.getId());
            if (existing != null) {
                playersByConnectionId.remove(connection.getId(), player);
                throw new IllegalStateException(
                        "A Discord connection already exists for guild " + discord.getGuildId()
                                + " with user " + discord.getUserId()
                );
            }
        }
    }
    public void unindexConnection(PlayerSession player, PlayerConnection connection) {
        playersByConnectionId.remove(connection.getId(), player);

        if (connection instanceof DiscordPlayerConnection discord) {
            DiscordConnectionKey key = discordConnectionKey(discord.getGuildId(), discord.getUserId());
            discordConnectionByGuildAndUser.remove(key, connection.getId());
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
        discordConnectionByGuildAndUser.clear();
    }
    public Collection<PlayerSession> getAll() {
        return players.values();
    }

    public int size() {
        return players.size();
    }

    public void cleanupIdleSessions() {
        for (PlayerSession session : getPlayersSnapshot()) {
            if (session.shouldBeDeleted()) {
                destroy(session.getId());
            }
        }
    }

    public JsonArray toJson(String guildId, String userId, String connectionId) {
        JsonArray json = new JsonArray();
        if (connectionId != null && !connectionId.isBlank()) {
            PlayerSession player = findByConnectionId(connectionId);
            if (player != null) {
                json.add(player.toJson(audioPlayerManager));
            }
            return json;
        }
        if (guildId != null && !guildId.isBlank()) {
            if (userId != null && !userId.isBlank()) {
                PlayerSession player = findByGuildAndUserId(guildId, userId);
                if (player != null) {
                    json.add(player.toJson(audioPlayerManager));
                }
                return json;
            }

            for (PlayerSession player : findByGuildId(guildId)) {
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
        return toJson(null, null, null);
    }

    private static DiscordConnectionKey discordConnectionKey(String guildId, String userId) {
        return new DiscordConnectionKey(
                normalizeDiscordId(guildId, "guildId"),
                normalizeDiscordId(userId, "userId")
        );
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

    private record DiscordConnectionKey(String guildId, String userId) {}
}
