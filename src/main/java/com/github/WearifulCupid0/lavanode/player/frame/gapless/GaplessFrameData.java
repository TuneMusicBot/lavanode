package com.github.WearifulCupid0.lavanode.player.frame.gapless;

public final class GaplessFrameData {
    private final byte[] data;

    public GaplessFrameData(byte[] data) {
        this.data = data;
    }

    public byte[] data() {
        return data;
    }

    public int length() {
        return data.length;
    }
}