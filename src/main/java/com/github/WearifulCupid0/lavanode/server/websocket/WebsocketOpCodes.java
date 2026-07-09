package com.github.WearifulCupid0.lavanode.server.websocket;

public enum WebsocketOpCodes {
    playerUpdate,
    playerDestroy,

    filtersUpdate,
    volumeUpdate,
    pauseUpdate,
    seekUpdate,
    providerModeUpdate,

    queueUpdate,
    queueEnd,
    queueShuffle,
    queueClear,
    queueEntryRemoved,

    trackStart,
    trackEnd,
    trackStuck,
    trackException,

    gatewayReady,
    gatewayError,
    gatewayDisconnect,

    heartbeat,
    heartbeatAck,
    resumed,
    stats,
    ready,
    resuming
}
