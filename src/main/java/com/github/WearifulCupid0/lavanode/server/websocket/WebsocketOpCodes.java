package com.github.WearifulCupid0.lavanode.server.websocket;

public enum WebsocketOpCodes {
    playerUpdate,
    playerDestroy,

    filtersUpdate,
    volumeUpdate,
    pauseUpdate,
    seekUpdate,
    loopUpdate,
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

    connectionCreate,
    connectionConnect,
    connectionDelete,
    connectionDisconnect,
    connectionError,

    heartbeat,
    heartbeatAck,
    resumed,
    stats,
    ready,
    resuming
}
