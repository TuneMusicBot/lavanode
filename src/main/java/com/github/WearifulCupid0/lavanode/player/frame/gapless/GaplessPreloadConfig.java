package com.github.WearifulCupid0.lavanode.player.frame.gapless;

public final class GaplessPreloadConfig {
    private final long preloadBeforeMs;
    private final int prebufferFrames;
    private final long preloadLoadTimeoutMs;

    public GaplessPreloadConfig(long preloadBeforeMs, int prebufferFrames, long preloadLoadTimeoutMs) {
        this.preloadBeforeMs = Math.max(0L, preloadBeforeMs);
        this.prebufferFrames = Math.max(1, Math.min(50, prebufferFrames));
        this.preloadLoadTimeoutMs = Math.max(1L, preloadLoadTimeoutMs);
    }

    public long preloadBeforeMs() {
        return preloadBeforeMs;
    }

    public int prebufferFrames() {
        return prebufferFrames;
    }

    public long preloadLoadTimeoutMs() {
        return preloadLoadTimeoutMs;
    }

    public long effectiveTimeoutMs() {
        return Math.min(
                preloadLoadTimeoutMs,
                Math.max(1_000L, preloadBeforeMs - 500L)
        );
    }
}
