package com.github.WearifulCupid0.lavanode.player.filters.config;

import com.github.WearifulCupid0.lavanode.player.filters.Config;
import com.sedmelluq.discord.lavaplayer.filter.AudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import io.vertx.core.json.JsonObject;
import me.devoxin.lavadspx.NormalizationFilter;

public class NormalizationConfig implements Config {
    private float maxAmplitude = 1.0f;
    private boolean adaptive = false;

    public float maxAmplitude() {
        return maxAmplitude;
    }

    public void setMaxAmplitude(float maxAmplitude) {
        this.maxAmplitude = maxAmplitude;
    }

    public boolean adaptive() {
        return adaptive;
    }

    public void setAdaptive(boolean adaptive) {
        this.adaptive = adaptive;
    }

    @Override
    public String name() {
        return "normalization";
    }

    @Override
    public boolean enabled() {
        return Config.isSet(maxAmplitude, 1.0f);
    }

    @Override
    public AudioFilter create(AudioDataFormat format, FloatPcmAudioFilter output) {
        return new NormalizationFilter(output, maxAmplitude, adaptive);
    }

    @Override
    public JsonObject encode() {
        return new JsonObject()
                .put("adaptive", adaptive)
                .put("maxAmplitude", maxAmplitude);
    }
}
