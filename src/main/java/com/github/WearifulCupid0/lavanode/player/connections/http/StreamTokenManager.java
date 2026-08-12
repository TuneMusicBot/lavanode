package com.github.WearifulCupid0.lavanode.player.connections.http;

import com.github.WearifulCupid0.lavanode.Main;
import com.github.WearifulCupid0.lavanode.player.PlayerSession;
import com.github.WearifulCupid0.lavanode.server.websocket.WebsocketOpCodes;
import io.vertx.core.json.JsonObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Issues short-lived or non-expiring bearer tokens for HTTP player streams.
 *
 * Tokens are intentionally stateful so destroying a PlayerSession can revoke
 * every token immediately and close clients that are already connected.
 */
public final class StreamTokenManager {
    private static final int TOKEN_BYTES = 32;

    private final Main main;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, StreamToken> tokens = new HashMap<>();
    private final Map<PlayerSession, Set<StreamToken>> tokensBySession = new HashMap<>();

    public StreamTokenManager(Main main) {
        this.main = main;
    }

    public StreamToken issue(PlayerSession playerSession, String clientUserId, Long expiresInMs) {
        if (playerSession == null || playerSession.isDestroyed()) {
            throw new IllegalStateException("Player session is no longer available");
        }

        if (clientUserId == null || clientUserId.isBlank()) {
            throw new IllegalArgumentException("Stream user id is required");
        }

        long issuedAt = System.currentTimeMillis();
        Long expiresAt = null;

        if (expiresInMs != null) {
            if (expiresInMs <= 0L) {
                throw new IllegalArgumentException("expiresInMs must be greater than 0");
            }

            try {
                expiresAt = Math.addExact(issuedAt, expiresInMs);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("expiresInMs is too large", exception);
            }
        }

        byte[] bytes = new byte[TOKEN_BYTES];
        String value;

        synchronized (this) {
            if (playerSession.isDestroyed()) {
                throw new IllegalStateException("Player session is no longer available");
            }

            do {
                random.nextBytes(bytes);
                value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            } while (tokens.containsKey(value));

            StreamToken token = new StreamToken(
                    value,
                    playerSession,
                    clientUserId,
                    issuedAt,
                    expiresAt
            );

            tokens.put(value, token);
            tokensBySession.computeIfAbsent(playerSession, ignored -> new HashSet<>()).add(token);

            if (expiresAt != null) {
                long delay = Math.max(1L, expiresAt - issuedAt);
                token.expirationTimerId = main.getVertx().setTimer(delay, ignored -> expireToken(token));
            }

            return token;
        }
    }

    public StreamToken resolve(String value, String playerId) {
        if (value == null || value.isBlank() || playerId == null || playerId.isBlank()) {
            return null;
        }

        StreamToken expiredToken = null;

        synchronized (this) {
            StreamToken token = tokens.get(value);

            if (token == null || token.revoked) {
                return null;
            }

            if (!token.playerSession.getId().equals(playerId)) {
                return null;
            }

            if (token.playerSession.isDestroyed()) {
                expiredToken = token;
            } else if (token.expiresAt != null && System.currentTimeMillis() >= token.expiresAt) {
                expiredToken = token;
            } else {
                return token;
            }
        }

        expireToken(expiredToken);
        return null;
    }

    public boolean register(HttpPlayerConnection connection) {
        StreamToken token = connection.getToken();
        boolean expire = false;

        synchronized (this) {
            if (token.revoked || tokens.get(token.value) != token) {
                return false;
            }

            if (token.playerSession.isDestroyed()
                    || (token.expiresAt != null && System.currentTimeMillis() >= token.expiresAt)) {
                expire = true;
            } else {
                if (!token.connections.add(connection)) {
                    return false;
                }

                try {
                    token.playerSession.registerConnection(connection);
                    connection.markRegistered();
                    dispatchConnect(connection);
                } catch (RuntimeException exception) {
                    token.connections.remove(connection);
                    throw exception;
                }
            }
        }

        if (expire) {
            expireToken(token);
            return false;
        }

        return true;
    }

