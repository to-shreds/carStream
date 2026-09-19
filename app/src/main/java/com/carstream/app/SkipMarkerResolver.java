package com.carstream.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Resolves skip markers from manual records, embedded chapters, and IntroDB. */
public final class SkipMarkerResolver {
    private static final long ONLINE_CACHE_MS = 21L * 24L * 60L * 60L * 1000L;
    private static final int MAX_RESPONSE = 512 * 1024;
    private static final String TVMAZE = "https://api.tvmaze.com";
    private static final String INTRODB = "https://api.introdb.app";

    public static final class Resolution {
        public final EpisodeIdentity identity;
        public final List<SkipSegment> segments;
        public final String source;
        public final String warning;
        public final String manualScope;

        Resolution(EpisodeIdentity identity, List<SkipSegment> segments, String source,
                   String warning, String manualScope) {
            this.identity = identity;
            this.segments = segments;
            this.source = source == null ? "" : source;
            this.warning = warning == null ? "" : warning;
            this.manualScope = manualScope == null ? "" : manualScope;
        }

        public JSONObject toJson() throws JSONException {
            JSONArray array = new JSONArray();
            for (SkipSegment segment : segments) array.put(segment.toJson());
            return new JSONObject()
                    .put("identity", identity == null ? JSONObject.NULL : identity.toJson())
                    .put("segments", array)
                    .put("source", source)
                    .put("warning", warning)
                    .put("manualScope", manualScope);
        }
    }

    private final CellularNetworkProvider networks;
    private final SkipMarkerStore store;
    private final AppSettings settings;

    public SkipMarkerResolver(CellularNetworkProvider networks, SkipMarkerStore store, AppSettings settings) {
        this.networks = networks;
        this.store = store;
        this.settings = settings;
    }

    public Resolution resolve(MediaItem item, boolean preferCellular, boolean forceOnline) {
        EpisodeIdentity identity = store.identityFor(item);
        SkipMarkerStore.ManualMatch manual = store.findManual(item, identity);
        List<SkipSegment> onlineSegments = new ArrayList<SkipSegment>();
        String onlineSource = "";
        String warning = "";

        SkipMarkerStore.CachedResult cached = forceOnline ? null : store.getOnline(item == null ? "" : item.id, ONLINE_CACHE_MS);
        if (cached != null) {
            if (cached.identity != null) identity = cached.identity;
            onlineSegments.addAll(cached.segments);
            onlineSource = cached.source;
            warning = cached.warning;
        }

        if (item != null && settings.isOnlineSkipLookupEnabled()
                && (forceOnline || cached == null) && identity.canLookupOnline()) {
            try {
                if (identity.imdbId.isEmpty()) {
                    EpisodeIdentity lookedUp = lookupShow(identity, preferCellular);
                    if (lookedUp != null) identity = lookedUp;
                }
                if (!identity.imdbId.isEmpty()) {
                    onlineSegments = fetchIntroDb(identity, preferCellular);
                    onlineSource = onlineSegments.isEmpty() ? "IntroDB checked" : "IntroDB";
                    warning = onlineSegments.isEmpty() ? "No community timestamps were found for this episode." : "";
                } else {
                    warning = "The show could not be matched to an IMDb ID. You can correct the show details in the marker editor.";
                }
            } catch (Exception error) {
                warning = "Online marker lookup failed: " + safeMessage(error);
                EventLogger.warn("Skip", warning, error);
            }
            store.saveOnline(item.id, identity, onlineSegments, onlineSource, warning);
        } else if (item != null && (identity.season == 0 || identity.episode == 0)) {
            warning = "Season and episode numbers could not be read from the file name. Manual markers still work.";
        }

        if (item != null) store.saveIdentity(item.id, identity);
        List<SkipSegment> merged = merge(onlineSegments, manual == null ? null : manual.segments);
        String source;
        if (manual != null && !onlineSegments.isEmpty()) source = "Manual markers plus " + (onlineSource.isEmpty() ? "online markers" : onlineSource);
        else if (manual != null) source = "Manual markers";
        else source = onlineSource;
        return new Resolution(identity, Collections.unmodifiableList(merged), source, warning,
                manual == null ? "" : manual.scope);
    }

    private EpisodeIdentity lookupShow(EpisodeIdentity identity, boolean preferCellular)
            throws IOException, JSONException {
        URL url = new URL(TVMAZE + "/search/shows?q=" + encode(identity.showTitle));
        String body = read(url, preferCellular, "TVmaze show search");
        JSONArray results = new JSONArray(body);
        JSONObject bestShow = null;
        double bestScore = -1.0;
        String wanted = EpisodeIdentity.normalizeKey(identity.showTitle);
        for (int i = 0; i < Math.min(10, results.length()); i++) {
            JSONObject result = results.optJSONObject(i);
            JSONObject show = result == null ? null : result.optJSONObject("show");
            if (show == null) continue;
            String name = show.optString("name", "");
            double score = result.optDouble("score", 0.0);
            if (wanted.equals(EpisodeIdentity.normalizeKey(name))) score += 2.0;
            int premieredYear = yearOf(show.optString("premiered", ""));
            if (identity.year > 0 && premieredYear == identity.year) score += 1.0;
            if (score > bestScore) { bestScore = score; bestShow = show; }
        }
        if (bestShow == null) return identity;
        JSONObject externals = bestShow.optJSONObject("externals");
        String imdb = externals == null ? "" : externals.optString("imdb", "");
        return identity.withLookup(bestShow.optString("name", identity.showTitle),
                yearOf(bestShow.optString("premiered", "")), imdb,
                bestShow.optLong("id", -1L), Math.min(0.99, 0.65 + Math.max(0.0, bestScore) / 10.0));
    }

