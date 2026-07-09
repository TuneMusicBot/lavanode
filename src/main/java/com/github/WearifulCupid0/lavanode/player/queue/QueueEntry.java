package com.github.WearifulCupid0.lavanode.player.queue;

import com.github.WearifulCupid0.lavanode.util.RequestUtil;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import io.vertx.core.json.JsonObject;

import java.util.Objects;

public final class QueueEntry {
    private final String id;
    private final AudioTrack track;
    private final String requesterId;
    private final long addedAt;
    private final JsonObject extraData;

    public QueueEntry(String id, AudioTrack track, String requesterId, long addedAt) {
        this(id, track, requesterId, addedAt, new JsonObject());
    }

    public QueueEntry(String id, AudioTrack track, String requesterId, long addedAt, JsonObject extraData) {
        this.id = Objects.requireNonNull(id, "id");
        this.track = Objects.requireNonNull(track, "track");
        this.requesterId = requesterId;
        this.addedAt = addedAt;
        this.extraData = extraData != null ? extraData.copy() : new JsonObject();
    }

    public String getId() {
        return id;
    }

    public AudioTrack getTrack() {
        return track;
    }

    public String getRequesterId() {
        return requesterId;
    }

    public long getAddedAt() {
        return addedAt;
    }

    public JsonObject getExtraData() {
        return extraData.copy();
    }

    public JsonObject toJson(AudioPlayerManager audioPlayerManager) {
        return new JsonObject()
                .put("id", id)
                .put("requesterId", requesterId)
                .put("addedAt", addedAt)
                .put("extraData", extraData.copy())
                .put("track", RequestUtil.trackToJson(audioPlayerManager, track));
    }

    public QueueEntry copyWithPosition(long position) {
        AudioTrack clone = this.track.makeClone();
        clone.setPosition(Math.max(0L, position));

        return new QueueEntry(
                this.id,
                clone,
                this.requesterId,
                this.addedAt,
                this.extraData
        );
    }
}
