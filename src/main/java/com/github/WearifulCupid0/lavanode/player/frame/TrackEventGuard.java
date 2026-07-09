package com.github.WearifulCupid0.lavanode.player.frame;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Guards asynchronous lavaplayer callbacks against stale tracks.
 *
 * Lavaplayer can emit onTrackEnd/onTrackException/onTrackStuck after a user
 * command already stopped the old track and started another one. Comparing only
 * suppressEvents is not enough because those callbacks can arrive later, after
 * suppressEvents is false again.
 *
 * This guard gives every started AudioTrack instance a generation. A callback is
 * accepted only if it belongs to the exact AudioTrack instance currently bound
 * to that slot and to the same generation that was bound when the slot was set.
 */
public final class TrackEventGuard {
    private final Map<AudioTrack, Long> generations = new IdentityHashMap<>();
    private long nextGeneration;

    public long bind(AudioTrack track) {
        long generation = ++nextGeneration;

        if (track != null) {
            generations.put(track, generation);
        }

        return generation;
    }

    public long invalidate(AudioTrack track) {
        if (track != null) {
            generations.remove(track);
        }

        return ++nextGeneration;
    }

    public void unbind(AudioTrack track) {
        if (track != null) {
            generations.remove(track);
        }
    }

    public boolean accepts(AudioTrack expectedTrack, long expectedGeneration, AudioTrack eventTrack) {
        if (expectedTrack == null || eventTrack == null || expectedTrack != eventTrack) {
            return false;
        }

        Long actualGeneration = generations.get(eventTrack);
        return actualGeneration != null && actualGeneration == expectedGeneration;
    }

    public void clear() {
        generations.clear();
        nextGeneration++;
    }
}
