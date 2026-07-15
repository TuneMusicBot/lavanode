package com.github.WearifulCupid0.lavanode.player.queue;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Random;

public final class PlayerQueue {
    private static final int DEFAULT_MAX_HISTORY_SIZE = 100;

    private final Deque<QueueEntry> queue = new ArrayDeque<>();
    private final Deque<QueueEntry> history = new ArrayDeque<>();
    private final int maxHistorySize;

    public PlayerQueue() {
        this(DEFAULT_MAX_HISTORY_SIZE);
    }

    public PlayerQueue(int maxHistorySize) {
        this.maxHistorySize = Math.max(0, maxHistorySize);
    }

    public synchronized void add(QueueEntry entry) {
        queue.addLast(Objects.requireNonNull(entry, "entry"));
    }

    public synchronized void addFirst(QueueEntry entry) {
        queue.addFirst(Objects.requireNonNull(entry, "entry"));
    }

    public synchronized void addAll(Collection<QueueEntry> entries) {
        Objects.requireNonNull(entries, "entries");

        for (QueueEntry entry : entries) {
            queue.addLast(Objects.requireNonNull(entry, "entry"));
        }
    }

    public synchronized QueueEntry poll() {
        return queue.pollFirst();
    }

    public synchronized QueueEntry peek() {
        return queue.peekFirst();
    }

    public synchronized QueueEntry removeById(String entryId) {
        QueueEntry removed = removeById(queue, entryId);

        if (removed != null) {
            return removed;
        }

        return removeById(history, entryId);
    }

    public synchronized QueueEntry removeQueuedById(String entryId) {
        return removeById(queue, entryId);
    }

    public synchronized QueueEntry removeHistoryById(String entryId) {
        return removeById(history, entryId);
    }

    private QueueEntry removeById(Deque<QueueEntry> entries, String entryId) {
        if (entryId == null || entryId.isBlank()) {
            return null;
        }

        Iterator<QueueEntry> iterator = entries.iterator();

        while (iterator.hasNext()) {
            QueueEntry entry = iterator.next();

            if (entryId.equals(entry.getId())) {
                iterator.remove();
                return entry;
            }
        }

        return null;
    }

    public synchronized boolean shuffle() {
        if (queue.size() < 2) {
            return false;
        }

        List<QueueEntry> entries = new ArrayList<>(queue);

        Collections.shuffle(entries);

        queue.clear();
        queue.addAll(entries);

        return true;
    }

    public synchronized boolean shuffle(Random random) {
        Objects.requireNonNull(random, "random");

        if (queue.size() < 2) {
            return false;
        }

        List<QueueEntry> entries = new ArrayList<>(queue);

        Collections.shuffle(entries, random);

        queue.clear();
        queue.addAll(entries);

        return true;
    }

    public synchronized void addToHistory(QueueEntry entry) {
        if (entry == null || maxHistorySize == 0) {
            return;
        }

        history.addLast(entry);

        while (history.size() > maxHistorySize) {
            history.pollFirst();
        }
    }

    public synchronized QueueEntry pollPrevious() {
        return history.pollLast();
    }

    public synchronized QueueEntry peekPrevious() {
        return history.peekLast();
    }

    public synchronized boolean hasPrevious() {
        return !history.isEmpty();
    }

    public synchronized List<QueueEntry> historySnapshot() {
        return List.copyOf(history);
    }

    /**
     * Moves the whole playback history back to the pending queue, preserving
     * the original playback order and resetting every track position to 0.
     *
     * Used by queue loop when the queue naturally reaches the end: after the
     * queueEnd event is emitted, the old history becomes the next queue cycle.
     */
    public synchronized boolean moveHistoryToQueueFromStart() {
        if (history.isEmpty()) {
            return false;
        }

        List<QueueEntry> entries = new ArrayList<>(history);
        history.clear();

        for (QueueEntry entry : entries) {
            queue.addLast(entry.copyWithPosition(0));
        }

        return true;
    }

    public synchronized List<QueueEntry> snapshot() {
        return List.copyOf(queue);
    }

    public synchronized int size() {
        return queue.size();
    }

    public synchronized int historySize() {
        return history.size();
    }

    public synchronized void clear() {
        queue.clear();
        history.clear();
    }

    public synchronized boolean clearQueueOnly() {
        if (queue.isEmpty()) {
            return false;
        }

        queue.clear();
        return true;
    }

    public synchronized boolean clearHistoryOnly() {
        if (history.isEmpty()) {
            return false;
        }

        history.clear();
        return true;
    }

    public synchronized boolean clearQueueAndHistory() {
        boolean changed = !queue.isEmpty() || !history.isEmpty();

        queue.clear();
        history.clear();

        return changed;
    }

    public synchronized boolean isEmpty() {
        return queue.isEmpty();
    }

    public synchronized JsonObject toJson(AudioPlayerManager audioPlayerManager) {
        JsonArray queue = new JsonArray();
        for (QueueEntry entry : this.queue) {
            queue.add(entry.toJson(audioPlayerManager));
        }

        JsonArray history = new JsonArray();
        for (QueueEntry entry : this.history) {
            history.add(entry.toJson(audioPlayerManager));
        }

        return sizeToJson()
                .put("tracks", queue)
                .put("history", history);
    }

    public synchronized JsonObject sizeToJson() {
        return new JsonObject()
                .put("tracksSize", queue.size())
                .put("historySize", history.size());
    }
}