    private List<SkipSegment> fetchIntroDb(EpisodeIdentity identity, boolean preferCellular)
            throws IOException, JSONException {
        String query = "?imdb_id=" + encode(identity.imdbId)
                + "&season=" + identity.season + "&episode=" + identity.episode;
        String body;
        try {
            body = read(new URL(INTRODB + "/segments" + query), preferCellular, "IntroDB segment lookup");
        } catch (HttpNotFound ignored) {
            return Collections.emptyList();
        }
        List<SkipSegment> result = parseSegments(body);
        if (!result.isEmpty()) return result;

        // Keep compatibility with older IntroDB deployments that expose intro-only data.
        try {
            String legacy = read(new URL(INTRODB + "/intro" + query), preferCellular, "IntroDB legacy intro lookup");
            result.addAll(parseSegments(legacy));
        } catch (HttpNotFound ignored) { }
        return result;
    }

    private List<SkipSegment> parseSegments(String body) throws JSONException {
        List<SkipSegment> result = new ArrayList<SkipSegment>();
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) return result;
        if (text.startsWith("[")) {
            appendSegments(result, new JSONArray(text));
        } else {
            JSONObject root = new JSONObject(text);
            JSONArray array = root.optJSONArray("segments");
            if (array == null) array = root.optJSONArray("data");
            if (array != null) appendSegments(result, array);
            else appendSegment(result, root);
        }
        Collections.sort(result, new Comparator<SkipSegment>() {
            @Override public int compare(SkipSegment left, SkipSegment right) {
                return left.startMillis < right.startMillis ? -1 : left.startMillis == right.startMillis ? 0 : 1;
            }
        });
        return result;
    }

    private static void appendSegments(List<SkipSegment> target, JSONArray array) {
        for (int i = 0; i < array.length(); i++) appendSegment(target, array.optJSONObject(i));
    }

    private static void appendSegment(List<SkipSegment> target, JSONObject value) {
        if (value == null) return;
        String type = value.optString("segment_type", value.optString("type", "intro"));
        long start;
        long end;
        if (value.has("start_ms")) start = value.optLong("start_ms", 0L);
        else start = SkipSegment.parseTimeMillis(first(value, "start_sec", "startSeconds", "start"));
        if (value.has("end_ms")) end = value.optLong("end_ms", -1L);
        else end = SkipSegment.parseTimeMillis(first(value, "end_sec", "endSeconds", "end"));
        if (end <= start && SkipSegment.CREDITS.equals(SkipSegment.normalizeType(type))) end = -1L;
        if (end <= start && !SkipSegment.CREDITS.equals(SkipSegment.normalizeType(type))) return;
        double confidence = value.optDouble("confidence", value.optDouble("score", 0.8));
        target.add(new SkipSegment(type, start, end, "IntroDB", "", confidence));
    }

    private static Object first(JSONObject value, String... keys) {
        for (String key : keys) if (value.has(key)) return value.opt(key);
        return null;
    }

    /** Manual segments replace online segments of the same type. */
    public static List<SkipSegment> merge(List<SkipSegment> online, List<SkipSegment> manual) {
        LinkedHashMap<String, List<SkipSegment>> byType = new LinkedHashMap<String, List<SkipSegment>>();
        if (online != null) {
            for (SkipSegment segment : online) {
                List<SkipSegment> values = byType.get(segment.type);
                if (values == null) { values = new ArrayList<SkipSegment>(); byType.put(segment.type, values); }
                values.add(segment);
            }
        }
        if (manual != null) {
            Map<String, List<SkipSegment>> manualTypes = new LinkedHashMap<String, List<SkipSegment>>();
            for (SkipSegment segment : manual) {
                List<SkipSegment> values = manualTypes.get(segment.type);
                if (values == null) { values = new ArrayList<SkipSegment>(); manualTypes.put(segment.type, values); }
                values.add(segment);
            }
            for (Map.Entry<String, List<SkipSegment>> entry : manualTypes.entrySet()) byType.put(entry.getKey(), entry.getValue());
        }
        List<SkipSegment> result = new ArrayList<SkipSegment>();
        for (List<SkipSegment> values : byType.values()) result.addAll(values);
        Collections.sort(result, new Comparator<SkipSegment>() {
            @Override public int compare(SkipSegment left, SkipSegment right) {
                return left.startMillis < right.startMillis ? -1 : left.startMillis == right.startMillis ? 0 : 1;
            }
        });
        return result;
    }

    private String read(URL url, boolean preferCellular, String operation) throws IOException {
        HttpURLConnection connection = networks.open(url, preferCellular);
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(20_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "CarStream/" + BuildInfo.VERSION_NAME + " Android");
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = IoUtils.readUtf8(input, MAX_RESPONSE);
        connection.disconnect();
        if (status == 404) throw new HttpNotFound();
        if (status == 429) throw new IOException(operation + " is temporarily rate limited. Try again in a few seconds.");
        if (status < 200 || status >= 300) throw new IOException(operation + " returned HTTP " + status);
        return body;
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    private static int yearOf(String date) {
        if (date == null || date.length() < 4) return 0;
        try { return Integer.parseInt(date.substring(0, 4)); }
        catch (Exception ignored) { return 0; }
    }

    private static String safeMessage(Throwable error) {
        String value = error == null ? null : error.getMessage();
        return value == null || value.trim().isEmpty()
                ? (error == null ? "unknown error" : error.getClass().getSimpleName()) : value.trim();
    }

    private static final class HttpNotFound extends IOException { }
}
