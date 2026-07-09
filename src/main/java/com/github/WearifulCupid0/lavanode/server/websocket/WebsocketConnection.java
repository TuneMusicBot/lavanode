package com.github.WearifulCupid0.lavanode.server.websocket;

import com.github.WearifulCupid0.lavanode.Main;
import com.github.WearifulCupid0.lavanode.util.RequestUtil;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.DecodeException;
import java.nio.charset.StandardCharsets;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

public class WebsocketConnection {
    private static final Logger log = LoggerFactory.getLogger(WebsocketConnection.class);

    public static final long HEARTBEAT_INTERVAL_MS = 15_000;
    private static final long HEARTBEAT_TIMEOUT_MS = 45_000;
    private static final long HEARTBEAT_CHECK_INTERVAL_MS = 5_000;
    private static final int MAX_PENDING_EVENTS = 500;
    private static final long RESUME_TIMEOUT_MS = 180_000;

    private final Main main;
    private final String userId;
    private final String connectionId;
    private final Deque<JsonObject> pendingEvents = new ArrayDeque<>();

    private ServerWebSocket ws;
    private Context context;

    private Long statsTimer;
    private Long deleteTimer;
    private Long heartbeatTimer;

    private long sequence = 0;
    private long lastHeartbeatAt = System.currentTimeMillis();
    private long ping = -1;

    private boolean resuming = false;
    private boolean alive = false;
    private boolean destroyed = false;

    public WebsocketConnection(String userId, String connectionId, ServerWebSocket ws, Main main) {
        this.userId = userId;
        this.connectionId = connectionId;
        this.ws = ws;
        this.main = main;
        this.context = Vertx.currentContext();
    }

    public String getUserId() {
        return userId;
    }

    public long getPing() {
        return ping;
    }

    public void start() {
        runOnContext(() -> startOnContext(false));
    }

    private void startOnContext(boolean fromResume) {
        if (destroyed) {
            closeSocketQuietly(ws, (short) 1001, "Connection destroyed");
            return;
        }

        resuming = false;
        alive = true;
        lastHeartbeatAt = System.currentTimeMillis();

        cancelDeleteTimer();
        startStatsTimer();
        startHeartbeatTimeoutCheck();
        setupSocketHandlers();

        if (!fromResume) {
            send(WebsocketOpCodes.ready, readyPayload(false));
        }
    }

    public void resumeConnection(ServerWebSocket ws) {
        Context newContext = Vertx.currentContext();

        if (newContext != null) {
            this.context = newContext;
        }

        runOnContext(() -> resumeConnectionOnContext(ws));
    }

    private void resumeConnectionOnContext(ServerWebSocket ws) {
        if (destroyed) {
            closeSocketQuietly(ws, (short) 1001, "Connection destroyed");
            return;
        }

        this.ws = ws;
        this.context = Vertx.currentContext() != null ? Vertx.currentContext() : this.context;

        cancelDeleteTimer();
        stopHeartbeatTimeoutCheck();
        cancelStatsTimer();

        alive = true;
        resuming = true;
        lastHeartbeatAt = System.currentTimeMillis();

        setupSocketHandlers();

        sendPacket(new JsonObject()
                .put("op", WebsocketOpCodes.resuming.toString())
                .put("d", new JsonObject()
                        .put("sessionId", this.connectionId)
                        .put("heartbeatInterval", HEARTBEAT_INTERVAL_MS)
                )
        );

        int packets = this.pendingEvents.size();
        log.debug("Sending {} pending packets to connection {} of user {}", packets, shortConnectionId(), userId);

        while (!pendingEvents.isEmpty()) {
            sendPacket(pendingEvents.pollFirst());
        }

        resuming = false;
        send(WebsocketOpCodes.resumed, readyPayload(true));

        startOnContext(true);
    }

    public void send(WebsocketOpCodes op, JsonObject data) {
        JsonObject copiedData = data == null ? new JsonObject() : data.copy();

        runOnContext(() -> sendOnContext(op, copiedData));
    }

    private void sendOnContext(WebsocketOpCodes op, JsonObject data) {
        if (destroyed) {
            return;
        }

        JsonObject formatted = new JsonObject()
                .put("op", op.toString())
                .put("d", data == null ? new JsonObject() : data)
                .put("t", System.currentTimeMillis())
                .put("seq", ++sequence);

        if (!alive || resuming || ws == null || ws.isClosed()) {
            if (shouldQueueWhenDisconnected(op)) {
                addPendingEvent(formatted);
            }
            return;
        }

        sendPacket(formatted);
    }

