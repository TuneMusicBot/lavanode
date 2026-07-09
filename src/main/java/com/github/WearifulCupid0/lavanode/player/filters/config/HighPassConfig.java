package com.github.WearifulCupid0.lavanode.player.filters.config;

import com.github.WearifulCupid0.lavanode.player.filters.Config;
import com.sedmelluq.discord.lavaplayer.filter.AudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import io.vertx.core.json.JsonObject;
import me.devoxin.lavadspx.HighPassFilter;

public class HighPassConfig implements Config {
    private float boostFactor = 1.0f;
    private int cutoffFrequency = 1;

    public float boostFactor() {
        return boostFactor;
    }

    public void setBoostFactor(float boostFactor) {
        this.boostFactor = boostFactor;
    }

    public int cutoffFrequency() {
        return cutoffFrequency;
    }

    public void setCutoffFrequency(int cutoffFrequency) {
        this.cutoffFrequency = cutoffFrequency;
    }

    @Override
    public String name() {
        return "highpass";
    }

    @Override
    public boolean enabled() {
        return Config.isSet(boostFactor, 1.0f) && Config.isSet(cutoffFrequency, 1);
    }

    @Override
    public AudioFilter create(AudioDataFormat format, FloatPcmAudioFilter output) {
        return new HighPassFilter(output, format.channelCount, 0, cutoffFrequency, boostFactor);
    }

    @Override
    public JsonObject encode() {
        return new JsonObject()
                .put("boostFactor", boostFactor)
                .put("cutoffFrequency", cutoffFrequency);
    }
}
