package com.carstream.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TorBoxClient {
    private static final String BASE = "https://api.torbox.app/v1/api/torrents";
    private static final String STREAM_BASE = "https://api.torbox.app/v1/api/stream";
    private static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    private static final String CACHE_FILE = "torbox-library-cache.json";

    private final Context context;
    private final CellularNetworkProvider networks;
    private final SecureStore secureStore;
    private volatile List<MediaItem> current = Collections.emptyList();
    private volatile long lastRefreshMillis;
    private volatile boolean loadedFromCache;
    private final ConcurrentHashMap<String, CachedDownloadUrl> downloadUrlCache = new ConcurrentHashMap<String, CachedDownloadUrl>();
    private static final long DOWNLOAD_URL_TTL_MS = 8L * 60L * 1000L;

    public TorBoxClient(Context context, CellularNetworkProvider networks, SecureStore secureStore) {
        this.context = context.getApplicationContext();
        this.networks = networks;
        this.secureStore = secureStore;
        loadCache();
    }

    public synchronized List<MediaItem> refresh(boolean requireCellular) throws IOException, JSONException {
        EventLogger.info("TorBox", "Refreshing library; cellularRequired=" + requireCellular);
        String key = requireKey();
        HttpURLConnection connection = networks.open(
                new URL(BASE + "/mylist?bypass_cache=true"), requireCellular);
        configureJson(connection, key, "GET");
        JSONObject root = readJsonResponse(connection, "TorBox library");
        Object data = root.opt("data");
        JSONArray downloads = data instanceof JSONArray ? (JSONArray) data : new JSONArray();
        List<MediaItem> result = new ArrayList<MediaItem>();

        for (int i = 0; i < downloads.length(); i++) {
            JSONObject download = downloads.optJSONObject(i);
            if (download == null) continue;
            long torrentId = download.optLong("id", download.optLong("torrent_id", -1));
            String sourceName = firstNonBlank(download.optString("name"),
                    download.optString("short_name"), download.optString("title"),
                    download.optString("filename"), download.optString("original_name"),
                    torrentId >= 0 ? "TorBox download " + torrentId : "TorBox download");
            boolean ready = isDownloadReady(download);
            JSONArray files = download.optJSONArray("files");
            if (files == null) continue;

            for (int j = 0; j < files.length(); j++) {
                JSONObject file = files.optJSONObject(j);
                if (file == null) continue;
                long fileId = file.optLong("id", file.optLong("file_id", j));
                // Prefer whichever API field preserves folders. Older builds preferred
                // a short display name first, which could flatten the TorBox hierarchy.
                String path = mostPathLike(file.optString("absolute_path"),
                        file.optString("download_path"), file.optString("path"),
                        file.optString("file_path"), file.optString("filepath"),
                        file.optString("name"), file.optString("short_name"));
                if (!MediaItem.isPlayablePath(path)) continue;
                result.add(new MediaItem(torrentId + ":" + fileId, torrentId, fileId,
                        sourceName, MediaItem.displayName(path), path, file.optLong("size", 0),
                        MediaItem.mimeFor(path), ready));
            }
        }

        Collections.sort(result, new Comparator<MediaItem>() {
            @Override public int compare(MediaItem left, MediaItem right) {
                int source = left.displaySourceName().toLowerCase(Locale.US)
                        .compareTo(right.displaySourceName().toLowerCase(Locale.US));
                if (source != 0) return source;
                int path = left.relativePath().toLowerCase(Locale.US)
                        .compareTo(right.relativePath().toLowerCase(Locale.US));
                if (path != 0) return path;
                return left.title.toLowerCase(Locale.US).compareTo(right.title.toLowerCase(Locale.US));
            }
        });
        current = Collections.unmodifiableList(result);
        lastRefreshMillis = System.currentTimeMillis();
        loadedFromCache = false;
        saveCache();
        EventLogger.info("TorBox", "Library refresh completed with " + result.size() + " playable files");
        return current;
    }

    public List<MediaItem> currentLibrary() { return current; }
    public long getLastRefreshMillis() { return lastRefreshMillis; }
    public boolean isLoadedFromCache() { return loadedFromCache; }

    public MediaItem find(String id) {
        if (id == null) return null;
        for (MediaItem item : current) if (id.equals(item.id)) return item;
        return null;
    }

    public URL requestDownloadUrl(MediaItem item, boolean requireCellular) throws IOException, JSONException {
        if (item == null) throw new IOException("Missing media item");
        if (!item.ready) throw new IOException("This TorBox item is not download-ready yet");
        long now = System.currentTimeMillis();
        CachedDownloadUrl cached = downloadUrlCache.get(item.id);
        if (cached != null && cached.expiresAtMillis > now) {
            EventLogger.debug("TorBox", "Reusing a temporary streaming link for " + item.title);
            return cached.url;
        }
        String key = requireKey();
        String query = BASE + "/requestdl?token=" + encode(key)
                + "&torrent_id=" + item.torrentId
                + "&file_id=" + item.fileId
                + "&zip_link=false&redirect=false&append_name=true";
        HttpURLConnection connection = networks.open(new URL(query), requireCellular);
        configureJson(connection, key, "GET");
        JSONObject root = readJsonResponse(connection, "TorBox streaming link");
        String value = root.optString("data", "").trim();
        if (value.isEmpty()) throw new IOException("TorBox returned an empty streaming link");
        URL url = new URL(value);
        downloadUrlCache.put(item.id, new CachedDownloadUrl(url, now + DOWNLOAD_URL_TTL_MS));
        return url;
    }

    public BrowserStream createBrowserStream(MediaItem item, boolean requireCellular)
            throws IOException, JSONException {
        if (item == null) throw new IOException("Missing media item");
        if (!item.ready) throw new IOException("This TorBox item is not download-ready yet");
        BrowserStreamException firstFailure = null;
        try {
            // 720p is a practical browser target and TorBox does not upscale smaller files.
            return createBrowserStreamOnce(item, requireCellular, "4");
        } catch (BrowserStreamException error) {
            firstFailure = error;
            // TorBox documents raw browser playback for compatible files on non-Pro plans.
            // A second request at original resolution gives that path a chance before the
            // host falls back to the ordinary download stream.
            if (!"PLAN_RESTRICTED_FEATURE".equalsIgnoreCase(error.code)
                    && !"INVALID_OPTION".equalsIgnoreCase(error.code)) throw error;
        }
        try {
            return createBrowserStreamOnce(item, requireCellular, "null");
        } catch (BrowserStreamException secondFailure) {
            if (firstFailure != null && "PLAN_RESTRICTED_FEATURE".equalsIgnoreCase(firstFailure.code)) {
                throw firstFailure;
            }
            throw secondFailure;
        }
    }

    private BrowserStream createBrowserStreamOnce(MediaItem item, boolean requireCellular,
                                                    String resolution) throws IOException, JSONException {
        String key = requireKey();
        String query = STREAM_BASE + "/createstream?id=" + item.torrentId
                + "&file_id=" + item.fileId
                + "&type=torrent"
                + "&chosen_subtitle_index=null"
                + "&chosen_audio_index=0"
                + "&chosen_resolution_index=" + resolution;
        HttpURLConnection connection = networks.open(new URL(query), requireCellular);
        configureJson(connection, key, "GET");
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = IoUtils.readUtf8(stream, MAX_JSON_BYTES);
        connection.disconnect();

        JSONObject root;
        try { root = new JSONObject(body == null || body.trim().isEmpty() ? "{}" : body); }
        catch (JSONException malformed) {
            throw new BrowserStreamException("MALFORMED_RESPONSE",
                    "TorBox returned an unreadable browser-stream response", status, malformed);
        }
        String code = firstNonBlank(root.optString("error"), root.optString("code"));
        String detail = firstNonBlank(root.optString("detail"), root.optString("message"),
                status >= 400 ? "TorBox browser stream returned HTTP " + status : "TorBox browser stream failed");
        if (status < 200 || status >= 300 || !root.optBoolean("success", true)) {
            throw new BrowserStreamException(code.isEmpty() ? "HTTP_" + status : code,
                    detail, status, null);
        }
        JSONObject data = root.optJSONObject("data");
        if (data == null) throw new BrowserStreamException("EMPTY_STREAM_DATA",
                "TorBox did not return browser-stream data", status, null);
        String hls = data.optString("hls_url", "").trim();
        if (hls.isEmpty()) throw new BrowserStreamException("EMPTY_HLS_URL",
                "TorBox did not return an HLS address", status, null);

        JSONObject metadata = data.optJSONObject("metadata");
        JSONObject video = metadata == null ? null : metadata.optJSONObject("video");
        JSONArray audios = metadata == null ? null : metadata.optJSONArray("audios");
        JSONObject firstAudio = audios == null ? null : audios.optJSONObject(0);
        String videoCodec = video == null ? "" : video.optString("codec", "");
        String audioCodec = firstAudio == null ? "" : firstAudio.optString("codec", "");
        double duration = parseDouble(video == null ? "" : video.optString("duration", ""));
        int height = video == null ? 0 : video.optInt("height", 0);
        String quality = height > 0 ? height + "p" : ("4".equals(resolution) ? "Up to 720p" : "Original resolution");
        return new BrowserStream(new URL(hls), duration, videoCodec, audioCodec, quality,
                data.optBoolean("is_transcoding", false), data.optBoolean("needs_transcoding", false));
    }

    public void invalidateDownloadUrl(String mediaId) {
        if (mediaId != null) downloadUrlCache.remove(mediaId);
    }

    public String addMagnet(String magnet, boolean requireCellular) throws IOException, JSONException {
        String clean = magnet == null ? "" : magnet.trim();
        EventLogger.info("TorBox", "Submitting magnet fingerprint="
                + Integer.toHexString(clean.hashCode()) + "; cellularRequired=" + requireCellular);
        if (!clean.startsWith("magnet:?")) throw new IOException("Paste a complete magnet link");
        String key = requireKey();
        String boundary = "----CarStream" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection connection = networks.open(new URL(BASE + "/createtorrent"), requireCellular);
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Authorization", "Bearer " + key);
        connection.setRequestProperty("User-Agent", "CarStream/" + BuildInfo.VERSION_NAME + " Android");
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (DataOutputStream output = new DataOutputStream(connection.getOutputStream())) {
            writeField(output, boundary, "magnet", clean);
            writeField(output, boundary, "add_only_if_cached", "false");
            writeField(output, boundary, "allow_zip", "false");
            output.writeBytes("--" + boundary + "--\r\n");
        }

        JSONObject root = readJsonResponse(connection, "TorBox add torrent");
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            EventLogger.info("TorBox", "Magnet accepted by TorBox");
            return "Torrent submitted to TorBox";
        }
        long id = data.optLong("torrent_id", data.optLong("id", -1));
        EventLogger.info("TorBox", id >= 0 ? "Magnet accepted as torrent #" + id : "Magnet accepted by TorBox");
        return id >= 0 ? "Torrent submitted to TorBox as #" + id : "Torrent submitted to TorBox";
    }

    private void loadCache() {
        File file = new File(context.getFilesDir(), CACHE_FILE);
        if (!file.isFile()) return;
        try {
            String text = new String(IoUtils.readFile(file, MAX_JSON_BYTES), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(text);
            JSONArray items = root.optJSONArray("items");
            if (items == null) return;
            List<MediaItem> result = new ArrayList<MediaItem>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                result.add(new MediaItem(
                        item.optString("id", ""),
                        item.optLong("torrentId", -1),
                        item.optLong("fileId", -1),
                        item.optString("sourceName", item.optString("collection", "")),
                        item.optString("title", "Untitled video"),
                        item.optString("path", ""),
                        item.optLong("size", 0),
                        item.optString("mimeType", "video/mp4"),
                        item.optBoolean("ready", false)));
            }
            current = Collections.unmodifiableList(result);
            lastRefreshMillis = root.optLong("savedAt", file.lastModified());
            loadedFromCache = true;
            EventLogger.info("TorBox", "Loaded " + result.size() + " saved library files from this phone");
        } catch (Exception error) {
            EventLogger.warn("TorBox", "Could not read the saved TorBox library", error);
        }
    }

    private void saveCache() {
        File file = new File(context.getFilesDir(), CACHE_FILE);
        try {
            JSONArray items = new JSONArray();
            for (MediaItem item : current) items.put(item.toJson());
            JSONObject root = new JSONObject()
                    .put("savedAt", lastRefreshMillis)
                    .put("items", items);
            FileOutputStream output = new FileOutputStream(file, false);
            try {
                output.write(root.toString().getBytes(StandardCharsets.UTF_8));
                output.flush();
            } finally {
                output.close();
            }
        } catch (Exception error) {
            EventLogger.warn("TorBox", "Could not save the TorBox library for later browsing", error);
        }
    }

    private static void writeField(DataOutputStream output, String boundary, String name, String value)
            throws IOException {
        output.writeBytes("--" + boundary + "\r\n");
        output.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.writeBytes("\r\n");
    }

    private static boolean isDownloadReady(JSONObject download) {
        return download.optBoolean("download_present", false);
    }

    private static void configureJson(HttpURLConnection connection, String key, String method)
            throws IOException {
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(45_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + key);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "CarStream/" + BuildInfo.VERSION_NAME + " Android");
    }

    private static JSONObject readJsonResponse(HttpURLConnection connection, String operation)
            throws IOException, JSONException {
        int status = connection.getResponseCode();
        EventLogger.debug("TorBox", operation + " returned HTTP " + status);
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = IoUtils.readUtf8(stream, MAX_JSON_BYTES);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IOException(operation + " returned HTTP " + status + formatDetail(body));
        }
        JSONObject root = new JSONObject(body);
        if (!root.optBoolean("success", true)) {
            throw new IOException(firstNonBlank(root.optString("detail"),
                    root.optString("error"), operation + " failed"));
        }
        return root;
    }

    private String requireKey() throws IOException {
        String key = secureStore.loadTorBoxKey().trim();
        if (key.isEmpty()) throw new IOException("Save a TorBox API key in Settings first");
        return key;
    }

    private static String formatDetail(String body) {
        if (body == null || body.trim().isEmpty()) return "";
        String compact = body.trim().replace('\n', ' ').replace('\r', ' ');
        if (compact.length() > 240) compact = compact.substring(0, 240) + "...";
        return ": " + compact;
    }

    private static String mostPathLike(String... values) {
        String fallback = "";
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) continue;
            String clean = value.trim();
            if (fallback.isEmpty()) fallback = clean;
            if (clean.indexOf('/') >= 0 || clean.indexOf('\\') >= 0) return clean;
        }
        return fallback;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }

    private static double parseDouble(String value) {
        try {
            double parsed = Double.parseDouble(value == null ? "" : value.trim());
            return Double.isFinite(parsed) && parsed > 0 ? parsed : 0.0;
        } catch (Exception ignored) { return 0.0; }
    }

    public static final class BrowserStream {
        public final URL hlsUrl;
        public final double durationSeconds;
        public final String videoCodec;
        public final String audioCodec;
        public final String quality;
        public final boolean isTranscoding;
        public final boolean needsTranscoding;

        BrowserStream(URL hlsUrl, double durationSeconds, String videoCodec,
                      String audioCodec, String quality, boolean isTranscoding,
                      boolean needsTranscoding) {
            this.hlsUrl = hlsUrl;
            this.durationSeconds = durationSeconds;
            this.videoCodec = videoCodec == null ? "" : videoCodec;
            this.audioCodec = audioCodec == null ? "" : audioCodec;
            this.quality = quality == null ? "" : quality;
            this.isTranscoding = isTranscoding;
            this.needsTranscoding = needsTranscoding;
        }
    }

    public static final class BrowserStreamException extends IOException {
        public final String code;
        public final String detail;
        public final int httpStatus;

        BrowserStreamException(String code, String detail, int httpStatus, Throwable cause) {
            super((detail == null || detail.trim().isEmpty()) ? code : detail, cause);
            this.code = code == null ? "STREAM_ERROR" : code.trim();
            this.detail = detail == null ? "TorBox browser stream failed" : detail.trim();
            this.httpStatus = httpStatus;
        }
    }

    private static final class CachedDownloadUrl {
        final URL url;
        final long expiresAtMillis;
        CachedDownloadUrl(URL url, long expiresAtMillis) {
            this.url = url;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }
}
