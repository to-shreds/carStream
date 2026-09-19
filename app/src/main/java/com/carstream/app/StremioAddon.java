package com.carstream.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A local-only catalog adapter. It never resolves or returns remote video URLs. */
public final class StremioAddon {
    private static final String PREFIX = "carstream:";
    private static final Pattern EPISODE = Pattern.compile("(?i)(?:^|[^a-z0-9])s(\\d{1,3})[ ._-]*e(\\d{1,4})(?:[^0-9]|$)|(?:^|[^a-z0-9])(\\d{1,3})x(\\d{1,4})(?:[^0-9]|$)");
    private final String base;
    private final List<Group> groups = new ArrayList<Group>();
    private final Map<String, MediaItem> videos = new LinkedHashMap<String, MediaItem>();

    private static final class Group {
        String id, name, type;
        List<MediaItem> items = new ArrayList<MediaItem>();
    }

    public StremioAddon(List<MediaItem> library, String base) {
        this.base = base;
        Map<Long, Group> downloads = new LinkedHashMap<Long, Group>();
        for (MediaItem item : library) {
            if (!item.ready) continue;
            Group group = downloads.get(item.torrentId);
            if (group == null) {
                group = new Group();
                group.id = PREFIX + "download:" + item.torrentId;
                group.name = item.displaySourceName();
                downloads.put(item.torrentId, group);
            }
            group.items.add(item);
            videos.put(videoId(item), item);
        }
        for (Group group : downloads.values()) {
            Collections.sort(group.items, new Comparator<MediaItem>() {
                @Override public int compare(MediaItem a, MediaItem b) {
                    int[] left = episode(a), right = episode(b);
                    int diff = Integer.compare(left[0], right[0]);
                    if (diff == 0) diff = Integer.compare(left[1], right[1]);
                    return diff == 0 ? naturalCompare(a.relativePath(), b.relativePath()) : diff;
                }
            });
            boolean series = group.items.size() > 1 || episode(group.items.get(0))[1] > 0;
            group.type = series ? "series" : "movie";
            if (!series) group.id = videoId(group.items.get(0));
            groups.add(group);
        }
        Collections.sort(groups, new Comparator<Group>() {
            @Override public int compare(Group a, Group b) {
                boolean ae = isElena(a.name), be = isElena(b.name);
                return ae != be ? (ae ? -1 : 1) : naturalCompare(a.name, b.name);
            }
        });
    }

    public JSONObject manifest() throws JSONException {
        JSONArray catalogs = new JSONArray();
        for (String type : new String[] {"series", "movie"}) {
            catalogs.put(new JSONObject().put("type", type).put("id", "carstream")
                    .put("name", "CarStream " + ("series".equals(type) ? "Shows" : "Movies"))
                    .put("extra", new JSONArray().put(new JSONObject().put("name", "search").put("isRequired", false))));
        }
        return new JSONObject().put("id", "com.carstream.local").put("version", BuildInfo.VERSION_NAME)
                .put("name", "CarStream on your phone")
                .put("description", "Your phone's ready TorBox videos over CarStream Wi-Fi. Keep CarStream running.")
                .put("types", new JSONArray().put("series").put("movie"))
                .put("resources", new JSONArray().put("catalog").put("meta").put("stream"))
                .put("idPrefixes", new JSONArray().put(PREFIX)).put("catalogs", catalogs);
    }

    public JSONObject catalog(String type, String catalogId, String search) throws JSONException {
        JSONArray metas = new JSONArray();
        if ("carstream".equals(catalogId)) {
            String query = search == null ? "" : search.toLowerCase(Locale.US).trim();
            for (Group group : groups) {
                if (!group.type.equals(type)) continue;
                boolean matches = group.name.toLowerCase(Locale.US).contains(query);
                for (MediaItem item : group.items) matches |= item.searchableText().contains(query);
                if (matches) metas.put(preview(group));
            }
        }
        return response("metas", metas);
    }

