package com.carstream.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ClientRegistry {
    public interface Listener { void onClientsChanged(); }

    private final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<String, ClientSession>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();

    public void addListener(Listener listener) { listeners.addIfAbsent(listener); }
    public void removeListener(Listener listener) { listeners.remove(listener); }

    public ClientSession update(JSONObject telemetry) {
        String id = telemetry.optString("clientId", "").trim();
        if (id.isEmpty()) return null;
        ClientSession session = sessions.get(id);
        if (session == null) {
            ClientSession created = new ClientSession(id, telemetry.optString("name", "Tablet"));
            ClientSession existing = sessions.putIfAbsent(id, created);
            session = existing == null ? created : existing;
            if (existing == null) EventLogger.info("Client", "Screen connected: " + session.name + " (" + id + ")");
        }

        String name = telemetry.optString("name", "").trim();
        if (!name.isEmpty()) session.name = name;
        session.mediaId = telemetry.optString("mediaId", session.mediaId);
        session.title = telemetry.optString("title", session.title);
        session.positionSeconds = finite(telemetry.optDouble("position", session.positionSeconds));
        session.durationSeconds = finite(telemetry.optDouble("duration", session.durationSeconds));
        session.paused = telemetry.optBoolean("paused", session.paused);
        session.buffering = telemetry.optBoolean("buffering", session.buffering);
        session.needsGesture = telemetry.optBoolean("needsGesture", session.needsGesture);
        session.lastSeenMillis = System.currentTimeMillis();
        notifyChanged();
        return session;
    }

    public JSONObject responseFor(ClientSession session, long seenVersion) throws JSONException {
        return session.responseFor(seenVersion);
    }

    public boolean send(String id, JSONObject command) {
        ClientSession session = sessions.get(id);
        if (session == null) return false;
        session.queueCommand(command);
        EventLogger.info("Client", "Queued command for " + session.name + ": " + command.optString("action", "unknown"));
        notifyChanged();
        return true;
    }

    public boolean setControlMode(String id, ControlMode mode) {
        ClientSession session = sessions.get(id);
        if (session == null) return false;
        session.controlMode = mode;
        EventLogger.info("Client", "Control mode for " + session.name + " changed to " + mode.name());
        try { session.queueCommand(PlaybackCommand.controlMode(mode)); }
        catch (JSONException ignored) { }
        notifyChanged();
        return true;
    }

    public List<ClientSession> snapshot() {
        List<ClientSession> result = new ArrayList<ClientSession>(sessions.values());
        Collections.sort(result, new Comparator<ClientSession>() {
            @Override public int compare(ClientSession left, ClientSession right) {
                return left.name.toLowerCase(Locale.US).compareTo(right.name.toLowerCase(Locale.US));
            }
        });
        return result;
    }

    public JSONArray toJson() throws JSONException {
        JSONArray result = new JSONArray();
        for (ClientSession session : snapshot()) result.put(session.toJson());
        return result;
    }

    public void clearOfflineOlderThan(long ageMillis) {
        long cutoff = System.currentTimeMillis() - ageMillis;
        List<String> expired = new ArrayList<String>();
        for (Map.Entry<String, ClientSession> entry : sessions.entrySet()) {
            if (entry.getValue().lastSeenMillis < cutoff) expired.add(entry.getKey());
        }
        for (String id : expired) {
            ClientSession removed = sessions.remove(id);
            if (removed != null) EventLogger.info("Client", "Removed inactive screen: " + removed.name);
        }
        notifyChanged();
    }

    private void notifyChanged() {
        for (Listener listener : listeners) listener.onClientsChanged();
    }

    private static double finite(double value) {
        return Double.isInfinite(value) || Double.isNaN(value) || value < 0 ? 0 : value;
    }
}
