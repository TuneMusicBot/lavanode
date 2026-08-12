package com.github.WearifulCupid0.lavanode.player.connections.discord;

import com.github.WearifulCupid0.lavanode.config.KoeClientManager;
import com.github.WearifulCupid0.lavanode.player.PlayerSession;
import com.github.WearifulCupid0.lavanode.player.PlayerSessionManager;
import com.github.WearifulCupid0.lavanode.player.connections.ConnectionState;
import com.github.WearifulCupid0.lavanode.player.connections.ConnectionType;
import com.github.WearifulCupid0.lavanode.player.connections.PlayerConnection;
import com.github.WearifulCupid0.lavanode.util.RequestUtil;
import io.vertx.core.json.JsonObject;
import moe.kyokobot.koe.KoeClient;
import moe.kyokobot.koe.MediaConnection;
import moe.kyokobot.koe.VoiceServerInfo;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DiscordPlayerConnection implements PlayerConnection {
    private final String id = UUID.randomUUID().toString();
    private final PlayerSession player;
    private final long userId;
    private final long guildId;
    private final long channelId;
    private final String endpoint;
    private final VoiceServerInfo serverInfo;
    private final long createdAt = System.currentTimeMillis();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean disconnectEventSent = new AtomicBoolean();

    private volatile ConnectionState state = ConnectionState.CREATED;
    private volatile Long connectedAt;
    private volatile MediaConnection mediaConnection;
    private volatile DiscordFrameDispatcher frameDispatcher;

    public DiscordPlayerConnection(
            PlayerSession player,
            long userId,
            long guildId,
            long channelId,
            String endpoint,
            VoiceServerInfo serverInfo
    ) {
        this.player = player;
        this.userId = userId;
        this.guildId = guildId;
        this.channelId = channelId;
        this.endpoint = endpoint;
        this.serverInfo = serverInfo;
    }

    public void connect() {
        if (closed.get()) {
            throw new IllegalStateException("Discord connection is closed");
        }

        state = ConnectionState.CONNECTING;

        MediaConnection connection = KoeClientManager.getClient(userId).createConnection(guildId);
        DiscordFrameDispatcher sender = new DiscordFrameDispatcher(player);

        this.mediaConnection = connection;
        this.frameDispatcher = sender;

        connection.registerListener(new KoeEventHandler(this));
        connection.setAudioSender(sender);
        connection.connect(serverInfo);
    }

    void gatewayReady(InetSocketAddress address, int ssrc) {
        if (closed.get()) {
            return;
        }

        state = ConnectionState.CONNECTED;
        connectedAt = System.currentTimeMillis();
    }

    void gatewayClosed(int code, String reason, boolean byRemote) {
        state = closed.get() ? ConnectionState.CLOSED : ConnectionState.DISCONNECTED;
        String detail = "discordGatewayClosed(code=" + code
                + ", byRemote=" + byRemote + ")"
                + (reason == null || reason.isBlank() ? "" : ": " + reason);
        emitDisconnect(detail);
    }

    void gatewayError(Throwable error) {
        state = ConnectionState.ERROR;
        player.notifyConnectionError(this, error);
    }

    private void emitDisconnect(String reason) {
        if (!disconnectEventSent.compareAndSet(false, true)) {
            return;
        }

        player.notifyConnectionDisconnect(this, reason);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public ConnectionType getType() {
        return ConnectionType.DISCORD;
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

    public String getGuildId() {
        return Long.toUnsignedString(guildId);
    }

    public String getChannelId() {
        return Long.toUnsignedString(channelId);
    }

    @Override
    public JsonObject toJson() {
        JsonObject json = new JsonObject()
                .put("id", id)
                .put("type", getType().jsonName())
                .put("state", state.jsonName())
                .put("guildId", getGuildId())
                .put("channelId", getChannelId())
                .put("endpoint", endpoint)
                .put("createdAt", createdAt)
                .put("connectedAt", connectedAt);

        MediaConnection connection = mediaConnection;
        if (connection != null) {
            json.put("media", RequestUtil.mediaToJson(connection));
        }

        return json;
    }

    @Override
    public void disconnect(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        state = ConnectionState.CLOSING;

        DiscordFrameDispatcher sender = frameDispatcher;
        frameDispatcher = null;
        if (sender != null) {
            sender.dispose();
        }

        try {
            KoeClientManager.getClient(userId).destroyConnection(guildId);
        } catch (Throwable error) {
            player.notifyConnectionError(this, error);
        }

        state = ConnectionState.CLOSED;
        emitDisconnect(reason == null ? "disconnected" : reason);
    }
}