    public JSONObject meta(String type, String id) throws JSONException {
        Group group = findGroup(id);
        if (group == null || !group.type.equals(type)) return response("meta", JSONObject.NULL);
        JSONObject meta = preview(group);
        if ("series".equals(type)) {
            JSONArray episodes = new JSONArray();
            int index = 0;
            for (MediaItem item : group.items) {
                int[] numbering = episode(item);
                JSONObject video = new JSONObject().put("id", videoId(item)).put("title", item.title)
                        .put("available", true).put("season", numbering[1] > 0 ? numbering[0] : 1)
                        .put("episode", numbering[1] > 0 ? numbering[1] : index + 1)
                        .put("overview", item.relativePath())
                        .put("streams", streamsFor(item, group));
                // Air dates are unknown. Current core accepts an absent date; do not invent one.
                episodes.put(video);
                index++;
            }
            meta.put("videos", episodes);
        }
        return response("meta", meta);
    }

    public JSONObject streams(String type, String id) throws JSONException {
        MediaItem item = videos.get(id);
        Group group = item == null ? null : groupFor(item);
        return response("streams", group != null && group.type.equals(type)
                ? streamsFor(item, group) : new JSONArray());
    }

    public String mediaId(String videoId) {
        MediaItem item = videos.get(videoId);
        return item == null ? null : item.id;
    }

    public String posterTitle(String id) {
        Group group = findGroup(id);
        return group == null ? null : group.name;
    }

    private JSONObject preview(Group group) throws JSONException {
        return new JSONObject().put("id", group.id).put("type", group.type).put("name", group.name)
                .put("poster", base + "poster/" + encode(group.id) + ".png").put("posterShape", "square")
                .put("description", group.items.size() + " ready video(s) from your phone. Open an episode to play.");
    }

    private JSONArray streamsFor(MediaItem item, Group group) throws JSONException {
        return new JSONArray().put(new JSONObject().put("name", "CarStream")
                .put("title", item.title + "\nFrom your phone over local Wi-Fi")
                .put("url", base + "play/" + encode(videoId(item)) + "/" + encode(item.fileName()))
                .put("behaviorHints", new JSONObject().put("notWebReady", true)
                        .put("bingeGroup", "carstream-" + item.torrentId).put("filename", item.fileName())));
    }

    private Group findGroup(String id) {
        for (Group group : groups) if (group.id.equals(id)) return group;
        return null;
    }

    private Group groupFor(MediaItem item) {
        for (Group group : groups) if (group.items.get(0).torrentId == item.torrentId) return group;
        return null;
    }

    private static JSONObject response(String key, Object value) throws JSONException {
        return new JSONObject().put(key, value).put("cacheMaxAge", 0).put("staleRevalidate", 0).put("staleError", 0);
    }

    private static String videoId(MediaItem item) { return PREFIX + "video:" + item.id; }

    private static int[] episode(MediaItem item) {
        // Prefer the actual filename to an episode marker in the download's name.
        Matcher match = EPISODE.matcher(item.fileName());
        if (match.find()) {
            return match.group(1) != null
                    ? new int[] {Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2))}
                    : new int[] {Integer.parseInt(match.group(3)), Integer.parseInt(match.group(4))};
        }
        EpisodeIdentity parsed = EpisodeIdentity.parse(item);
        return new int[] {parsed.season, parsed.episode};
    }

    private static boolean isElena(String name) {
        return name.toLowerCase(Locale.US).replaceAll("[._-]+", " ").contains("elena of avalor");
    }

    static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (Exception error) { throw new IllegalArgumentException(error); }
    }

    private static int naturalCompare(String a, String b) {
        a = a.toLowerCase(Locale.US); b = b.toLowerCase(Locale.US);
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ac = a.charAt(i), bc = b.charAt(j);
            if (Character.isDigit(ac) && Character.isDigit(bc)) {
                int ai = i, bj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String an = a.substring(ai, i).replaceFirst("^0+(?!$)", "");
                String bn = b.substring(bj, j).replaceFirst("^0+(?!$)", "");
                int diff = Integer.compare(an.length(), bn.length());
                if (diff == 0) diff = an.compareTo(bn);
                if (diff != 0) return diff;
            } else {
                if (ac != bc) return ac - bc;
                i++; j++;
            }
        }
        return Integer.compare(a.length(), b.length());
    }
}
