package com.github.WearifulCupid0.lavanode.player;

import com.github.WearifulCupid0.lavanode.Main;
import com.github.WearifulCupid0.lavanode.server.websocket.WebsocketConnection;
import com.github.WearifulCupid0.lavanode.server.websocket.WebsocketOpCodes;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerManager {
    private static final Logger log = LoggerFactory.getLogger(PlayerManager.class);

    private final Map<String, PlayerSessionManager> sessionMap = new ConcurrentHashMap<>();
    private final Main main;

    public PlayerManager(Main main) {
        this.main = main;
    }

    public PlayerSessionManager getOrCreate(String userId) {
        long parsedUserId = parseDiscordId(userId, "userId");
        String normalizedUserId = Long.toString(parsedUserId);

        return this.sessionMap.computeIfAbsent(normalizedUserId, id ->
                new PlayerSessionManager(
                        this.main.getAudioPlayerManager(),
                        this.main.getPcmAudioPlayerManager(),
                        new PlayerSessionEventListener(this),
                        this.main.getKoe().newClient(parsedUserId)
                )
        );
    }

    public void destroy(String userId) {
        long parsedUserId = parseDiscordId(userId, "userId");
        String normalizedUserId = Long.toString(parsedUserId);

        PlayerSessionManager session = this.sessionMap.remove(normalizedUserId);

        if (session != null) {
            log.debug("Destroying all players from user id: {}", normalizedUserId);
            session.shutdown();
        }
    }

    public Collection<PlayerSessionManager> getAll() {
        return sessionMap.values();
    }

    public AudioPlayerManager getAudioPlayerManager() {
        return main.getAudioPlayerManager();
    }

    public void dispatchEvent(WebsocketOpCodes op, JsonObject json, String userId) {
        List<WebsocketConnection> conn = main.getWebsocketManager().getConnections(userId);

        conn.forEach(c -> c.send(op ,json));
    }

    public List<PlayerSession> getPlayersSnapshot() {
        List<PlayerSession> array = new LinkedList<>();

        List<List<PlayerSession>> players = this.sessionMap.values().stream().map(PlayerSessionManager::getPlayersSnapshot).toList();
        for (List<PlayerSession> p : players)
            array.addAll(p);

        return array;
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
}
