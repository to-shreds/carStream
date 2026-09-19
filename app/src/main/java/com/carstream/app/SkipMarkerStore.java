package com.carstream.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stores manual skip markers, resolved episode identities, and short-lived online results. */
public final class SkipMarkerStore {
    public static final String SCOPE_EPISODE = "episode";
    public static final String SCOPE_FOLDER = "folder";
    public static final String SCOPE_SEASON = "season";
    public static final String SCOPE_SHOW = "show";

    private static final String FILE_NAME = "skip-markers-v1.json";
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;

    public static final class ManualMatch {
        public final String scope;
        public final String key;
        public final List<SkipSegment> segments;
        ManualMatch(String scope, String key, List<SkipSegment> segments) {
            this.scope = scope;
            this.key = key;
            this.segments = segments;
        }
    }

    public static final class CachedResult {
        public final EpisodeIdentity identity;
        public final List<SkipSegment> segments;
        public final long checkedAt;
        public final String source;
        public final String warning;
        CachedResult(EpisodeIdentity identity, List<SkipSegment> segments, long checkedAt,
                     String source, String warning) {
            this.identity = identity;
            this.segments = segments;
            this.checkedAt = checkedAt;
            this.source = source;
            this.warning = warning;
        }
    }

    private final Context context;
    private final Map<String, JSONObject> manual = new LinkedHashMap<String, JSONObject>();
    private final Map<String, JSONObject> online = new LinkedHashMap<String, JSONObject>();
    private final Map<String, EpisodeIdentity> identities = new LinkedHashMap<String, EpisodeIdentity>();

    public SkipMarkerStore(Context context) {
        this.context = context == null ? null : context.getApplicationContext();
        load();
    }

    public synchronized EpisodeIdentity identityFor(MediaItem item) {
        if (item == null) return EpisodeIdentity.parse(null);
        EpisodeIdentity saved = identities.get(item.id);
        return saved == null ? EpisodeIdentity.parse(item) : saved;
    }

    public synchronized void saveIdentity(String mediaId, EpisodeIdentity identity) {
        if (mediaId == null || mediaId.trim().isEmpty() || identity == null) return;
        identities.put(mediaId, identity);
        save();
    }

    public synchronized ManualMatch findManual(MediaItem item, EpisodeIdentity identity) {
        if (item == null) return null;
        String[] keys = candidateKeys(item, identity);
        for (String key : keys) {
            JSONObject record = manual.get(key);
            if (record == null) continue;
            List<SkipSegment> segments = segmentsFrom(record.optJSONArray("segments"));
            if (!segments.isEmpty()) {
                return new ManualMatch(record.optString("scope", SCOPE_EPISODE), key,
                        Collections.unmodifiableList(segments));
            }
        }
        return null;
    }

    public synchronized void saveManual(String scope, MediaItem item, EpisodeIdentity identity,
                                        List<SkipSegment> segments) {
        if (item == null) return;
        String normalizedScope = normalizeScope(scope);
        String key = keyForScope(normalizedScope, item, identity);
        JSONArray array = new JSONArray();
        if (segments != null) {
            for (SkipSegment segment : segments) {
                if (segment == null) continue;
                try {
                    array.put(new SkipSegment(segment.type, segment.startMillis, segment.endMillis,
                            "manual", segment.label, 1.0).toJson());
                } catch (JSONException ignored) { }
            }
        }
        if (array.length() == 0) manual.remove(key);
        else {
            try {
                manual.put(key, new JSONObject()
                        .put("scope", normalizedScope)
                        .put("key", key)
                        .put("savedAt", System.currentTimeMillis())
                        .put("identity", identity == null ? JSONObject.NULL : identity.toJson())
                        .put("segments", array));
            } catch (JSONException ignored) { }
        }
        if (identity != null) identities.put(item.id, identity);
        save();
    }

    public synchronized void clearManual(String scope, MediaItem item, EpisodeIdentity identity) {
        if (item == null) return;
        manual.remove(keyForScope(normalizeScope(scope), item, identity));
        save();
    }

    public synchronized CachedResult getOnline(String mediaId, long maximumAgeMillis) {
        JSONObject record = mediaId == null ? null : online.get(mediaId);
        if (record == null) return null;
        long checkedAt = record.optLong("checkedAt", 0L);
        if (maximumAgeMillis > 0L && System.currentTimeMillis() - checkedAt > maximumAgeMillis) return null;
        EpisodeIdentity identity = EpisodeIdentity.fromJson(record.optJSONObject("identity"));
        List<SkipSegment> segments = segmentsFrom(record.optJSONArray("segments"));
        return new CachedResult(identity, Collections.unmodifiableList(segments), checkedAt,
                record.optString("source", "online cache"), record.optString("warning", ""));
    }

    public synchronized void saveOnline(String mediaId, EpisodeIdentity identity,
                                        List<SkipSegment> segments, String source, String warning) {
        if (mediaId == null || mediaId.trim().isEmpty()) return;
        JSONArray array = new JSONArray();
        if (segments != null) {
            for (SkipSegment segment : segments) {
                try { if (segment != null) array.put(segment.toJson()); }
                catch (JSONException ignored) { }
            }
        }
        try {
            online.put(mediaId, new JSONObject()
                    .put("checkedAt", System.currentTimeMillis())
                    .put("identity", identity == null ? JSONObject.NULL : identity.toJson())
                    .put("segments", array)
                    .put("source", source == null ? "" : source)
                    .put("warning", warning == null ? "" : warning));
        } catch (JSONException ignored) { }
        if (identity != null) identities.put(mediaId, identity);
        trimOnlineCache();
        save();
    }

