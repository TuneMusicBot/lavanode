package com.github.WearifulCupid0.lavanode.player.filters;

import com.sedmelluq.discord.lavaplayer.filter.AudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import io.vertx.core.json.JsonObject;

public interface Config {
    float MINIMUM_FP_DIFF = 0.01f;

    String name();

    boolean enabled();

    AudioFilter create(AudioDataFormat format, FloatPcmAudioFilter output);

    JsonObject encode();

    static boolean isSet(float value, float defaultValue) {
        return Math.abs(value - defaultValue) >= MINIMUM_FP_DIFF;
    }
}