    public void destroy() {
        runOnContext(this::destroyOnContext);
    }

    private void destroyOnContext() {
        destroyed = true;
        alive = false;
        resuming = false;

        cancelStatsTimer();
        cancelDeleteTimer();
        stopHeartbeatTimeoutCheck();

        pendingEvents.clear();

        ServerWebSocket currentWs = ws;
        ws = null;

        if (currentWs != null && !currentWs.isClosed()) {
            closeSocketQuietly(currentWs, (short) 1001, "Connection destroyed");
        }

        log.info("Connection {} from user {} is being deleted and no longer is resumable.", shortConnectionId(), userId);
    }

    private void setupSocketHandlers() {
        if (ws == null) {
            return;
        }

        ws.textMessageHandler(this::handleTextMessage);
        ws.binaryMessageHandler(this::handleBinaryMessage);
        ws.closeHandler(__ -> runOnContext(this::handleCloseOnContext));
        ws.exceptionHandler(error -> log.debug("WebSocket error on connection {} from user {}", shortConnectionId(), userId, error));
    }

    private void handleTextMessage(String message) {
        JsonObject json;

        try {
            json = new JsonObject(message);
        } catch (DecodeException exception) {
            closePolicyViolation("Invalid JSON");
            return;
        }

        handleClientPacket(json);
    }

    private void handleBinaryMessage(Buffer buffer) {
        JsonObject json;

        try {
            json = new JsonObject(buffer.toString(StandardCharsets.UTF_8));
        } catch (DecodeException exception) {
            closePolicyViolation("Invalid JSON");
            return;
        }

        handleClientPacket(json);
    }

    private void handleClientPacket(JsonObject json) {
        String op = json.getString("op");

        if (!WebsocketOpCodes.heartbeat.toString().equals(op)) {
            closePolicyViolation("Only heartbeat packets are accepted by this websocket");
            return;
        }

        handleHeartbeat(json.getJsonObject("d", new JsonObject()));
    }

    private void handleHeartbeat(JsonObject data) {
        long now = System.currentTimeMillis();
        lastHeartbeatAt = now;

        Object timestampValue = data.getValue("timestamp");
        Object nonce = data.getValue("nonce");

        if (timestampValue instanceof Number) {
            long timestamp = ((Number) timestampValue).longValue();
            if (timestamp > 0) {
                ping = Math.max(0, now - timestamp);
            }
        }

        JsonObject ack = new JsonObject()
                .put("nonce", nonce)
                .put("ping", ping)
                .put("heartbeatInterval", HEARTBEAT_INTERVAL_MS);

        send(WebsocketOpCodes.heartbeatAck, ack);
    }

    private void startStatsTimer() {
        cancelStatsTimer();

        statsTimer = main
                .getVertx()
                .setPeriodic(30_000, __ -> {
                    JsonObject stats = RequestUtil.nodeStats(main, userId)
                            .put("websocket", new JsonObject()
                                    .put("ping", ping)
                                    .put("heartbeatInterval", HEARTBEAT_INTERVAL_MS)
                                    .put("lastHeartbeatAt", lastHeartbeatAt)
                            );

                    send(WebsocketOpCodes.stats, stats);
                });
    }

    private void startHeartbeatTimeoutCheck() {
        stopHeartbeatTimeoutCheck();

        heartbeatTimer = main
                .getVertx()
                .setPeriodic(HEARTBEAT_CHECK_INTERVAL_MS, __ -> {
                    if (!alive || destroyed || ws == null || ws.isClosed()) {
                        return;
                    }

                    long elapsed = System.currentTimeMillis() - lastHeartbeatAt;
                    if (elapsed > HEARTBEAT_TIMEOUT_MS) {
                        closeAsZombie(elapsed);
                    }
                });
    }

    private void closeAsZombie(long elapsed) {
        log.info(
                "Closing websocket connection {} from user {} after {}ms without heartbeat",
                shortConnectionId(),
                userId,
                elapsed
        );

        alive = false;
        resuming = true;
        stopHeartbeatTimeoutCheck();
        cancelStatsTimer();

        closeSocketQuietly(ws, (short) 1001, "Heartbeat timeout");
        scheduleDeleteTimer();
    }

