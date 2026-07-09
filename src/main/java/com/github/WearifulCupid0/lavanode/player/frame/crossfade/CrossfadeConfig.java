package com.github.WearifulCupid0.lavanode.player.frame.crossfade;

public final class CrossfadeConfig {
    private final long crossfadeMs;
    private final long preloadLeadMs;
    private final int crossfadeFrameCount;
    private final int incomingBufferLimitFrames;
    private final int minStartBufferFrames;
    private final int pumpFramesPerTick;

    public CrossfadeConfig(long crossfadeMs, long preloadLeadMs) {
        this.crossfadeMs = Math.max(CrossfadeAudio.FRAME_MS, crossfadeMs);
        this.preloadLeadMs = Math.max(0L, preloadLeadMs);

        this.crossfadeFrameCount = Math.max(
                1,
                (int) Math.ceil(this.crossfadeMs / (double) CrossfadeAudio.FRAME_MS)
        );

        int preloadLeadFrames = Math.max(
                0,
                (int) Math.ceil(this.preloadLeadMs / (double) CrossfadeAudio.FRAME_MS)
        );

        /*
         * O preloadLeadMs serve para dar tempo da fonte HTTP carregar, não para deixar
         * o incomingPlayer avançar vários segundos além do ponto audível. Por isso o
         * buffer segura a janela de crossfade + uma margem pequena.
         */
        int safetyFrames = Math.max(5, Math.min(25, Math.max(1, preloadLeadFrames / 6)));

        this.incomingBufferLimitFrames = this.crossfadeFrameCount + safetyFrames;
        this.minStartBufferFrames = Math.min(this.crossfadeFrameCount, Math.max(3, safetyFrames));
        this.pumpFramesPerTick = Math.max(1, Math.min(12, this.incomingBufferLimitFrames / 8));
    }

    public long crossfadeMs() {
        return crossfadeMs;
    }

    public long preloadLeadMs() {
        return preloadLeadMs;
    }

    public long preloadStartRemainingMs() {
        return crossfadeMs + preloadLeadMs;
    }

    public int crossfadeFrameCount() {
        return crossfadeFrameCount;
    }

    public int incomingBufferLimitFrames() {
        return incomingBufferLimitFrames;
    }

    public int minStartBufferFrames() {
        return minStartBufferFrames;
    }

    public int pumpFramesPerTick() {
        return pumpFramesPerTick;
    }
}
