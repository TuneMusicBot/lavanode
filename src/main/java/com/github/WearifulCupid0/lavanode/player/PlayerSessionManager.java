package com.github.WearifulCupid0.lavanode.player;

import com.github.WearifulCupid0.lavanode.player.voice.KoeEventHandler;
import com.github.WearifulCupid0.lavanode.player.voice.PlayerProvider;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import io.vertx.core.json.JsonArray;
import moe.kyokobot.koe.KoeClient;
import moe.kyokobot.koe.MediaConnection;
import moe.kyokobot.koe.VoiceServerInfo;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class PlayerSessionManager {
    private final AudioPlayerManager audioPlayerManager;
    private final AudioPlayerManager pcmAudioPlayerManager;
    private final PlayerEventListener listener;
    private final Map<String, PlayerSession> players = new ConcurrentHashMap<>();
    private final KoeClient koe;

    private final ExecutorService preloadExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            new NamedDaemonThreadFactory("lavanode-preload")
    );

    public PlayerSessionManager(
            AudioPlayerManager audioPlayerManager,
            AudioPlayerManager pcmAudioPlayerManager,
            PlayerEventListener listener,
            KoeClient koe
    ) {
        this.audioPlayerManager = audioPlayerManager;
        this.pcmAudioPlayerManager = pcmAudioPlayerManager;
        this.listener = listener;
        this.koe = koe;
    }

    public List<PlayerSession> getPlayersSnapshot() {
        return List.copyOf(this.players.values());
    }

    public AudioPlayerManager getAudioPlayerManager() {
        return audioPlayerManager;
    }

    public AudioPlayerManager getPcmAudioPlayerManager() {
        return pcmAudioPlayerManager;
    }

    public PlayerSession getOrCreate(String guildId) {
        String normalizedGuildId = Long.toString(parseDiscordId(guildId, "guildId"));

        return players.computeIfAbsent(normalizedGuildId, id ->
                new PlayerSession(id, getUserId(), this, listener, preloadExecutor)
        );
    }

    public String getUserId() {
        return Long.toString(this.koe.getClientId());
    }

    public PlayerSession get(String guildId) {
        return players.get(Long.toString(parseDiscordId(guildId, "guildId")));
    }

    public boolean exists(String guildId) {
        return players.containsKey(Long.toString(parseDiscordId(guildId, "guildId")));
    }

    public void destroy(long guildId) {
        destroy(Long.toString(guildId));
    }

    public void destroy(String guildId) {
        long parsedGuildId = parseDiscordId(guildId, "guildId");
        String normalizedGuildId = Long.toString(parsedGuildId);

        PlayerSession session = players.remove(normalizedGuildId);

        if (session != null) {
            session.destroy();
        }

        koe.destroyConnection(parsedGuildId);
    }

    public void shutdown() {
        for (PlayerSession session : this.getAll()) {
            destroy(session.getId());
        }

        players.clear();
        preloadExecutor.shutdownNow();
        this.koe.close();
    }

    public Collection<PlayerSession> getAll() {
        return players.values();
    }

    public int size() {
        return players.size();
    }

    public MediaConnection getConnection(PlayerSession playerSession) {
        long guildId = parseDiscordId(playerSession.getId(), "guildId");
        MediaConnection mediaConnection = koe.getConnection(guildId);

        if (mediaConnection == null) {
            mediaConnection = koe.createConnection(guildId);
            mediaConnection.registerListener(new KoeEventHandler(playerSession));
            mediaConnection.setAudioSender(new PlayerProvider(playerSession));
        }

        return mediaConnection;
    }

    public void connectVoiceChannel(String guildId, VoiceServerInfo serverInfo) {
        PlayerSession playerSession = getOrCreate(guildId);
        MediaConnection mediaConnection = getConnection(playerSession);

        mediaConnection.connect(serverInfo);
    }

    public JsonArray toJson() {
        JsonArray json = new JsonArray();
        for (PlayerSession playerSession : getAll()) {
            json.add(playerSession.toJson(audioPlayerManager));
        }
        return json;
    }

    private static long parseDiscordId(String value, String fieldName) {
        if (value == null || !value.matches("\\d{1,20}")) {
            throw new IllegalArgumentException("Invalid Discord " + fieldName + ": " + value);
        }

        try {
            return Long.parseLong(value);
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