    private void handleCloseOnContext() {
        if (destroyed) {
            return;
        }

        alive = false;
        resuming = true;

        cancelStatsTimer();
        stopHeartbeatTimeoutCheck();
        scheduleDeleteTimer();

        log.info(
                "Connection {} from user {} closed and is resumable for {}ms",
                shortConnectionId(),
                userId,
                RESUME_TIMEOUT_MS
        );
    }

    private void sendPacket(JsonObject json) {
        ServerWebSocket currentWs = ws;

        if (currentWs == null || currentWs.isClosed()) {
            addPendingEvent(json);
            return;
        }

        try {
            currentWs.writeTextMessage(json.encode()).onFailure(error -> {
                log.debug("Failed to send websocket packet on connection {} from user {}", shortConnectionId(), userId, error);
                addPendingEvent(json);
            });
        } catch (Throwable throwable) {
            log.debug("Failed to write websocket packet on connection {} from user {}", shortConnectionId(), userId, throwable);
            addPendingEvent(json);
        }
    }

    private void addPendingEvent(JsonObject json) {
        if (json == null) {
            return;
        }

        String op = json.getString("op");
        if (WebsocketOpCodes.stats.toString().equals(op)
                || WebsocketOpCodes.heartbeatAck.toString().equals(op)
                || WebsocketOpCodes.ready.toString().equals(op)
                || WebsocketOpCodes.resumed.toString().equals(op)
                || WebsocketOpCodes.resuming.toString().equals(op)) {
            return;
        }

        if (pendingEvents.size() >= MAX_PENDING_EVENTS) {
            pendingEvents.pollFirst();
        }

        pendingEvents.addLast(json.copy());
    }

    private boolean shouldQueueWhenDisconnected(WebsocketOpCodes op) {
        return op != WebsocketOpCodes.stats
                && op != WebsocketOpCodes.heartbeatAck
                && op != WebsocketOpCodes.ready
                && op != WebsocketOpCodes.resumed
                && op != WebsocketOpCodes.resuming;
    }

    private JsonObject readyPayload(boolean resumed) {
        return new JsonObject()
                .put("sessionId", this.connectionId)
                .put("resumed", resumed)
                .put("heartbeatInterval", HEARTBEAT_INTERVAL_MS)
                .put("heartbeatTimeout", HEARTBEAT_TIMEOUT_MS)
                .put("ping", ping);
    }

    private void runOnContext(Runnable runnable) {
        Context target = context;

        if (target != null) {
            if (Vertx.currentContext() == target) {
                runnable.run();
            } else {
                target.runOnContext(__ -> runnable.run());
            }
            return;
        }

        main.getVertx().runOnContext(__ -> runnable.run());
    }

    private void cancelStatsTimer() {
        if (statsTimer != null) {
            main.getVertx().cancelTimer(statsTimer);
            statsTimer = null;
        }
    }

    private void cancelDeleteTimer() {
        if (deleteTimer != null) {
            main.getVertx().cancelTimer(deleteTimer);
            deleteTimer = null;
        }
    }

    private void stopHeartbeatTimeoutCheck() {
        if (heartbeatTimer != null) {
            main.getVertx().cancelTimer(heartbeatTimer);
            heartbeatTimer = null;
        }
    }

    private void scheduleDeleteTimer() {
        if (deleteTimer != null) {
            return;
        }

        deleteTimer = main.getVertx().setTimer(RESUME_TIMEOUT_MS, __ -> main.getWebsocketManager().deleteConnection(connectionId));
    }

    private void closePolicyViolation(String reason) {
        alive = false;
        resuming = true;
        stopHeartbeatTimeoutCheck();
        cancelStatsTimer();
        scheduleDeleteTimer();
        closeSocketQuietly(ws, (short) 1008, reason);
    }

    private void closeSocketQuietly(ServerWebSocket socket, short statusCode, String reason) {
        if (socket == null || socket.isClosed()) {
            return;
        }

        try {
            socket.close(statusCode, reason);
        } catch (Throwable ignored) {
        }
    }

    private String shortConnectionId() {
        return connectionId.length() <= 8 ? connectionId : connectionId.substring(0, 8) + "…";
    }
}
