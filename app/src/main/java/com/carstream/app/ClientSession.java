package com.carstream.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicLong;

public final class ClientSession {
    public final String id;
    public volatile String name;
    public volatile String mediaId = "";
    public volatile String title = "Nothing playing";
    public volatile double positionSeconds;
    public volatile double durationSeconds;
    public volatile boolean paused = true;
    public volatile boolean buffering;
    public volatile boolean needsGesture;
    public volatile long lastSeenMillis = System.currentTimeMillis();
    public volatile ControlMode controlMode = ControlMode.FREE;

    private final AtomicLong commandVersion = new AtomicLong();
    private volatile JSONObject pendingCommand;

    ClientSession(String id, String name) {
        this.id = id;
        this.name = name == null || name.trim().isEmpty() ? "Tablet" : name.trim();
    }

    public boolean isOnline() {
        return System.currentTimeMillis() - lastSeenMillis < 10_000L;
    }

    public int progressPermille() {
        if (durationSeconds <= 0) return 0;
        return (int) Math.max(0, Math.min(1000,
                Math.round(positionSeconds * 1000.0 / durationSeconds)));
    }

    long queueCommand(JSONObject command) {
        pendingCommand = command;
        return commandVersion.incrementAndGet();
    }

    JSONObject responseFor(long seenVersion) throws JSONException {
        long currentVersion = commandVersion.get();
        JSONObject result = new JSONObject()
                .put("serverTime", System.currentTimeMillis())
                .put("commandVersion", currentVersion)
                .put("controlMode", controlMode.name());
        JSONObject command = pendingCommand;
        if (command != null && currentVersion > seenVersion) result.put("command", command);
        return result;
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("name", name)
                .put("mediaId", mediaId)
                .put("title", title)
                .put("position", positionSeconds)
                .put("duration", durationSeconds)
                .put("paused", paused)
                .put("buffering", buffering)
                .put("needsGesture", needsGesture)
                .put("online", isOnline())
                .put("lastSeen", lastSeenMillis)
                .put("controlMode", controlMode.name());
    }
}