    public void unregister(HttpPlayerConnection connection, String reason) {
        boolean removed;

        synchronized (this) {
            StreamToken token = connection.getToken();
            removed = token.connections.remove(connection);

        }

        if (removed) {
            dispatchDisconnect(connection, reason);
            connection.getToken().playerSession.unregisterConnection(connection, reason);
        }
    }

    public void revokeSession(PlayerSession playerSession, String reason) {
        List<HttpPlayerConnection> connections = new ArrayList<>();

        synchronized (this) {
            Set<StreamToken> sessionTokens = tokensBySession.remove(playerSession);

            if (sessionTokens == null || sessionTokens.isEmpty()) {
                return;
            }

            for (StreamToken token : sessionTokens) {
                revokeTokenLocked(token, connections);
            }
        }

        for (HttpPlayerConnection connection : connections) {
            connection.disconnect(reason);
        }
    }

    private void expireToken(StreamToken token) {
        if (token == null) {
            return;
        }

        List<HttpPlayerConnection> connections = new ArrayList<>();

        synchronized (this) {
            if (token.revoked) {
                return;
            }

            revokeTokenLocked(token, connections);

            Set<StreamToken> sessionTokens = tokensBySession.get(token.playerSession);
            if (sessionTokens != null) {
                sessionTokens.remove(token);

                if (sessionTokens.isEmpty()) {
                    tokensBySession.remove(token.playerSession);
                }
            }
        }

        for (HttpPlayerConnection connection : connections) {
            connection.disconnect("tokenExpired");
        }
    }

    private void revokeTokenLocked(StreamToken token, List<HttpPlayerConnection> connections) {
        token.revoked = true;
        tokens.remove(token.value, token);

        if (token.expirationTimerId != null) {
            main.getVertx().cancelTimer(token.expirationTimerId);
            token.expirationTimerId = null;
        }

        connections.addAll(token.connections);
    }

    private void dispatchConnect(HttpPlayerConnection connection) {
        StreamToken token = connection.getToken();

        JsonObject payload = new JsonObject()
                .put("playerId", token.playerSession.getId())
                .put("connectionId", connection.getId())
                .put("connectionType", connection.getType().jsonName())
                .put("ip", connection.getIp())
                .put("userId", token.clientUserId);

        main.getPlayerManager().dispatchEvent(
                WebsocketOpCodes.connectionConnect,
                payload,
                token.playerSession.getUserId()
        );
    }

    private void dispatchDisconnect(HttpPlayerConnection connection, String reason) {
        StreamToken token = connection.getToken();

        JsonObject payload = new JsonObject()
                .put("playerId", token.playerSession.getId())
                .put("connectionId", connection.getId())
                .put("connectionType", connection.getType().jsonName())
                .put("ip", connection.getIp())
                .put("userId", token.clientUserId)
                .put("connectedDurationMs", connection.getConnectedDurationMs())
                .put("reason", reason);

        main.getPlayerManager().dispatchEvent(
                WebsocketOpCodes.connectionDisconnect,
                payload,
                token.playerSession.getUserId()
        );
    }

    public static final class StreamToken {
        private final String value;
        private final PlayerSession playerSession;
        private final String clientUserId;
        private final long issuedAt;
        private final Long expiresAt;
        private final Set<HttpPlayerConnection> connections = new HashSet<>();

        private boolean revoked;
        private Long expirationTimerId;

        private StreamToken(
                String value,
                PlayerSession playerSession,
                String clientUserId,
                long issuedAt,
                Long expiresAt
        ) {
            this.value = value;
            this.playerSession = playerSession;
            this.clientUserId = clientUserId;
            this.issuedAt = issuedAt;
            this.expiresAt = expiresAt;
        }

        public String getValue() {
            return value;
        }

        public PlayerSession getPlayerSession() {
            return playerSession;
        }

        public String getClientUserId() {
            return clientUserId;
        }

        public long getIssuedAt() {
            return issuedAt;
        }

        public Long getExpiresAt() {
            return expiresAt;
        }

        public JsonObject toJson() {
            return new JsonObject()
                    .put("token", value)
                    .put("playerId", playerSession.getId())
                    .put("userId", clientUserId)
                    .put("issuedAt", issuedAt)
                    .put("expiresAt", expiresAt);
        }
    }
}
