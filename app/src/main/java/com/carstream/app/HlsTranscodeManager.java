package com.carstream.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Coordinates browser playback sessions without shipping a native media engine.
 * Browser-compatible HLS is created by TorBox when the user's plan supports it.
 * CarStream relays the resulting playlist and segments over the private local network.
 */
public final class HlsTranscodeManager implements AutoCloseable {
    public static final int MAX_COMPATIBILITY_STREAMS = 12;
    private static final long SESSION_TTL_MILLIS = 2L * 60L * 60L * 1000L;
    private static final long IDLE_TTL_MILLIS = 30L * 60L * 1000L;

    private final Context context;
    private final TorBoxClient torBox;
    private final LocalHttpServer.NetworkMode networkMode;
    private final Object lock = new Object();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new LinkedHashMap<String, Session>();
    private volatile String pairingCode;
    private volatile boolean closed;

    public HlsTranscodeManager(Context context, String pairingCode, TorBoxClient torBox,
                               LocalHttpServer.NetworkMode networkMode) {
        this.context = context == null ? null : context.getApplicationContext();
        this.pairingCode = pairingCode == null ? "" : pairingCode;
        this.torBox = torBox;
        this.networkMode = networkMode;
    }

    public void setPairingCode(String value) {
        pairingCode = value == null ? "" : value;
    }

    public JSONObject request(MediaItem item, double startAtSeconds, String viewerId) throws JSONException {
        cleanupExpired();
        double seek = Math.max(0.0, startAtSeconds);
        if (item == null) return failed("That video is no longer in the TorBox library.");
        if (!item.ready) return failed("TorBox is still preparing that video.");
        if (closed) return failed("The browser playback service is stopped.");

        String viewer = cleanViewer(viewerId);
        String key = item.id + "@" + viewer;
        Session existing;
        synchronized (lock) {
            existing = findByKeyLocked(key);
            if (existing != null) {
                existing.lastAccessMillis = System.currentTimeMillis();
                return existing.toJson(seek, pairingCode);
            }
            if (sessions.size() >= MAX_COMPATIBILITY_STREAMS) {
                return new JSONObject()
                        .put("state", "BUSY")
                        .put("mode", "hls")
                        .put("message", "CarStream already has " + MAX_COMPATIBILITY_STREAMS
                                + " browser playback sessions. Stop one screen and try again.")
                        .put("active", sessions.size())
                        .put("maximum", MAX_COMPATIBILITY_STREAMS);
            }
        }

        try {
            boolean requireCellular = networkMode != null && networkMode.requireCellular();
            TorBoxClient.BrowserStream stream = torBox.createBrowserStream(item, requireCellular);
            Session created = new Session(randomId(), key, item, viewer, stream);
            synchronized (lock) {
                if (closed) return failed("The browser playback service stopped while the video was preparing.");
                sessions.put(created.id, created);
            }
            EventLogger.info(context, "Browser", "TorBox browser stream ready for " + item.title
                    + "; transcoding=" + stream.isTranscoding + "; needsTranscoding=" + stream.needsTranscoding);
            return created.toJson(seek, pairingCode);
        } catch (TorBoxClient.BrowserStreamException error) {
            String reason = error.detail;
            boolean planRestricted = "PLAN_RESTRICTED_FEATURE".equalsIgnoreCase(error.code);
            EventLogger.warn(context, "Browser", "TorBox browser stream unavailable ("
                    + error.code + "): " + reason);
            return directFallback(item, seek, planRestricted
                    ? "Your TorBox plan does not include browser conversion. Trying the original file in Chrome."
                    : "TorBox browser conversion was unavailable. Trying the original file in Chrome. " + reason,
                    error.code);
        } catch (Exception error) {
            EventLogger.warn(context, "Browser", "Could not create TorBox browser stream", error);
            return directFallback(item, seek,
                    "TorBox browser conversion could not start. Trying the original file in Chrome. "
                            + safeMessage(error), "STREAM_SETUP_ERROR");
        }
    }

