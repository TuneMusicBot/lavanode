package com.github.WearifulCupid0.lavanode.player.filters.config;

import com.github.WearifulCupid0.lavanode.player.filters.Config;
import com.github.natanbc.lavadsp.rotation.RotationPcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.AudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import io.vertx.core.json.JsonObject;

public class RotationConfig implements Config {
    private float rotationHz = 5f;

    public float rotationHz() {
        return rotationHz;
    }

    public void setRotationHz(float rotationHz) {
        this.rotationHz = rotationHz;
    }

    @Override
    public String name() {
        return "rotation";
    }

    @Override
    public boolean enabled() {
        return Config.isSet(rotationHz, 5f);
    }

    @Override
    public AudioFilter create(AudioDataFormat format, FloatPcmAudioFilter output) {
        return new RotationPcmAudioFilter(output, format.sampleRate)
                .setRotationSpeed(rotationHz);
    }

    @Override
    public JsonObject encode() {
        return new JsonObject().put("rotationHz", rotationHz);
    }
}
