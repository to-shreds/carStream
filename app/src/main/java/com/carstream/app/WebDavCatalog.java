package com.carstream.app;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds a read-only WebDAV view of the cached TorBox library. */
public final class WebDavCatalog {
    public static final String ROOT = "/webdav/";

    public static final class Resource {
        public final String href;
        public final String displayName;
        public final boolean collection;
        public final MediaItem item;

        Resource(String href, String displayName, boolean collection, MediaItem item) {
            this.href = href;
            this.displayName = displayName;
            this.collection = collection;
            this.item = item;
        }

        public long size() { return item == null ? 0L : Math.max(0L, item.size); }
        public String mimeType() { return item == null ? "application/octet-stream" : item.mimeType; }
    }

    private final List<MediaItem> items;

    public WebDavCatalog(List<MediaItem> items) {
        this.items = items == null ? Collections.<MediaItem>emptyList() : new ArrayList<MediaItem>(items);
    }

    public Resource resolve(String requestPath) {
        String clean = cleanRequestPath(requestPath);
        if (clean == null) return null;
        if (clean.length() == 0) return new Resource(ROOT, "CarStream", true, null);

        String[] segments = clean.split("/");
        if (segments.length == 0 || !segments[0].startsWith("t-")) return null;
        long torrentId = parseTorrentId(segments[0]);
        if (torrentId == Long.MIN_VALUE) return null;
        String sourceName = sourceNameFor(torrentId);
        if (sourceName == null) return null;
        if (segments.length == 1) {
            return new Resource(ROOT + encodeSegment(segments[0]) + "/", sourceName, true, null);
        }

        String relative = joinDecoded(segments, 1);
        MediaItem exact = findExact(torrentId, relative);
        if (exact != null) {
            return new Resource(hrefForFile(exact), exact.fileName(), false, exact);
        }
        if (hasFolder(torrentId, relative)) {
            return new Resource(hrefForFolder(torrentId, relative), leaf(relative), true, null);
        }
        return null;
    }

    public List<Resource> children(Resource parent) {
        if (parent == null || !parent.collection) return Collections.emptyList();
        String clean = cleanRequestPath(parent.href);
        if (clean == null || clean.length() == 0) return rootFolders();
        String[] segments = clean.split("/");
        long torrentId = parseTorrentId(segments[0]);
        if (torrentId == Long.MIN_VALUE) return Collections.emptyList();
        String folder = segments.length <= 1 ? "" : joinDecoded(segments, 1);
        return childrenForFolder(torrentId, folder);
    }

    private List<Resource> rootFolders() {
        Map<Long, String> names = new LinkedHashMap<Long, String>();
        for (MediaItem item : items) {
            if (!names.containsKey(item.torrentId)) names.put(item.torrentId, item.displaySourceName());
        }
        List<Resource> result = new ArrayList<Resource>();
        for (Map.Entry<Long, String> entry : names.entrySet()) {
            String segment = "t-" + entry.getKey();
            result.add(new Resource(ROOT + encodeSegment(segment) + "/", entry.getValue(), true, null));
        }
        sort(result);
        return result;
    }

    private List<Resource> childrenForFolder(long torrentId, String folder) {
        String cleanFolder = normalize(folder);
        String prefix = cleanFolder.length() == 0 ? "" : cleanFolder + "/";
        Map<String, Resource> folders = new LinkedHashMap<String, Resource>();
        List<Resource> files = new ArrayList<Resource>();

        for (MediaItem item : items) {
            if (item.torrentId != torrentId) continue;
            String relative = normalize(item.relativePath());
            if (!relative.startsWith(prefix)) continue;
            String remainder = relative.substring(prefix.length());
            if (remainder.length() == 0) continue;
            int slash = remainder.indexOf('/');
            if (slash >= 0) {
                String child = remainder.substring(0, slash);
                if (!folders.containsKey(child)) {
                    String path = cleanFolder.length() == 0 ? child : cleanFolder + "/" + child;
                    folders.put(child, new Resource(hrefForFolder(torrentId, path), child, true, null));
                }
            } else {
                files.add(new Resource(hrefForFile(item), item.fileName(), false, item));
            }
        }

        List<Resource> result = new ArrayList<Resource>(folders.values());
        sort(result);
        sort(files);
        result.addAll(files);
        return result;
    }

