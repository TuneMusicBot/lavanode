package com.github.WearifulCupid0.lavanode.player.filters.config;

import com.github.WearifulCupid0.lavanode.player.filters.Config;
import com.github.natanbc.lavadsp.volume.VolumePcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.AudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import io.vertx.core.json.JsonObject;

public class VolumeConfig implements Config {
    private float volume = 1f;

    public float volume() {
        return volume;
    }

    public void setVolume(float volume) {
        if(volume <= 0) {
            throw new IllegalArgumentException("Volume <= 0.0");
        }
        if(volume > 5) {
            throw new IllegalArgumentException("Volume > 5.0");
        }
        this.volume = volume;
    }

    @Override
    public String name() {
        return "volume";
    }

    @Override
    public boolean enabled() {
        return Config.isSet(volume, 1f);
    }

    @Override
    public AudioFilter create(AudioDataFormat format, FloatPcmAudioFilter output) {
        return new VolumePcmAudioFilter(output, format.channelCount)
                .setVolume(volume);
    }

    @Override
    public JsonObject encode() {
        return new JsonObject()
                .put("volume", volume);
    }
}
