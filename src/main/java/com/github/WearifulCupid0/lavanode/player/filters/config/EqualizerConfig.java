package com.github.WearifulCupid0.lavanode.player.filters.config;

import com.github.WearifulCupid0.lavanode.player.filters.Config;
import com.sedmelluq.discord.lavaplayer.filter.AudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.equalizer.Equalizer;
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class EqualizerConfig implements Config {
    private final float[] equalizerBands = new float[Equalizer.BAND_COUNT];

    public float getBand(int band) {
        return equalizerBands[band];
    }

    public void setBand(int band, float gain) {
        equalizerBands[band] = gain;
    }

    @Override
    public String name() {
        return "equalizer";
    }

    @Override
    public boolean enabled() {
        for(var band : equalizerBands) {
            if(Config.isSet(band, 0f)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public AudioFilter create(AudioDataFormat format, FloatPcmAudioFilter output) {
        return Equalizer.isCompatible(format) ? new Equalizer(format.channelCount, output, equalizerBands) : null;
    }

    @Override
    public JsonObject encode() {
        var array = new JsonArray();
        for(var i = 0; i < Equalizer.BAND_COUNT; i++) {
            array.add(new JsonObject()
                    .put("band", i)
                    .put("gain", equalizerBands[i])
            );
        }
        return new JsonObject().put("bands", array);
    }
}
