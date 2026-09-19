package com.carstream.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Tracks and limits independent video relays. */
public final class StreamRegistry {
    public static final int DEFAULT_MAX_STREAMS = 12;

    public static final class Ticket {
        private final StreamRegistry owner;
        private final long id;
        private final long startedAt;
        private final MediaItem item;
        private final String clientAddress;
        private final AtomicLong bytes = new AtomicLong();
        private volatile boolean closed;

        Ticket(StreamRegistry owner, long id, MediaItem item, String clientAddress) {
            this.owner = owner;
            this.id = id;
            this.item = item;
            this.clientAddress = clientAddress == null ? "unknown" : clientAddress;
            this.startedAt = System.currentTimeMillis();
        }

        public void addBytes(long count) {
            if (count <= 0 || closed) return;
            bytes.addAndGet(count);
            owner.totalBytes.addAndGet(count);
        }

        public long getId() { return id; }
        public long getBytes() { return bytes.get(); }
        public long getStartedAt() { return startedAt; }
        public MediaItem getItem() { return item; }
        public String getClientAddress() { return clientAddress; }

        public void close() {
            if (closed) return;
            closed = true;
            owner.finish(this);
        }
    }

    private final int maximum;
    private final Semaphore slots;
    private final AtomicLong nextId = new AtomicLong();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicLong totalStarted = new AtomicLong();
    final AtomicLong totalBytes = new AtomicLong();
    private final ConcurrentHashMap<Long, Ticket> tickets = new ConcurrentHashMap<Long, Ticket>();

    public StreamRegistry() { this(DEFAULT_MAX_STREAMS); }

    public StreamRegistry(int maximum) {
        this.maximum = Math.max(2, maximum);
        slots = new Semaphore(this.maximum, true);
    }

    public Ticket tryStart(MediaItem item, String clientAddress) {
        if (!slots.tryAcquire()) return null;
        long id = nextId.incrementAndGet();
        Ticket ticket = new Ticket(this, id, item, clientAddress);
        tickets.put(id, ticket);
        active.incrementAndGet();
        totalStarted.incrementAndGet();
        return ticket;
    }

    private void finish(Ticket ticket) {
        if (ticket == null || tickets.remove(ticket.id) == null) return;
        active.decrementAndGet();
        slots.release();
    }

    public int getActiveCount() { return active.get(); }
    public int getMaximum() { return maximum; }
    public long getTotalStarted() { return totalStarted.get(); }
    public long getTotalBytes() { return totalBytes.get(); }

    public List<Ticket> snapshot() {
        List<Ticket> result = new ArrayList<Ticket>(tickets.values());
        Collections.sort(result, new Comparator<Ticket>() {
            @Override public int compare(Ticket left, Ticket right) {
                return left.startedAt < right.startedAt ? -1 : (left.startedAt == right.startedAt ? 0 : 1);
            }
        });
        return result;
    }

    public JSONObject toJson() throws JSONException {
        JSONArray streams = new JSONArray();
        long now = System.currentTimeMillis();
        for (Ticket ticket : snapshot()) {
            MediaItem item = ticket.item;
            streams.put(new JSONObject()
                    .put("id", ticket.id)
                    .put("title", item == null ? "Unknown video" : item.title)
                    .put("mediaId", item == null ? "" : item.id)
                    .put("client", ticket.clientAddress)
                    .put("bytes", ticket.bytes.get())
                    .put("seconds", Math.max(0L, (now - ticket.startedAt) / 1000L)));
        }
        return new JSONObject()
                .put("active", getActiveCount())
                .put("maximum", getMaximum())
                .put("totalStarted", getTotalStarted())
                .put("totalBytes", getTotalBytes())
                .put("streams", streams);
    }

    public String summary() {
        int count = getActiveCount();
        return count + " active stream" + (count == 1 ? "" : "s") + " of " + maximum;
    }
}
