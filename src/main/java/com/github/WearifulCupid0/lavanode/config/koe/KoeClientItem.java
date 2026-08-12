package com.github.WearifulCupid0.lavanode.config.koe;

import moe.kyokobot.koe.KoeClient;
import moe.kyokobot.koe.MediaConnection;

public class KoeClientItem {
    private final KoeClient koe;
    private int connections = 0;

    public KoeClientItem(KoeClient koe) {
        this.koe = koe;
    }

    public MediaConnection createConnection(long guildId) {
        this.connections++;
        return this.koe.createConnection(guildId);
    }

    public void destroyConnection(long guildId) {
        this.connections--;
        this.koe.destroyConnection(guildId);
    }

    public boolean shoudBeDeleted() {
        return this.connections == 0;
    }

    public void shutdown() {
        koe.close();
    }
}
