package com.github.WearifulCupid0.lavanode.player.frame.crossfade;

final class PcmCrossfadeMixer {
    void mix(
            short[] active,
            boolean activeAvailable,
            short[] incoming,
            boolean incomingAvailable,
            short[] output,
            int frameIndex,
            int frameCount
    ) {
        double progress = Math.min(1.0, Math.max(0.0, frameIndex / (double) frameCount));

        double activeGain = activeAvailable
                ? Math.cos(progress * Math.PI / 2.0)
                : 0.0;

        double incomingGain = incomingAvailable
                ? Math.sin(progress * Math.PI / 2.0)
                : 0.0;

        if (!activeAvailable && incomingAvailable) {
            incomingGain = 1.0;
        }

        for (int i = 0; i < output.length; i++) {
            int sample = (int) Math.round(
                    (active[i] * activeGain + incoming[i] * incomingGain) * CrossfadeAudio.MIX_HEADROOM
            );

            output[i] = clampToShort(sample);
        }
    }

    private static short clampToShort(int value) {
        if (value > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }

        if (value < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }

        return (short) value;
    }
}