    public synchronized JSONObject diagnostics() throws JSONException {
        return new JSONObject()
                .put("manualRecords", manual.size())
                .put("onlineRecords", online.size())
                .put("identifiedFiles", identities.size());
    }

    private String[] candidateKeys(MediaItem item, EpisodeIdentity identity) {
        ArrayList<String> result = new ArrayList<String>();
        result.add(fileKey(item));
        if (identity != null && identity.season > 0 && identity.episode > 0 && !identity.showKey().isEmpty()) {
            result.add(episodeKey(identity));
        }
        result.add(folderKey(item));
        if (identity != null && identity.season > 0 && !identity.showKey().isEmpty()) {
            result.add(seasonKey(identity));
        }
        if (identity != null && !identity.showKey().isEmpty()) result.add(showKey(identity));
        return result.toArray(new String[result.size()]);
    }

    private String keyForScope(String scope, MediaItem item, EpisodeIdentity identity) {
        if (SCOPE_SHOW.equals(scope) && identity != null && !identity.showKey().isEmpty()) return showKey(identity);
        if (SCOPE_SEASON.equals(scope) && identity != null && identity.season > 0
                && !identity.showKey().isEmpty()) return seasonKey(identity);
        if (SCOPE_FOLDER.equals(scope)) return folderKey(item);
        if (identity != null && identity.season > 0 && identity.episode > 0
                && !identity.showKey().isEmpty()) return episodeKey(identity);
        return fileKey(item);
    }

    private static String fileKey(MediaItem item) { return "file:" + item.id; }
    private static String episodeKey(EpisodeIdentity identity) { return "episode:" + identity.episodeKey(); }
    private static String seasonKey(EpisodeIdentity identity) {
        return "season:" + identity.showKey() + ":s" + identity.season;
    }
    private static String showKey(EpisodeIdentity identity) { return "show:" + identity.showKey(); }
    private static String folderKey(MediaItem item) {
        return "folder:" + item.torrentId + ":" + MediaItem.normalizePath(item.folderPath()).toLowerCase();
    }

    private static String normalizeScope(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase();
        if (SCOPE_SHOW.equals(clean) || SCOPE_SEASON.equals(clean) || SCOPE_FOLDER.equals(clean)) return clean;
        return SCOPE_EPISODE;
    }

    private static List<SkipSegment> segmentsFrom(JSONArray array) {
        List<SkipSegment> result = new ArrayList<SkipSegment>();
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) {
            SkipSegment segment = SkipSegment.fromJson(array.optJSONObject(i));
            if (segment != null) result.add(segment);
        }
        Collections.sort(result, new java.util.Comparator<SkipSegment>() {
            @Override public int compare(SkipSegment left, SkipSegment right) {
                return left.startMillis < right.startMillis ? -1 : left.startMillis == right.startMillis ? 0 : 1;
            }
        });
        return result;
    }

    private void trimOnlineCache() {
        while (online.size() > 500) {
            String first = online.keySet().iterator().next();
            online.remove(first);
        }
    }

    private void load() {
        if (context == null) return;
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.isFile()) return;
        try {
            JSONObject root = new JSONObject(new String(IoUtils.readFile(file, MAX_FILE_BYTES), StandardCharsets.UTF_8));
            JSONObject manualObject = root.optJSONObject("manual");
            if (manualObject != null) {
                java.util.Iterator<String> keys = manualObject.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONObject record = manualObject.optJSONObject(key);
                    if (record != null) manual.put(key, record);
                }
            }
            JSONObject onlineObject = root.optJSONObject("online");
            if (onlineObject != null) {
                java.util.Iterator<String> keys = onlineObject.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONObject record = onlineObject.optJSONObject(key);
                    if (record != null) online.put(key, record);
                }
            }
            JSONObject identitiesObject = root.optJSONObject("identities");
            if (identitiesObject != null) {
                java.util.Iterator<String> keys = identitiesObject.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    EpisodeIdentity identity = EpisodeIdentity.fromJson(identitiesObject.optJSONObject(key));
                    if (identity != null) identities.put(key, identity);
                }
            }
            EventLogger.info(context, "Skip", "Loaded " + manual.size() + " manual marker records and "
                    + online.size() + " online marker results");
        } catch (Exception error) {
            EventLogger.warn(context, "Skip", "Could not read saved skip markers", error);
        }
    }

    private synchronized void save() {
        if (context == null) return;
        try {
            JSONObject manualObject = new JSONObject();
            for (Map.Entry<String, JSONObject> entry : manual.entrySet()) manualObject.put(entry.getKey(), entry.getValue());
            JSONObject onlineObject = new JSONObject();
            for (Map.Entry<String, JSONObject> entry : online.entrySet()) onlineObject.put(entry.getKey(), entry.getValue());
            JSONObject identitiesObject = new JSONObject();
            for (Map.Entry<String, EpisodeIdentity> entry : identities.entrySet()) identitiesObject.put(entry.getKey(), entry.getValue().toJson());
            JSONObject root = new JSONObject()
                    .put("version", 1)
                    .put("manual", manualObject)
                    .put("online", onlineObject)
                    .put("identities", identitiesObject);
            FileOutputStream output = new FileOutputStream(new File(context.getFilesDir(), FILE_NAME), false);
            try {
                output.write(root.toString().getBytes(StandardCharsets.UTF_8));
                output.flush();
            } finally { output.close(); }
        } catch (Exception error) {
            EventLogger.warn(context, "Skip", "Could not save skip markers", error);
        }
    }
}