    private MediaItem findExact(long torrentId, String relative) {
        String target = normalize(relative);
        for (MediaItem item : items) {
            if (item.torrentId == torrentId && normalize(item.relativePath()).equals(target)) return item;
        }
        return null;
    }

    private boolean hasFolder(long torrentId, String relative) {
        String target = normalize(relative);
        String prefix = target.length() == 0 ? "" : target + "/";
        for (MediaItem item : items) {
            if (item.torrentId == torrentId && normalize(item.relativePath()).startsWith(prefix)) return true;
        }
        return false;
    }

    private String sourceNameFor(long torrentId) {
        for (MediaItem item : items) if (item.torrentId == torrentId) return item.displaySourceName();
        return null;
    }

    private static void sort(List<Resource> values) {
        Collections.sort(values, new Comparator<Resource>() {
            @Override public int compare(Resource left, Resource right) {
                if (left.collection != right.collection) return left.collection ? -1 : 1;
                return naturalCompare(left.displayName, right.displayName);
            }
        });
    }

    public static String hrefForFile(MediaItem item) {
        return ROOT + encodeSegment("t-" + item.torrentId) + "/" + encodePath(item.relativePath());
    }

    public static String hrefForFolder(long torrentId, String relative) {
        String encoded = encodePath(relative);
        return ROOT + encodeSegment("t-" + torrentId) + "/" + (encoded.length() == 0 ? "" : encoded + "/");
    }

    private static String cleanRequestPath(String requestPath) {
        if (requestPath == null) return null;
        String value = requestPath;
        if (value.equals("/webdav")) value = ROOT;
        if (!value.startsWith(ROOT)) return null;
        value = value.substring(ROOT.length());
        while (value.endsWith("/") && value.length() > 0) value = value.substring(0, value.length() - 1);
        value = normalize(value);
        if (value.contains("../") || value.equals("..") || value.contains("/..")) return null;
        return value;
    }

    private static long parseTorrentId(String value) {
        if (value == null || !value.startsWith("t-")) return Long.MIN_VALUE;
        try { return Long.parseLong(value.substring(2)); }
        catch (NumberFormatException ignored) { return Long.MIN_VALUE; }
    }

    private static String joinDecoded(String[] values, int start) {
        StringBuilder out = new StringBuilder();
        for (int i = start; i < values.length; i++) {
            if (values[i].length() == 0) continue;
            if (out.length() > 0) out.append('/');
            out.append(values[i]);
        }
        return normalize(out.toString());
    }

    private static String normalize(String value) {
        return MediaItem.normalizePath(value);
    }

    private static String leaf(String path) {
        String clean = normalize(path);
        int slash = clean.lastIndexOf('/');
        return slash < 0 ? clean : clean.substring(slash + 1);
    }

    private static String encodePath(String value) {
        String clean = normalize(value);
        if (clean.length() == 0) return "";
        String[] segments = clean.split("/");
        StringBuilder out = new StringBuilder();
        for (String segment : segments) {
            if (out.length() > 0) out.append('/');
            out.append(encodeSegment(segment));
        }
        return out.toString();
    }

    private static String encodeSegment(String value) {
        try { return URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20"); }
        catch (UnsupportedEncodingException ignored) { return value == null ? "" : value; }
    }

    private static int naturalCompare(String left, String right) {
        String a = left == null ? "" : left.toLowerCase(Locale.US);
        String b = right == null ? "" : right.toLowerCase(Locale.US);
        int ia = 0, ib = 0;
        while (ia < a.length() && ib < b.length()) {
            char ca = a.charAt(ia), cb = b.charAt(ib);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int ea = ia, eb = ib;
                while (ea < a.length() && Character.isDigit(a.charAt(ea))) ea++;
                while (eb < b.length() && Character.isDigit(b.charAt(eb))) eb++;
                String na = a.substring(ia, ea).replaceFirst("^0+(?!$)", "");
                String nb = b.substring(ib, eb).replaceFirst("^0+(?!$)", "");
                if (na.length() != nb.length()) return na.length() < nb.length() ? -1 : 1;
                int compare = na.compareTo(nb);
                if (compare != 0) return compare;
                ia = ea; ib = eb;
            } else {
                if (ca != cb) return ca < cb ? -1 : 1;
                ia++; ib++;
            }
        }
        return a.length() - b.length();
    }
}
