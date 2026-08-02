package com.github.WearifulCupid0.lavanode.player.connections.http;

import com.github.WearifulCupid0.lavanode.Main;
import com.github.WearifulCupid0.lavanode.player.PlayerSession;
import com.github.WearifulCupid0.lavanode.player.connections.ConnectionState;
import com.github.WearifulCupid0.lavanode.player.connections.ConnectionType;
import com.github.WearifulCupid0.lavanode.player.connections.OpusFrameSubscription;
import com.github.WearifulCupid0.lavanode.player.connections.PcmFrameDispatcher;
import com.github.WearifulCupid0.lavanode.player.connections.PlayerConnection;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** One live HTTP listener represented as a first-class player connection. */
public final class HttpPlayerConnection implements PlayerConnection {
    private static final int MAX_FRAMES_PER_TICK = 5;

    private final String id = UUID.randomUUID().toString();
    private final Main main;
    private final StreamTokenManager tokenManager;
    private final StreamTokenManager.StreamToken token;
    private final PlayerSession player;
    private final OpusFrameSubscription subscription;
    private final HttpServerResponse response;
    private final String ip;
    private final OggOpusMuxer muxer = new OggOpusMuxer();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();
    private final long createdAt = System.currentTimeMillis();

    private volatile ConnectionState state = ConnectionState.CREATED;
    private volatile Long connectedAt;
    private volatile long timerId = -1L;
    private volatile boolean registered;

    HttpPlayerConnection(
            Main main,
            StreamTokenManager tokenManager,
            StreamTokenManager.StreamToken token,
            OpusFrameSubscription subscription,
            HttpServerResponse response,
            String ip
    ) {
        this.main = main;
        this.tokenManager = tokenManager;
        this.token = token;
        this.player = token.getPlayerSession();
        this.subscription = subscription;
        this.response = response;
        this.ip = ip;
    }

    boolean start() {
        state = ConnectionState.CONNECTING;

        if (!tokenManager.register(this)) {
            subscription.close();
            state = ConnectionState.CLOSED;
            return false;
        }

        synchronized (lifecycleLock) {
            if (closed.get()) {
                return false;
            }

            response
                    .setStatusCode(200)
                    .setChunked(true)
                    .putHeader(HttpHeaders.CONTENT_TYPE, "audio/ogg; codecs=opus")
                    .putHeader(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
                    .putHeader("Pragma", "no-cache")
                    .putHeader("Accept-Ranges", "none")
                    .putHeader("X-Content-Type-Options", "nosniff")
                    .putHeader("icy-name", "LavaNode Player " + player.getId())
                    .putHeader("icy-description", "Live Ogg/Opus stream from LavaNode")
                    .putHeader("icy-pub", "0")
                    .exceptionHandler(error -> {
                        player.notifyConnectionError(this, error);
                        closeInternal("connectionError", false);
                    })
                    .closeHandler(ignored -> closeInternal("clientDisconnected", false))
                    .endHandler(ignored -> closeInternal("responseEnded", false));

            response.write(Buffer.buffer(muxer.createIdentificationPage()))
                    .onFailure(error -> {
                        player.notifyConnectionError(this, error);
                        closeInternal("connectionError", false);
                    });
            response.write(Buffer.buffer(muxer.createCommentPage()))
                    .onFailure(error -> {
                        player.notifyConnectionError(this, error);
                        closeInternal("connectionError", false);
                    });

            state = ConnectionState.CONNECTED;
            connectedAt = System.currentTimeMillis();
            timerId = main.getVertx().setPeriodic(PcmFrameDispatcher.FRAME_DURATION_MS, ignored -> tick());
        }

        return !closed.get();
    }

    StreamTokenManager.StreamToken getToken() {
        return token;
    }

    void markRegistered() {
        registered = true;
    }

    public String getIp() {
        return ip;
    }

    public String getClientUserId() {
        return token.getClientUserId();
    }

    public long getConnectedDurationMs() {
        Long start = connectedAt;
        return start == null ? 0L : Math.max(0L, System.currentTimeMillis() - start);
    }

    private void tick() {
        if (closed.get()) {
            return;
        }

        if (subscription.isClosed()) {
            closeInternal("playerDestroyed", true);
            return;
        }

        subscription.keepAlive();

        if (response.writeQueueFull()) {
            return;
        }

        int sent = 0;
        byte[] packet;

        while (sent < MAX_FRAMES_PER_TICK
                && !response.writeQueueFull()
                && (packet = subscription.poll()) != null) {
            response.write(Buffer.buffer(muxer.writeAudioPacket(packet)))
                    .onFailure(error -> {
                        player.notifyConnectionError(this, error);
                        closeInternal("connectionError", false);
                    });
            sent++;
        }
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public ConnectionType getType() {
        return ConnectionType.HTTP;
    }

    @Override
    public ConnectionState getState() {
        return state;
    }

    @Override
    public long getCreatedAt() {
        return createdAt;
    }

    @Override
    public Long getConnectedAt() {
        return connectedAt;
    }

    @Override
    public JsonObject toJson() {
        return new JsonObject()
                .put("id", id)
                .put("type", getType().jsonName())
                .put("state", state.jsonName())
                .put("ip", ip)
                .put("userId", token.getClientUserId())
                .put("createdAt", createdAt)
                .put("connectedAt", connectedAt)
                .put("connectedDurationMs", getConnectedDurationMs())
                .put("expiresAt", token.getExpiresAt());
    }

    @Override
    public void disconnect(String reason) {
        closeInternal(reason == null ? "disconnected" : reason, true);
    }

    private void closeInternal(String reason, boolean endResponse) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        state = ConnectionState.CLOSING;
        boolean wasRegistered;

        synchronized (lifecycleLock) {
            long currentTimerId = timerId;
            timerId = -1L;

            if (currentTimerId != -1L) {
                main.getVertx().cancelTimer(currentTimerId);
            }

            subscription.close();
            wasRegistered = registered;
            registered = false;
        }

        state = ConnectionState.CLOSED;

        if (wasRegistered) {
            player.notifyConnectionDisconnect(this, reason);
            tokenManager.unregister(this, reason);
        }

        if (endResponse && !response.ended()) {
            response.end();
        }
    }
}