    public JSONObject release(String sessionId, String viewerId) throws JSONException {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return new JSONObject().put("ok", true).put("released", false);
        }
        Session removed;
        synchronized (lock) { removed = sessions.remove(sessionId); }
        return new JSONObject().put("ok", true).put("released", removed != null);
    }

    public URL initialPlaylist(String sessionId) {
        cleanupExpired();
        synchronized (lock) {
            Session session = sessions.get(sessionId);
            if (session == null) return null;
            session.lastAccessMillis = System.currentTimeMillis();
            return session.stream.hlsUrl;
        }
    }

    public String registerResource(String sessionId, URL url) {
        if (url == null || !allowed(url)) return null;
        synchronized (lock) {
            Session session = sessions.get(sessionId);
            if (session == null) return null;
            for (Map.Entry<String, URL> entry : session.resources.entrySet()) {
                if (entry.getValue().toExternalForm().equals(url.toExternalForm())) return entry.getKey();
            }
            String token = randomId();
            session.resources.put(token, url);
            session.lastAccessMillis = System.currentTimeMillis();
            return token;
        }
    }

    public URL resource(String sessionId, String token) {
        cleanupExpired();
        synchronized (lock) {
            Session session = sessions.get(sessionId);
            if (session == null || token == null) return null;
            URL result = session.resources.get(token);
            if (result != null) session.lastAccessMillis = System.currentTimeMillis();
            return result;
        }
    }

    public JSONObject diagnostics() throws JSONException {
        cleanupExpired();
        JSONArray values = new JSONArray();
        synchronized (lock) {
            for (Session session : sessions.values()) values.put(session.diagnostics());
            return new JSONObject()
                    .put("engine", "TorBox browser HLS")
                    .put("nativeMediaLibraries", false)
                    .put("active", sessions.size())
                    .put("maximum", MAX_COMPATIBILITY_STREAMS)
                    .put("sessions", values);
        }
    }

    public int getActiveCount() {
        cleanupExpired();
        synchronized (lock) { return sessions.size(); }
    }

    private JSONObject directFallback(MediaItem item, double seek, String message, String code)
            throws JSONException {
        return new JSONObject()
                .put("state", "READY")
                .put("mode", "direct")
                .put("provider", "TorBox original file")
                .put("sessionId", "")
                .put("playbackUrl", "/stream/" + urlSegment(item.id)
                        + "?code=" + urlSegment(pairingCode))
                .put("offsetSeconds", 0)
                .put("seekSeconds", seek)
                .put("durationSeconds", 0)
                .put("quality", "Original file")
                .put("message", message)
                .put("errorCode", code == null ? "" : code);
    }

    private static JSONObject failed(String message) throws JSONException {
        return new JSONObject().put("state", "FAILED").put("mode", "none").put("message", message);
    }

    private Session findByKeyLocked(String key) {
        for (Session session : sessions.values()) if (session.key.equals(key)) return session;
        return null;
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<String>();
        synchronized (lock) {
            for (Map.Entry<String, Session> entry : sessions.entrySet()) {
                Session session = entry.getValue();
                if (now - session.createdMillis > SESSION_TTL_MILLIS
                        || now - session.lastAccessMillis > IDLE_TTL_MILLIS) expired.add(entry.getKey());
            }
            for (String id : expired) sessions.remove(id);
        }
        if (!expired.isEmpty()) EventLogger.debug(context, "Browser", "Expired " + expired.size() + " HLS session(s)");
    }

    private static boolean allowed(URL url) {
        if (url == null || !"https".equalsIgnoreCase(url.getProtocol())) return false;
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.US);
        return host.equals("api.torbox.app") || host.endsWith(".torbox.app")
                || host.endsWith(".tb-cdn.cx") || host.endsWith(".tb-cdn.net")
                || host.contains("tb-cdn");
    }

    private String randomId() {
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        StringBuilder value = new StringBuilder();
        for (byte item : bytes) value.append(String.format(Locale.US, "%02x", item & 0xff));
        return value.toString();
    }

    private static String cleanViewer(String value) {
        String clean = value == null ? "screen" : value.trim().replace('\n', ' ').replace('\r', ' ');
        if (clean.isEmpty()) clean = "screen";
        return clean.length() > 100 ? clean.substring(0, 100) : clean;
    }

    private static String urlSegment(String value) {
        try { return java.net.URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20"); }
        catch (Exception ignored) { return value == null ? "" : value; }
    }

    private static String safeMessage(Throwable error) {
        String value = error == null ? null : error.getMessage();
        return value == null || value.trim().isEmpty()
                ? (error == null ? "Unknown error" : error.getClass().getSimpleName()) : value.trim();
    }

    @Override public void close() {
        closed = true;
        synchronized (lock) { sessions.clear(); }
    }

    private static final class Session {
        final String id;
        final String key;
        final MediaItem item;
        final String viewerId;
        final TorBoxClient.BrowserStream stream;
        final long createdMillis = System.currentTimeMillis();
        volatile long lastAccessMillis = createdMillis;
        final Map<String, URL> resources = new LinkedHashMap<String, URL>();

        Session(String id, String key, MediaItem item, String viewerId,
                TorBoxClient.BrowserStream stream) {
            this.id = id;
            this.key = key;
            this.item = item;
            this.viewerId = viewerId;
            this.stream = stream;
        }

        JSONObject toJson(double seek, String code) throws JSONException {
            String detail = stream.isTranscoding
                    ? "TorBox is converting this video for Chrome."
                    : "TorBox browser stream is ready.";
            if (stream.needsTranscoding && !stream.isTranscoding) {
                detail = "TorBox reported that this file may still need conversion.";
            }
            return new JSONObject()
                    .put("state", "READY")
                    .put("mode", "hls")
                    .put("provider", "TorBox browser stream")
                    .put("sessionId", id)
                    .put("playbackUrl", "/hls/" + id + "/index.m3u8?code=" + urlSegment(code))
                    .put("offsetSeconds", 0)
                    .put("seekSeconds", seek)
                    .put("durationSeconds", stream.durationSeconds)
                    .put("quality", stream.quality)
                    .put("videoCodec", stream.videoCodec)
                    .put("audioCodec", stream.audioCodec)
                    .put("isTranscoding", stream.isTranscoding)
                    .put("needsTranscoding", stream.needsTranscoding)
                    .put("message", detail);
        }

        JSONObject diagnostics() throws JSONException {
            return new JSONObject()
                    .put("id", id)
                    .put("mediaId", item.id)
                    .put("title", item.title)
                    .put("viewer", viewerId)
                    .put("quality", stream.quality)
                    .put("videoCodec", stream.videoCodec)
                    .put("audioCodec", stream.audioCodec)
                    .put("transcoding", stream.isTranscoding)
                    .put("resources", resources.size())
                    .put("ageSeconds", (System.currentTimeMillis() - createdMillis) / 1000L);
        }
    }
}
