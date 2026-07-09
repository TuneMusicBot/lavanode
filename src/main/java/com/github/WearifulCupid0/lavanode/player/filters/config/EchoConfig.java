package com.github.WearifulCupid0.lavanode.player.filters.config;

import com.github.WearifulCupid0.lavanode.player.filters.Config;
import com.sedmelluq.discord.lavaplayer.filter.AudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import io.vertx.core.json.JsonObject;
import me.devoxin.lavadspx.EchoFilter;

public class EchoConfig implements Config {
    private float echoLength = 0f;
    private float decay = 0f;

    public float echoLength() { return echoLength; }

    public void setEchoLength(float echoLength) {
        if(echoLength <= 0) {
            throw new IllegalArgumentException("echoLength <= 0");
        }
        this.echoLength = echoLength;
    }

    public float decay() { return decay; }

    public void setDecay(float decay) {
        if(decay <= 0) {
            throw new IllegalArgumentException("decay <= 0");
        }
        this.decay = decay;
    }

    @Override
    public String name() {
        return "echo";
    }

    @Override
    public boolean enabled() {
        return Config.isSet(echoLength, 0f) && Config.isSet(decay, 0f);
    }

    @Override
    public AudioFilter create(AudioDataFormat format, FloatPcmAudioFilter output) {
        return new EchoFilter(output, format.sampleRate, format.channelCount, echoLength, decay);
    }

    @Override
    public JsonObject encode() {
        return new JsonObject()
                .put("echoLength", echoLength)
                .put("decay", decay);
    }
}
