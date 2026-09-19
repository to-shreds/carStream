package com.carstream.app;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class LocalHttpServer implements Closeable {
    public interface NetworkMode { boolean requireCellular(); }

    private static final int MAX_REQUEST_BODY = 256 * 1024;
    private static final int MAX_HEADER_LINE = 16 * 1024;
    private static final int STREAM_BUFFER = 128 * 1024;
    private static final String DAV_USER = "carstream";

    private final Context context;
    private final int port;
    private volatile String pairingCode;
    private final ClientRegistry clients;
    private final TorBoxClient torBox;
    private final CellularNetworkProvider networks;
    private final ClientBundleManager bundles;
    private final SkipMarkerResolver skipMarkerResolver;
    private final AppSettings settings;
    private final NetworkMode networkMode;
    private final ExecutorService workers;
    private final Set<String> loggedClientIds = Collections.synchronizedSet(new HashSet<String>());
    private final StreamRegistry streamRegistry = new StreamRegistry(StreamRegistry.DEFAULT_MAX_STREAMS);
    private final HlsScriptCache hlsScriptCache;
    private final HlsTranscodeManager hlsTranscodes;

    private volatile boolean open;
    private volatile String lastStreamTitle = "";
    private ServerSocket serverSocket;

    public LocalHttpServer(Context context, int port, String pairingCode, ClientRegistry clients,
                           TorBoxClient torBox, CellularNetworkProvider networks,
                           ClientBundleManager bundles) {
        this(context, port, pairingCode, clients, torBox, networks, bundles, null, null, null);
    }

    public LocalHttpServer(Context context, int port, String pairingCode, ClientRegistry clients,
                           TorBoxClient torBox, CellularNetworkProvider networks,
                           ClientBundleManager bundles, NetworkMode networkMode) {
        this(context, port, pairingCode, clients, torBox, networks, bundles, null, null, networkMode);
    }

    public LocalHttpServer(Context context, int port, String pairingCode, ClientRegistry clients,
                           TorBoxClient torBox, CellularNetworkProvider networks,
                           ClientBundleManager bundles, SkipMarkerResolver skipMarkerResolver,
                           AppSettings settings, NetworkMode networkMode) {
        this.context = context == null ? null : context.getApplicationContext();
        this.port = port;
        this.pairingCode = pairingCode == null ? "" : pairingCode;
        this.clients = clients;
        this.torBox = torBox;
        this.networks = networks;
        this.bundles = bundles;
        AppSettings effectiveSettings = settings == null && this.context != null ? new AppSettings(this.context) : settings;
        this.settings = effectiveSettings;
        if (skipMarkerResolver == null && this.context != null && effectiveSettings != null) {
            this.skipMarkerResolver = new SkipMarkerResolver(networks, new SkipMarkerStore(this.context), effectiveSettings);
        } else {
            this.skipMarkerResolver = skipMarkerResolver;
        }
        this.networkMode = networkMode;
        final AtomicInteger number = new AtomicInteger();
        ThreadFactory factory = new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "CarStream-http-" + number.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        workers = Executors.newCachedThreadPool(factory);
        hlsScriptCache = new HlsScriptCache(this.context, networks);
        hlsTranscodes = new HlsTranscodeManager(this.context, this.pairingCode, torBox, networkMode);
    }

    public synchronized void start() throws IOException {
        if (open) return;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("0.0.0.0", port));
        open = true;
        EventLogger.info(context, "Server", "Local HTTP, HLS, and WebDAV server listening on port " + port);
        hlsScriptCache.prefetch();
        workers.execute(new Runnable() {
            @Override public void run() { acceptLoop(); }
        });
    }

    public boolean isOpen() { return open; }
    public int getActiveStreamCount() { return streamRegistry.getActiveCount(); }
    public long getTotalBytesRelayed() { return streamRegistry.getTotalBytes(); }
    public String getLastStreamTitle() { return lastStreamTitle; }
    public StreamRegistry getStreamRegistry() { return streamRegistry; }

    public void setPairingCode(String value) {
        pairingCode = value == null ? "" : value;
        hlsTranscodes.setPairingCode(pairingCode);
    }

    private void acceptLoop() {
        while (open) {
            try {
                final Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(25_000);
                workers.execute(new Runnable() {
                    @Override public void run() { handle(socket); }
                });
            } catch (SocketException error) {
                if (open) close();
            } catch (IOException error) {
                if (open) close();
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket client = socket;
             BufferedInputStream input = new BufferedInputStream(client.getInputStream());
             BufferedOutputStream output = new BufferedOutputStream(client.getOutputStream())) {
            Request request = Request.read(input);
            if (request == null) return;
            request.clientAddress = client.getInetAddress() == null ? "unknown" : client.getInetAddress().getHostAddress();
            request.localAddress = client.getLocalAddress().getHostAddress();

            if (request.path.startsWith("/webdav") || request.path.startsWith("/dav")) {
                handleWebDav(output, request);
                return;
            }

            // Discovery is intentionally available before pairing. It reveals only that
            // a CarStream host is present, never the library or any TorBox information.
            if ("/api/ping".equals(request.path) && "GET".equals(request.method)) {
                writeJson(output, new JSONObject()
                        .put("app", "CarStream")
                        .put("version", BuildInfo.VERSION_NAME)
                        .put("port", port)
                        .put("requiresPairingCode", true), request.method);
                return;
            }

            if ("OPTIONS".equals(request.method)) {
                writeHeaders(output, 204, "No Content", "text/plain", 0, null);
                output.flush();
                return;
            }

            if (request.path.startsWith("/stremio/")) {
                handleStremio(output, request);
                return;
            }

            if ("/".equals(request.path) || "/index.html".equals(request.path)) {
                serveClientAsset(output, "index.html", "text/html; charset=utf-8", request.method);
                return;
            }
            if ("/app.js".equals(request.path)) {
                serveClientAsset(output, "app.js", "application/javascript; charset=utf-8", request.method);
                return;
            }
            if ("/styles.css".equals(request.path)) {
                serveClientAsset(output, "styles.css", "text/css; charset=utf-8", request.method);
                return;
            }
            if ("/hls.js".equals(request.path)) {
                serveHlsScript(output, request.method);
                return;
            }
            if ("/player.html".equals(request.path)) {
                serveRawAsset(output, "player/index.html", "text/html; charset=utf-8", request.method);
                return;
            }
            if ("/player.js".equals(request.path)) {
                serveRawAsset(output, "player/app.js", "application/javascript; charset=utf-8", request.method);
                return;
            }
            if ("/player.css".equals(request.path)) {
                serveRawAsset(output, "player/styles.css", "text/css; charset=utf-8", request.method);
                return;
            }
            if ("/favicon.ico".equals(request.path)) {
                writeHeaders(output, 204, "No Content", "image/x-icon", 0, null);
                output.flush();
                return;
            }
            if ("/api/ping".equals(request.path) && "GET".equals(request.method)) {
                writeJson(output, new JSONObject()
                        .put("ok", true)
                        .put("service", "CarStream")
                        .put("version", BuildInfo.VERSION_NAME)
                        .put("port", port), request.method);
                return;
            }

            if (!authorized(request)) {
                EventLogger.warn(context, "Server", "Rejected a request with an incorrect pairing code");
                writeText(output, 401, "The pairing code does not match the code shown on the phone.");
                return;
            }

            if (request.path.startsWith("/hls/")
                    && ("GET".equals(request.method) || "HEAD".equals(request.method))) {
                serveHlsOutput(output, request);
                return;
            }

            if ("/api/health".equals(request.path)) {
                writeJson(output, serverHealth(), request.method);
                return;
            }
            if ("/api/streams".equals(request.path) && "GET".equals(request.method)) {
                writeJson(output, streamRegistry.toJson(), request.method);
                return;
            }
            if ("/api/playback".equals(request.path) && "GET".equals(request.method)) {
                String mediaId = request.query.get("mediaId");
                MediaItem item = torBox.find(mediaId);
                double startAt = 0.0;
                try { startAt = Double.parseDouble(request.query.get("startAt")); }
                catch (Exception ignored) { }
                String viewerId = request.query.get("clientId");
                if (viewerId == null || viewerId.trim().length() == 0) viewerId = request.clientAddress;
                writeJson(output, hlsTranscodes.request(item, startAt, viewerId), request.method);
                return;
            }
            if ("/api/playback-stop".equals(request.path)
                    && ("POST".equals(request.method) || "GET".equals(request.method))) {
                String sessionId = request.query.get("sessionId");
                String viewerId = request.query.get("clientId");
                if (viewerId == null || viewerId.trim().length() == 0) viewerId = request.clientAddress;
                writeJson(output, hlsTranscodes.release(sessionId, viewerId), request.method);
                return;
            }
            if ("/api/library".equals(request.path) && "GET".equals(request.method)) {
                JSONArray items = new JSONArray();
                for (MediaItem item : torBox.currentLibrary()) items.put(item.toJson());
                writeJson(output, new JSONObject()
                        .put("items", items)
                        .put("clientVersion", bundles.getVersion())
                        .put("activeStreams", streamRegistry.getActiveCount())
                        .put("maximumStreams", streamRegistry.getMaximum()), request.method);
                return;
            }
            if ("/api/skip-markers".equals(request.path) && "GET".equals(request.method)) {
                String mediaId = request.query.get("mediaId");
                MediaItem item = torBox.find(mediaId);
                if (item == null) {
                    writeText(output, 404, "That video is no longer in the current TorBox library.");
                    return;
                }
                boolean force = "1".equals(request.query.get("refresh"))
                        || "true".equalsIgnoreCase(request.query.get("refresh"));
                SkipMarkerResolver.Resolution resolution = skipMarkerResolver == null
                        ? new SkipMarkerResolver.Resolution(EpisodeIdentity.parse(item),
                            Collections.<SkipSegment>emptyList(), "",
                            "Skip-marker lookup is unavailable in this build.", "")
                        : skipMarkerResolver.resolve(item,
                            networkMode != null && networkMode.requireCellular(), force);
                JSONObject response = resolution.toJson()
                        .put("mediaId", item.id)
                        .put("settings", skipSettingsJson())
                        .put("nextMedia", nextReadyInFolder(item) == null
                                ? JSONObject.NULL : nextReadyInFolder(item).toJson());
                writeJson(output, response, request.method);
                return;
            }
            if ("/api/state".equals(request.path) && "POST".equals(request.method)) {
                JSONObject telemetry = new JSONObject(new String(request.body, StandardCharsets.UTF_8));
                long seen = telemetry.optLong("commandVersion", 0);
                ClientSession session = clients.update(telemetry);
                if (session == null) {
                    writeText(output, 400, "Missing clientId");
                    return;
                }
                if (loggedClientIds.add(session.id)) {
                    EventLogger.info(context, "Client", "Screen connected: " + safeClientName(session.name));
                }
                JSONObject response = clients.responseFor(session, seen)
                        .put("activeStreams", streamRegistry.getActiveCount())
                        .put("maximumStreams", streamRegistry.getMaximum());
                writeJson(output, response, request.method);
                return;
            }
            if (request.path.startsWith("/stream/")
                    && ("GET".equals(request.method) || "HEAD".equals(request.method))) {
                stream(output, request, decode(request.path.substring("/stream/".length())));
                return;
            }

            writeText(output, 404, "Not found");
        } catch (Exception error) {
            if (!isExpectedDisconnect(error)) {
                EventLogger.error(context, "Server", "Local request failed: " + safeMessage(error), error);
            }
        }
    }


    private JSONObject skipSettingsJson() throws JSONException {
        AppSettings value = settings;
        if (value == null) {
            return new JSONObject().put("intro", AppSettings.SKIP_BUTTON)
                    .put("recap", AppSettings.SKIP_BUTTON)
                    .put("credits", AppSettings.SKIP_BUTTON)
                    .put("countdownSeconds", 5);
        }
        return new JSONObject()
                .put("intro", value.getIntroSkipMode())
                .put("recap", value.getRecapSkipMode())
                .put("credits", value.getCreditsSkipMode())
                .put("countdownSeconds", value.getSkipCountdownSeconds());
    }

    private void handleStremio(OutputStream output, Request request) throws Exception {
        String[] path = request.rawPath.split("/", -1);
        if (settings == null || path.length < 4
                || !IoUtils.constantTimeEquals(settings.getStremioToken(), decode(path[2]))) {
            writeText(output, 401, "This CarStream link has expired. Scan Stremio setup on the phone again.");
            return;
        }
        if (!"GET".equals(request.method) && !"HEAD".equals(request.method)) {
            writeText(output, 405, "Use GET or HEAD");
            return;
        }
        String host = request.localAddress;
        if (host.contains(":")) host = "[" + host + "]";
        // Build addresses from the accepted socket, never an untrusted Host header.
        String base = "http://" + host + ":" + port + "/stremio/" + settings.getStremioToken() + "/";
        StremioAddon addon = new StremioAddon(torBox.currentLibrary(), base);
        if (path.length == 4 && "setup".equals(path[3])) {
            serveRawAsset(output, "stremio/setup.html", "text/html; charset=utf-8", request.method);
            return;
        }
        if (path.length == 4 && "manifest.json".equals(path[3])) {
            writeJson(output, addon.manifest(), request.method);
            return;
        }
        if (path.length == 5 && "poster".equals(path[3]) && path[4].endsWith(".png")) {
            String title = addon.posterTitle(decode(path[4].substring(0, path[4].length() - 4)));
            if (title == null) { writeText(output, 404, "No such download"); return; }
            byte[] png = StremioPoster.render(title);
            writeHeaders(output, 200, "OK", "image/png", png.length, null);
            if (!"HEAD".equals(request.method)) output.write(png);
            output.flush();
            return;
        }
        if (path.length == 6 && "play".equals(path[3])) {
            String mediaId = addon.mediaId(decode(path[4]));
            if (mediaId == null) { writeText(output, 404, "This video is not ready. Refresh Library on the phone."); return; }
            stream(output, request, mediaId);
            return;
        }
        if ((path.length == 6 || path.length == 7) && path[path.length - 1].endsWith(".json")) {
            String resource = path[3], type = decode(path[4]);
            String id = decode(path.length == 6 ? path[5].substring(0, path[5].length() - 5) : path[5]);
            if ("catalog".equals(resource)) {
                Map<String, String> extras = new HashMap<String, String>();
                if (path.length == 7) Request.parseQuery(path[6].substring(0, path[6].length() - 5), extras);
                writeJson(output, addon.catalog(type, id, extras.get("search")), request.method);
                return;
            }
            if (path.length == 6 && "meta".equals(resource)) {
                writeJson(output, addon.meta(type, id), request.method);
                return;
            }
            if (path.length == 6 && "stream".equals(resource)) {
                writeJson(output, addon.streams(type, id), request.method);
                return;
            }
        }
        writeText(output, 404, "Unknown CarStream add-on resource");
    }

    private MediaItem nextReadyInFolder(MediaItem current) {
        if (current == null) return null;
        List<MediaItem> folder = new ArrayList<MediaItem>();
        for (MediaItem item : torBox.currentLibrary()) {
            if (item.ready && item.torrentId == current.torrentId
                    && item.folderPath().equalsIgnoreCase(current.folderPath())) folder.add(item);
        }
        Collections.sort(folder, new Comparator<MediaItem>() {
            @Override public int compare(MediaItem left, MediaItem right) {
                return naturalCompare(left.fileName(), right.fileName());
            }
        });
        for (int i = 0; i < folder.size(); i++) {
            if (folder.get(i).id.equals(current.id)) return i + 1 < folder.size() ? folder.get(i + 1) : null;
        }
        return null;
    }

    private JSONObject serverHealth() throws JSONException {
        return new JSONObject()
                .put("ok", true)
                .put("serverTime", System.currentTimeMillis())
                .put("activeStreams", streamRegistry.getActiveCount())
                .put("maximumStreams", streamRegistry.getMaximum())
                .put("totalBytesRelayed", streamRegistry.getTotalBytes())
                .put("streams", streamRegistry.toJson())
                .put("browserCompatibility", hlsTranscodes.diagnostics())
                .put("hlsScript", hlsScriptCache.getStatus())
                .put("route", networks.getRouteDescription())
                .put("webDav", true);
    }

    private boolean authorized(Request request) {
        String candidate = request.query.get("code");
        if (candidate == null) candidate = request.query.get("token");
        if (candidate == null) candidate = request.headers.get("x-carstream-code");
        if (candidate == null) candidate = request.headers.get("x-carstream-token");
        return IoUtils.constantTimeEquals(pairingCode, candidate);
    }

    private boolean webDavAuthorized(Request request) {
        if (authorized(request)) return true;
        String authorization = request.headers.get("authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) return false;
        try {
            String decoded = new String(Base64.decode(authorization.substring(6).trim(), Base64.DEFAULT),
                    StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon < 0) return false;
            String user = decoded.substring(0, colon);
            String password = decoded.substring(colon + 1);
            return IoUtils.constantTimeEquals(DAV_USER, user)
                    && IoUtils.constantTimeEquals(pairingCode, password);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void serveClientAsset(OutputStream output, String name, String type, String method) throws IOException {
        byte[] data = bundles.getAsset(name);
        writeHeaders(output, 200, "OK", type, data.length, null);
        if (!"HEAD".equals(method)) output.write(data);
        output.flush();
    }

    private void serveRawAsset(OutputStream output, String name, String type, String method) throws IOException {
        if (context == null) throw new IOException("Application assets are unavailable");
        byte[] data;
        InputStream input = context.getAssets().open(name);
        try { data = IoUtils.readAll(input, 2 * 1024 * 1024); }
        finally { input.close(); }
        writeHeaders(output, 200, "OK", type, data.length, null);
        if (!"HEAD".equals(method)) output.write(data);
        output.flush();
    }

    private void serveHlsScript(OutputStream output, String method) throws IOException {
        byte[] data;
        try { data = hlsScriptCache.getScript(); }
        catch (Exception error) {
            EventLogger.warn(context, "Browser", "Could not serve hls.js: " + safeMessage(error), error);
            data = HlsScriptCache.failureScript(safeMessage(error));
        }
        writeHeaders(output, 200, "OK", "application/javascript; charset=utf-8", data.length, null);
        if (!"HEAD".equals(method)) output.write(data);
        output.flush();
    }

    private void serveHlsOutput(OutputStream output, Request request) throws IOException {
        String remainder = request.path.substring("/hls/".length());
        int slash = remainder.indexOf('/');
        if (slash <= 0 || slash >= remainder.length() - 1) {
            writeText(output, 404, "HLS resource not found");
            return;
        }
        String sessionId = remainder.substring(0, slash);
        String resourcePath = remainder.substring(slash + 1);
        URL upstreamUrl;
        if ("index.m3u8".equals(resourcePath)) {
            upstreamUrl = hlsTranscodes.initialPlaylist(sessionId);
        } else if (resourcePath.startsWith("r/") && resourcePath.length() > 2) {
            upstreamUrl = hlsTranscodes.resource(sessionId, resourcePath.substring(2));
        } else upstreamUrl = null;
        if (upstreamUrl == null) {
            writeText(output, 404, "This browser stream expired. Pick the video again.");
            return;
        }

        boolean requireCellular = networkMode != null && networkMode.requireCellular();
        String range = request.headers.get("range");
        HttpURLConnection upstream = null;
        try {
            upstream = openUpstream(upstreamUrl, range, request.method, requireCellular);
            int status = upstream.getResponseCode();
            if (status >= 400) {
                String detail = IoUtils.readUtf8(upstream.getErrorStream(), 128 * 1024);
                EventLogger.warn(context, "Browser", "TorBox HLS resource returned HTTP " + status);
                writeText(output, status == 404 ? 404 : 502,
                        "TorBox browser stream failed with HTTP " + status
                                + (detail.isEmpty() ? "" : ": " + detail));
                return;
            }

            String contentType = upstream.getContentType();
            URL finalUrl = upstream.getURL();
            boolean playlist = isPlaylist(contentType, finalUrl);
            if (playlist) {
                String body = IoUtils.readUtf8(upstream.getInputStream(), 4 * 1024 * 1024);
                String rewritten = rewriteHlsPlaylist(sessionId, finalUrl, body);
                byte[] data = rewritten.getBytes(StandardCharsets.UTF_8);
                LinkedHashMap<String, String> extra = new LinkedHashMap<String, String>();
                extra.put("Access-Control-Expose-Headers", "Content-Length, Content-Type");
                writeHeaders(output, 200, "OK",
                        "application/vnd.apple.mpegurl; charset=utf-8", data.length, extra);
                if (!"HEAD".equals(request.method)) output.write(data);
                output.flush();
                return;
            }

            LinkedHashMap<String, String> extra = new LinkedHashMap<String, String>();
            copyHeader(upstream, extra, "Content-Range");
            copyHeader(upstream, extra, "Accept-Ranges");
            copyHeader(upstream, extra, "ETag");
            copyHeader(upstream, extra, "Last-Modified");
            extra.put("Access-Control-Expose-Headers", "Content-Length, Content-Type, Content-Range, Accept-Ranges");
            if (contentType == null || contentType.trim().isEmpty()) contentType = "application/octet-stream";
            long contentLength = upstream.getHeaderFieldLong("Content-Length", -1L);
            writeHeaders(output, status, reason(status, upstream.getResponseMessage()),
                    contentType, contentLength, extra);
            if (!"HEAD".equals(request.method)) {
                InputStream source = upstream.getInputStream();
                try {
                    byte[] buffer = new byte[STREAM_BUFFER];
                    int count;
                    while ((count = source.read(buffer)) != -1) output.write(buffer, 0, count);
                } finally { source.close(); }
            }
            output.flush();
        } finally {
            if (upstream != null) upstream.disconnect();
        }
    }

    private String rewriteHlsPlaylist(String sessionId, URL baseUrl, String playlist) throws IOException {
        if (playlist == null) return "";
        String[] lines = playlist.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder result = new StringBuilder(playlist.length() + 1024);
        for (String line : lines) {
            String trimmed = line.trim();
            String rewritten = line;
            if (trimmed.startsWith("#")) {
                rewritten = rewriteUriAttributes(sessionId, baseUrl, line);
            } else if (!trimmed.isEmpty()) {
                rewritten = localHlsResource(sessionId, new URL(baseUrl, trimmed));
            }
            result.append(rewritten).append('\n');
        }
        return result.toString();
    }

    private String rewriteUriAttributes(String sessionId, URL baseUrl, String line) throws IOException {
        StringBuilder output = new StringBuilder(line.length() + 64);
        int position = 0;
        while (position < line.length()) {
            int marker = line.indexOf("URI=\"", position);
            if (marker < 0) {
                output.append(line.substring(position));
                break;
            }
            int valueStart = marker + 5;
            int valueEnd = line.indexOf('"', valueStart);
            if (valueEnd < 0) {
                output.append(line.substring(position));
                break;
            }
            output.append(line.substring(position, valueStart));
            String value = line.substring(valueStart, valueEnd);
            output.append(localHlsResource(sessionId, new URL(baseUrl, value)));
            position = valueEnd;
        }
        return output.toString();
    }

    private String localHlsResource(String sessionId, URL upstream) throws IOException {
        String token = hlsTranscodes.registerResource(sessionId, upstream);
        if (token == null) throw new IOException("TorBox returned an unexpected HLS resource host");
        return "/hls/" + sessionId + "/r/" + token + "?code=" + urlSegment(pairingCode);
    }

    private static boolean isPlaylist(String contentType, URL url) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.US);
        String path = url == null || url.getPath() == null ? "" : url.getPath().toLowerCase(Locale.US);
        return type.contains("mpegurl") || type.contains("m3u8") || path.endsWith(".m3u8");
    }

    private void writeJson(OutputStream output, JSONObject value, String method) throws IOException {
        byte[] data = value.toString().getBytes(StandardCharsets.UTF_8);
        writeHeaders(output, 200, "OK", "application/json; charset=utf-8", data.length, null);
        if (!"HEAD".equals(method)) output.write(data);
        output.flush();
    }

    private void stream(OutputStream output, Request request, String id) throws Exception {
        MediaItem item = torBox.find(id);
        if (item == null) {
            writeText(output, 404, "Media item was not found. Refresh the TorBox library on the phone.");
            return;
        }
        StreamRegistry.Ticket ticket = streamRegistry.tryStart(item, request.clientAddress);
        if (ticket == null) {
            writeText(output, 503, "CarStream is already serving the maximum number of simultaneous videos. Stop one screen and try again.");
            return;
        }
        lastStreamTitle = item.title;
        long transferred = 0L;
        EventLogger.info(context, "Stream", "Stream started (" + streamRegistry.getActiveCount()
                + " active of " + streamRegistry.getMaximum() + "): " + item.title);
        HttpURLConnection upstream = null;
        try {
            boolean requireCellular = networkMode != null && networkMode.requireCellular();
            String range = request.headers.get("range");
            upstream = openMediaUpstream(item, range, request.method, requireCellular);
            int status = upstream.getResponseCode();
            if (status >= 400) {
                String detail = IoUtils.readUtf8(upstream.getErrorStream(), 128 * 1024);
                EventLogger.warn(context, "Stream", "TorBox upstream returned HTTP " + status + " for " + item.title);
                writeText(output, 502, "TorBox stream failed with HTTP " + status
                        + (detail.isEmpty() ? "" : ": " + detail));
                return;
            }

            LinkedHashMap<String, String> extra = new LinkedHashMap<String, String>();
            copyHeader(upstream, extra, "Content-Range");
            copyHeader(upstream, extra, "Accept-Ranges");
            copyHeader(upstream, extra, "ETag");
            copyHeader(upstream, extra, "Last-Modified");
            if (!extra.containsKey("Accept-Ranges")) extra.put("Accept-Ranges", "bytes");
            extra.put("Content-Disposition", "inline; filename*=UTF-8''" + urlSegment(item.fileName()));

            String contentType = upstream.getContentType();
            if (contentType == null || contentType.trim().isEmpty()
                    || "application/octet-stream".equalsIgnoreCase(contentType.trim())) {
                contentType = item.mimeType;
            }
            long contentLength = upstream.getHeaderFieldLong("Content-Length", -1L);
            writeHeaders(output, status, reason(status, upstream.getResponseMessage()),
                    contentType, contentLength, extra);

            if (!"HEAD".equals(request.method)) {
                try (InputStream source = upstream.getInputStream()) {
                    byte[] buffer = new byte[STREAM_BUFFER];
                    int count;
                    int sinceFlush = 0;
                    while ((count = source.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        transferred += count;
                        ticket.addBytes(count);
                        sinceFlush += count;
                        if (sinceFlush >= 512 * 1024) {
                            output.flush();
                            sinceFlush = 0;
                        }
                    }
                }
            }
            output.flush();
        } finally {
            if (upstream != null) upstream.disconnect();
            ticket.close();
            EventLogger.info(context, "Stream", "Stream ended (" + streamRegistry.getActiveCount() + " active, "
                    + transferred + " bytes): " + item.title);
        }
    }

    private HttpURLConnection openMediaUpstream(MediaItem item, String range, String method,
                                                 boolean requireCellular) throws Exception {
        URL downloadUrl = torBox.requestDownloadUrl(item, requireCellular);
        HttpURLConnection connection = openUpstream(downloadUrl, range, method, requireCellular);
        int status = connection.getResponseCode();
        if (status == 401 || status == 403 || status == 410) {
            connection.disconnect();
            torBox.invalidateDownloadUrl(item.id);
            EventLogger.info(context, "Stream", "Temporary TorBox link expired; requesting a fresh link");
            downloadUrl = torBox.requestDownloadUrl(item, requireCellular);
            connection = openUpstream(downloadUrl, range, method, requireCellular);
        }
        return connection;
    }

    private HttpURLConnection openUpstream(URL initialUrl, String range, String method,
                                           boolean requireCellular) throws IOException {
        URL url = initialUrl;
        for (int redirect = 0; redirect < 8; redirect++) {
            HttpURLConnection connection = networks.open(url, requireCellular);
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(60_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("User-Agent", "CarStream/" + BuildInfo.VERSION_NAME + " Android");
            connection.setRequestProperty("Accept", "*/*");
            if (range != null && !range.trim().isEmpty()) connection.setRequestProperty("Range", range);
            int status = connection.getResponseCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.trim().isEmpty()) {
                    throw new IOException("Upstream redirect did not include a Location header");
                }
                url = new URL(url, location);
                continue;
            }
            return connection;
        }
        throw new IOException("Too many upstream redirects");
    }

    /* -------------------- Read-only WebDAV -------------------- */

    private void handleWebDav(OutputStream output, Request request) throws Exception {
        if ("OPTIONS".equals(request.method)) {
            LinkedHashMap<String, String> extra = new LinkedHashMap<String, String>();
            extra.put("DAV", "1");
            extra.put("Allow", "OPTIONS, PROPFIND, GET, HEAD");
            extra.put("MS-Author-Via", "DAV");
            writeHeaders(output, 200, "OK", "text/plain", 0, extra);
            output.flush();
            return;
        }
        if (!webDavAuthorized(request)) {
            LinkedHashMap<String, String> extra = new LinkedHashMap<String, String>();
            extra.put("WWW-Authenticate", "Basic realm=\"CarStream\"");
            extra.put("DAV", "1");
            byte[] data = "Enter the CarStream WebDAV username and pairing code.".getBytes(StandardCharsets.UTF_8);
            writeHeaders(output, 401, "Unauthorized", "text/plain; charset=utf-8", data.length, extra);
            output.write(data);
            output.flush();
            return;
        }

        DavNode node = resolveDavNode(request.path);
        if (node == null) {
            writeText(output, 404, "WebDAV item not found");
            return;
        }
        if ("PROPFIND".equals(request.method)) {
            int depth = "0".equals(request.headers.get("depth")) ? 0 : 1;
            writeDavProperties(output, node, depth);
            return;
        }
        if (("GET".equals(request.method) || "HEAD".equals(request.method)) && !node.collection) {
            stream(output, request, node.item.id);
            return;
        }
        if (("GET".equals(request.method) || "HEAD".equals(request.method)) && node.collection) {
            writeDavHtmlListing(output, node, request.method);
            return;
        }
        LinkedHashMap<String, String> extra = new LinkedHashMap<String, String>();
        extra.put("Allow", "OPTIONS, PROPFIND, GET, HEAD");
        writeHeaders(output, 405, "Method Not Allowed", "text/plain", 0, extra);
        output.flush();
    }

    private DavNode resolveDavNode(String requestPath) {
        String value = requestPath == null ? "" : requestPath;
        String prefix = value.startsWith("/webdav") ? "/webdav" : (value.startsWith("/dav") ? "/dav" : "");
        String suffix = prefix.isEmpty() ? value : value.substring(prefix.length());
        suffix = MediaItem.normalizePath(suffix);
        if (suffix.isEmpty()) return DavNode.root();
        String[] segments = suffix.split("/");
        if (segments.length == 0 || !segments[0].startsWith("t-")) return null;
        long torrentId;
        try { torrentId = Long.parseLong(segments[0].substring(2)); }
        catch (Exception ignored) { return null; }

        List<MediaItem> inTorrent = itemsForTorrent(torrentId);
        if (inTorrent.isEmpty()) return null;
        if (segments.length == 1) {
            return DavNode.collection(torrentId, "", inTorrent.get(0).displaySourceName());
        }
        StringBuilder relative = new StringBuilder();
        for (int i = 1; i < segments.length; i++) {
            if (i > 1) relative.append('/');
            relative.append(segments[i]);
        }
        String path = MediaItem.normalizePath(relative.toString());
        for (MediaItem item : inTorrent) {
            if (item.relativePath().equals(path)) return DavNode.file(item);
        }
        String childPrefix = path + "/";
        for (MediaItem item : inTorrent) {
            if (item.relativePath().startsWith(childPrefix)) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                return DavNode.collection(torrentId, path, name);
            }
        }
        return null;
    }

    private List<DavNode> davChildren(DavNode parent) {
        List<DavNode> result = new ArrayList<DavNode>();
        if (parent.root) {
            LinkedHashMap<Long, MediaItem> groups = new LinkedHashMap<Long, MediaItem>();
            for (MediaItem item : torBox.currentLibrary()) {
                if (!groups.containsKey(item.torrentId)) groups.put(item.torrentId, item);
            }
            for (Map.Entry<Long, MediaItem> entry : groups.entrySet()) {
                result.add(DavNode.collection(entry.getKey(), "", entry.getValue().displaySourceName()));
            }
        } else if (parent.collection) {
            String prefix = parent.relativePath.isEmpty() ? "" : parent.relativePath + "/";
            LinkedHashMap<String, DavNode> folders = new LinkedHashMap<String, DavNode>();
            LinkedHashMap<String, DavNode> files = new LinkedHashMap<String, DavNode>();
            for (MediaItem item : itemsForTorrent(parent.torrentId)) {
                String relative = item.relativePath();
                if (!prefix.isEmpty() && !relative.startsWith(prefix)) continue;
                String remainder = prefix.isEmpty() ? relative : relative.substring(prefix.length());
                if (remainder.isEmpty()) continue;
                int slash = remainder.indexOf('/');
                if (slash >= 0) {
                    String child = remainder.substring(0, slash);
                    String childPath = prefix + child;
                    if (!folders.containsKey(child)) {
                        folders.put(child, DavNode.collection(parent.torrentId, childPath, child));
                    }
                } else {
                    files.put(remainder, DavNode.file(item));
                }
            }
            result.addAll(folders.values());
            result.addAll(files.values());
        }
        Collections.sort(result, new Comparator<DavNode>() {
            @Override public int compare(DavNode left, DavNode right) {
                if (left.collection != right.collection) return left.collection ? -1 : 1;
                return naturalCompare(left.displayName, right.displayName);
            }
        });
        return result;
    }

    private List<MediaItem> itemsForTorrent(long torrentId) {
        List<MediaItem> result = new ArrayList<MediaItem>();
        for (MediaItem item : torBox.currentLibrary()) if (item.torrentId == torrentId) result.add(item);
        return result;
    }

    private void writeDavProperties(OutputStream output, DavNode node, int depth) throws IOException {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
                .append("<D:multistatus xmlns:D=\"DAV:\">");
        appendDavResponse(xml, node);
        if (depth > 0 && node.collection) {
            for (DavNode child : davChildren(node)) appendDavResponse(xml, child);
        }
        xml.append("</D:multistatus>");
        byte[] data = xml.toString().getBytes(StandardCharsets.UTF_8);
        LinkedHashMap<String, String> extra = new LinkedHashMap<String, String>();
        extra.put("DAV", "1");
        writeHeaders(output, 207, "Multi-Status", "application/xml; charset=utf-8", data.length, extra);
        output.write(data);
        output.flush();
    }

    private void appendDavResponse(StringBuilder xml, DavNode node) {
        xml.append("<D:response><D:href>").append(xmlEscape(node.href())).append("</D:href>")
                .append("<D:propstat><D:prop>")
                .append("<D:displayname>").append(xmlEscape(node.displayName)).append("</D:displayname>")
                .append("<D:resourcetype>");
        if (node.collection) xml.append("<D:collection/>");
        xml.append("</D:resourcetype>")
                .append("<D:getlastmodified>").append(xmlEscape(httpDate())).append("</D:getlastmodified>");
        if (!node.collection && node.item != null) {
            xml.append("<D:getcontentlength>").append(Math.max(0L, node.item.size)).append("</D:getcontentlength>")
                    .append("<D:getcontenttype>").append(xmlEscape(node.item.mimeType)).append("</D:getcontenttype>")
                    .append("<D:getetag>\"").append(xmlEscape(node.item.id)).append('-')
                    .append(Math.max(0L, node.item.size)).append("\"</D:getetag>");
        }
        xml.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>");
    }

    private void writeDavHtmlListing(OutputStream output, DavNode node, String method) throws IOException {
        StringBuilder html = new StringBuilder("<!doctype html><meta charset=\"utf-8\"><title>CarStream</title>")
                .append("<style>body{font:18px system-ui;background:#101722;color:white;padding:18px}a{display:block;color:#a9c0ff;padding:12px;border-bottom:1px solid #334}small{color:#aeb8cb}</style>")
                .append("<h1>").append(xmlEscape(node.displayName)).append("</h1>")
                .append("<small>Read-only CarStream WebDAV library</small>");
        for (DavNode child : davChildren(node)) {
            html.append("<a href=\"").append(child.href()).append("\">")
                    .append(child.collection ? "Folder: " : "Play: ")
                    .append(xmlEscape(child.displayName)).append("</a>");
        }
        byte[] data = html.toString().getBytes(StandardCharsets.UTF_8);
        writeHeaders(output, 200, "OK", "text/html; charset=utf-8", data.length, null);
        if (!"HEAD".equals(method)) output.write(data);
        output.flush();
    }

    private static String httpDate() {
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        return format.format(new Date());
    }

    private static String xmlEscape(String value) {
        String clean = value == null ? "" : value;
        return clean.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String urlSegment(String value) {
        try { return URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20"); }
        catch (Exception ignored) { return value == null ? "" : value; }
    }

    private static final class DavNode {
        final boolean root;
        final boolean collection;
        final long torrentId;
        final String relativePath;
        final String displayName;
        final MediaItem item;

        private DavNode(boolean root, boolean collection, long torrentId, String relativePath,
                        String displayName, MediaItem item) {
            this.root = root;
            this.collection = collection;
            this.torrentId = torrentId;
            this.relativePath = relativePath == null ? "" : MediaItem.normalizePath(relativePath);
            this.displayName = displayName == null || displayName.trim().isEmpty() ? "CarStream" : displayName;
            this.item = item;
        }

        static DavNode root() { return new DavNode(true, true, -1, "", "CarStream", null); }
        static DavNode collection(long torrentId, String relative, String name) {
            return new DavNode(false, true, torrentId, relative, name, null);
        }
        static DavNode file(MediaItem item) {
            return new DavNode(false, false, item.torrentId, item.relativePath(), item.fileName(), item);
        }
        String href() {
            if (root) return "/dav/";
            StringBuilder value = new StringBuilder("/dav/t-").append(torrentId).append('/');
            if (!relativePath.isEmpty()) {
                String[] parts = relativePath.split("/");
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) value.append('/');
                    value.append(urlSegment(parts[i]));
                }
                if (collection) value.append('/');
            }
            return value.toString();
        }
    }

    private static void copyHeader(HttpURLConnection connection, Map<String, String> target, String name) {
        String value = connection.getHeaderField(name);
        if (value != null && !value.trim().isEmpty()) target.put(name, value);
    }

    private static void writeText(OutputStream output, int status, String value) throws IOException {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        writeHeaders(output, status, reason(status, null), "text/plain; charset=utf-8", data.length, null);
        output.write(data);
        output.flush();
    }

    private static void writeHeaders(OutputStream output, int status, String reason,
                                     String contentType, long contentLength,
                                     Map<String, String> extra) throws IOException {
        StringBuilder header = new StringBuilder()
                .append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n")
                .append("Connection: close\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("X-Content-Type-Options: nosniff\r\n")
                .append("Access-Control-Allow-Origin: *\r\n")
                .append("Access-Control-Allow-Headers: Content-Type, X-CarStream-Code, X-CarStream-Token, Range, Authorization, Depth\r\n")
                .append("Access-Control-Allow-Methods: GET, HEAD, POST, OPTIONS, PROPFIND\r\n");
        if (contentLength >= 0) header.append("Content-Length: ").append(contentLength).append("\r\n");
        if (extra != null) {
            for (Map.Entry<String, String> entry : extra.entrySet()) {
                header.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
        }
        header.append("\r\n");
        output.write(header.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String reason(int status, String upstreamReason) {
        if (upstreamReason != null && !upstreamReason.trim().isEmpty()) return upstreamReason;
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 207: return "Multi-Status";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 416: return "Range Not Satisfiable";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            default: return "Response";
        }
    }

    @Override public synchronized void close() {
        if (open) EventLogger.info(context, "Server", "Local HTTP and WebDAV server stopped");
        open = false;
        if (serverSocket != null) {
            try { serverSocket.close(); }
            catch (IOException ignored) { }
        }
        serverSocket = null;
        try { hlsTranscodes.close(); } catch (Exception ignored) { }
        workers.shutdownNow();
    }

    private static String decode(String value) {
        try { return URLDecoder.decode(value, "UTF-8"); }
        catch (Exception ignored) { return value; }
    }

    private static boolean isExpectedDisconnect(Throwable error) {
        if (error instanceof SocketException) return true;
        String value = safeMessage(error).toLowerCase(Locale.US);
        return value.contains("broken pipe") || value.contains("connection reset")
                || value.contains("socket closed") || value.contains("connection aborted");
    }

    private static String safeClientName(String value) {
        String clean = value == null ? "Screen" : value.trim().replace('\n', ' ').replace('\r', ' ');
        if (clean.length() == 0) clean = "Screen";
        return clean.length() > 60 ? clean.substring(0, 60) : clean;
    }

    private static String safeMessage(Throwable error) {
        String value = error == null ? null : error.getMessage();
        return value == null || value.trim().length() == 0
                ? (error == null ? "unknown error" : error.getClass().getSimpleName())
                : value.trim();
    }

    private static int naturalCompare(String left, String right) {
        String a = left == null ? "" : left.toLowerCase(Locale.US);
        String b = right == null ? "" : right.toLowerCase(Locale.US);
        int ai = 0;
        int bi = 0;
        while (ai < a.length() && bi < b.length()) {
            char ac = a.charAt(ai);
            char bc = b.charAt(bi);
            if (Character.isDigit(ac) && Character.isDigit(bc)) {
                long an = 0;
                long bn = 0;
                while (ai < a.length() && Character.isDigit(a.charAt(ai))) {
                    an = Math.min(Long.MAX_VALUE / 10, an) * 10 + (a.charAt(ai++) - '0');
                }
                while (bi < b.length() && Character.isDigit(b.charAt(bi))) {
                    bn = Math.min(Long.MAX_VALUE / 10, bn) * 10 + (b.charAt(bi++) - '0');
                }
                if (an != bn) return an < bn ? -1 : 1;
            } else {
                if (ac != bc) return ac - bc;
                ai++;
                bi++;
            }
        }
        return a.length() - b.length();
    }

    private static final class Request {
        String method;
        String path;
        String rawPath;
        final Map<String, String> query = new HashMap<String, String>();
        final Map<String, String> headers = new HashMap<String, String>();
        byte[] body = new byte[0];
        String clientAddress = "unknown";
        String localAddress = "127.0.0.1";

        static Request read(BufferedInputStream input) throws IOException {
            String requestLine = readLine(input);
            if (requestLine == null || requestLine.isEmpty()) return null;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) throw new IOException("Invalid HTTP request line");

            Request request = new Request();
            request.method = parts[0].toUpperCase(Locale.US);
            String target = parts[1];
            int question = target.indexOf('?');
            request.rawPath = question < 0 ? target : target.substring(0, question);
            request.path = decode(request.rawPath);
            if (question >= 0) parseQuery(target.substring(question + 1), request.query);

            String line;
            while ((line = readLine(input)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                request.headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                        line.substring(colon + 1).trim());
            }

            int contentLength = 0;
            try { contentLength = Integer.parseInt(request.headers.getOrDefault("content-length", "0")); }
            catch (NumberFormatException ignored) { }
            if (contentLength < 0 || contentLength > MAX_REQUEST_BODY) {
                throw new IOException("Request body is too large");
            }
            if (contentLength > 0) {
                request.body = new byte[contentLength];
                int offset = 0;
                while (offset < contentLength) {
                    int count = input.read(request.body, offset, contentLength - offset);
                    if (count < 0) break;
                    offset += count;
                }
                if (offset < contentLength) request.body = Arrays.copyOf(request.body, offset);
            }
            return request;
        }

        private static String readLine(InputStream input) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int previous = -1;
            int current;
            while ((current = input.read()) != -1) {
                if (previous == '\r' && current == '\n') break;
                if (previous != -1) output.write(previous);
                previous = current;
                if (output.size() > MAX_HEADER_LINE) throw new IOException("HTTP header line is too long");
            }
            if (current == -1 && previous == -1) return null;
            if (previous != -1 && previous != '\r') output.write(previous);
            return output.toString(StandardCharsets.ISO_8859_1.name());
        }

        private static void parseQuery(String value, Map<String, String> output) {
            for (String pair : value.split("&")) {
                int equals = pair.indexOf('=');
                String key = decode(equals < 0 ? pair : pair.substring(0, equals));
                String item = decode(equals < 0 ? "" : pair.substring(equals + 1));
                output.put(key, item);
            }
        }
    }
}
