package com.github.WearifulCupid0.lavanode.player.frame.crossfade;

/**
 * Mixer para o caminho de crossfade.
 *
 * A versão anterior calculava cos/sin e fazia operações double por amostra.
 * Isso é caro porque o mixer roda até 50 vezes por segundo por player, com
 * 1920 samples por frame em áudio estéreo 48 kHz/20 ms. Aqui os ganhos são
 * pré-calculados por frame em Q15 e o loop quente fica só com int math.
 */
final class PcmCrossfadeMixer {
    private static final int Q15_ONE = 1 << 15;
    private static final int HEADROOM_Q15 = (int) Math.round(CrossfadeAudio.MIX_HEADROOM * Q15_ONE);

    private int configuredFrameCount;
    private int[] activeGains;
    private int[] incomingGains;

    PcmCrossfadeMixer(int frameCount) {
        configure(frameCount);
    }

    void configure(int frameCount) {
        int safeFrameCount = Math.max(1, frameCount);

        if (safeFrameCount == configuredFrameCount && activeGains != null && incomingGains != null) {
            return;
        }

        configuredFrameCount = safeFrameCount;
        activeGains = new int[safeFrameCount + 1];
        incomingGains = new int[safeFrameCount + 1];

        for (int frame = 0; frame <= safeFrameCount; frame++) {
            double progress = Math.min(1.0, Math.max(0.0, frame / (double) safeFrameCount));

            activeGains[frame] = toQ15(Math.cos(progress * Math.PI / 2.0));
            incomingGains[frame] = toQ15(Math.sin(progress * Math.PI / 2.0));
        }
    }

    void mix(
            short[] active,
            boolean activeAvailable,
            short[] incoming,
            boolean incomingAvailable,
            short[] output,
            int frameIndex,
            int frameCount
    ) {
        configure(frameCount);

        int index = Math.max(0, Math.min(frameIndex, configuredFrameCount));

        int activeGain = activeAvailable ? activeGains[index] : 0;
        int incomingGain = incomingAvailable ? incomingGains[index] : 0;

        if (!activeAvailable && incomingAvailable) {
            incomingGain = HEADROOM_Q15;
        }

        if (activeAvailable && !incomingAvailable) {
            mixSingle(active, output, activeGain);
            return;
        }

        if (!activeAvailable && incomingAvailable) {
            mixSingle(incoming, output, incomingGain);
            return;
        }

        if (!activeAvailable) {
            PcmFrameReader.fillSilence(output);
            return;
        }

        for (int i = 0; i < output.length; i++) {
            int sample = ((active[i] * activeGain) + (incoming[i] * incomingGain)) >> 15;
            output[i] = clampToShort(sample);
        }
    }

    private static int toQ15(double gain) {
        return (int) Math.round(Math.max(0.0, Math.min(1.0, gain)) * HEADROOM_Q15);
    }

    private static void mixSingle(short[] input, short[] output, int gain) {
        if (gain == Q15_ONE) {
            System.arraycopy(input, 0, output, 0, output.length);
            return;
        }

        for (int i = 0; i < output.length; i++) {
            output[i] = clampToShort((input[i] * gain) >> 15);
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
