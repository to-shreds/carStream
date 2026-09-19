package com.carstream.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure-Java folder model used by both the phone UI and tests. */
public final class LibraryNavigator {
    public static final long ROOT = Long.MIN_VALUE;

    public static final class Location {
        public long torrentId = ROOT;
        public String sourceName = "";
        public String folderPath = "";

        public boolean isRoot() { return torrentId == ROOT; }
        public void reset() { torrentId = ROOT; sourceName = ""; folderPath = ""; }
        public String label() {
            if (isRoot()) return "All TorBox downloads";
            return sourceName + (folderPath.length() == 0 ? "" : " / " + folderPath);
        }
    }

    public static final class Entry {
        public final boolean folder;
        public final long torrentId;
        public final String name;
        public final String detail;
        public final MediaItem item;

        private Entry(boolean folder, long torrentId, String name, String detail, MediaItem item) {
            this.folder = folder;
            this.torrentId = torrentId;
            this.name = name == null || name.trim().length() == 0 ? (folder ? "Folder" : "Untitled video") : name.trim();
            this.detail = detail == null ? "" : detail;
            this.item = item;
        }

        public static Entry folder(long torrentId, String name, String detail) {
            return new Entry(true, torrentId, name, detail, null);
        }

        public static Entry media(MediaItem item, String detail) {
            return new Entry(false, item == null ? -1L : item.torrentId,
                    item == null ? "Untitled video" : item.fileName(), detail, item);
        }
    }

    private static final class FolderStats {
        final String name;
        int total;
        int ready;
        long size;
        FolderStats(String name) { this.name = name == null || name.trim().length() == 0 ? "Folder" : name.trim(); }
    }

    private static final class Snapshot {
        final List<String> folders = new ArrayList<String>();
        final List<MediaItem> files = new ArrayList<MediaItem>();
    }

    private LibraryNavigator() { }

    public static List<Entry> entries(List<MediaItem> source, Location location, String search) {
        List<MediaItem> items = source == null ? Collections.<MediaItem>emptyList() : source;
        validate(items, location);
        String query = search == null ? "" : search.trim().toLowerCase(Locale.US);
        if (query.length() > 0) return search(items, query);
        if (location == null || location.isRoot()) return downloads(items);
        return folder(items, location);
    }

    public static void open(Location location, Entry entry) {
        if (location == null || entry == null || !entry.folder) return;
        if (location.isRoot()) {
            location.torrentId = entry.torrentId;
            location.sourceName = entry.name;
            location.folderPath = "";
        } else {
            location.folderPath = join(location.folderPath, entry.name);
        }
    }

    public static void up(Location location) {
        if (location == null || location.isRoot()) return;
        if (location.folderPath.length() == 0) location.reset();
        else location.folderPath = parent(location.folderPath);
    }

    public static int skipWrappers(List<MediaItem> items, Location location) {
        if (location == null || location.isRoot()) return 0;
        int skipped = 0;
        for (int guard = 0; guard < 12; guard++) {
            Snapshot snapshot = snapshot(items, location.torrentId, location.folderPath);
            if (!snapshot.files.isEmpty() || snapshot.folders.size() != 1) break;
            location.folderPath = join(location.folderPath, snapshot.folders.get(0));
            skipped++;
        }
        return skipped;
    }

    public static MediaItem nextInFolder(List<MediaItem> source, MediaItem current) {
        if (current == null || source == null) return null;
        List<MediaItem> siblings = new ArrayList<MediaItem>();
        String folder = current.folderPath();
        for (MediaItem item : source) {
            if (item.torrentId == current.torrentId && folder.equals(item.folderPath()) && item.ready) siblings.add(item);
        }
        Collections.sort(siblings, new Comparator<MediaItem>() {
            @Override public int compare(MediaItem a, MediaItem b) { return naturalCompare(a.fileName(), b.fileName()); }
        });
        for (int i = 0; i < siblings.size(); i++) {
            if (current.id.equals(siblings.get(i).id)) return i + 1 < siblings.size() ? siblings.get(i + 1) : null;
        }
        return null;
    }

    private static List<Entry> downloads(List<MediaItem> items) {
        Map<Long, FolderStats> grouped = new LinkedHashMap<Long, FolderStats>();
        for (MediaItem item : items) {
            FolderStats stats = grouped.get(Long.valueOf(item.torrentId));
            if (stats == null) {
                stats = new FolderStats(item.displaySourceName());
                grouped.put(Long.valueOf(item.torrentId), stats);
            }
            stats.total++;
            if (item.ready) stats.ready++;
            stats.size += Math.max(0L, item.size);
        }
        List<Map.Entry<Long, FolderStats>> values = new ArrayList<Map.Entry<Long, FolderStats>>(grouped.entrySet());
        Collections.sort(values, new Comparator<Map.Entry<Long, FolderStats>>() {
            @Override public int compare(Map.Entry<Long, FolderStats> a, Map.Entry<Long, FolderStats> b) {
                return naturalCompare(a.getValue().name, b.getValue().name);
            }
        });
        List<Entry> result = new ArrayList<Entry>();
        for (Map.Entry<Long, FolderStats> value : values) {
            FolderStats s = value.getValue();
            result.add(Entry.folder(value.getKey().longValue(), s.name,
                    s.total + " video" + (s.total == 1 ? "" : "s") + " • " + s.ready + " ready" + sizeText(s.size)));
        }
        return result;
    }

    private static List<Entry> folder(List<MediaItem> items, Location location) {
        String clean = MediaItem.normalizePath(location.folderPath);
        String prefix = clean.length() == 0 ? "" : clean + "/";
        Map<String, FolderStats> folders = new LinkedHashMap<String, FolderStats>();
        List<MediaItem> files = new ArrayList<MediaItem>();
        for (MediaItem item : items) {
            if (item.torrentId != location.torrentId) continue;
            String relative = item.relativePath();
            if (prefix.length() > 0 && !relative.startsWith(prefix)) continue;
            String remainder = prefix.length() == 0 ? relative : relative.substring(prefix.length());
            int slash = remainder.indexOf('/');
            if (slash < 0) files.add(item);
            else {
                String child = remainder.substring(0, slash);
                FolderStats stats = folders.get(child);
                if (stats == null) { stats = new FolderStats(child); folders.put(child, stats); }
                stats.total++;
                if (item.ready) stats.ready++;
                stats.size += Math.max(0L, item.size);
            }
        }
        List<FolderStats> folderValues = new ArrayList<FolderStats>(folders.values());
        Collections.sort(folderValues, new Comparator<FolderStats>() {
            @Override public int compare(FolderStats a, FolderStats b) { return naturalCompare(a.name, b.name); }
        });
        Collections.sort(files, new Comparator<MediaItem>() {
            @Override public int compare(MediaItem a, MediaItem b) { return naturalCompare(a.fileName(), b.fileName()); }
        });
        List<Entry> result = new ArrayList<Entry>();
        for (FolderStats s : folderValues) {
            result.add(Entry.folder(location.torrentId, s.name,
                    s.total + " video" + (s.total == 1 ? "" : "s") + " below • " + s.ready + " ready" + sizeText(s.size)));
        }
        for (MediaItem item : files) {
            result.add(Entry.media(item, item.ready ? "Ready" + sizeText(item.size) : "Preparing in TorBox"));
        }
        return result;
    }

    private static List<Entry> search(List<MediaItem> items, String query) {
        List<MediaItem> matches = new ArrayList<MediaItem>();
        for (MediaItem item : items) if (item.searchableText().contains(query)) matches.add(item);
        Collections.sort(matches, new Comparator<MediaItem>() {
            @Override public int compare(MediaItem a, MediaItem b) {
                int location = naturalCompare(a.locationLabel(), b.locationLabel());
                return location != 0 ? location : naturalCompare(a.fileName(), b.fileName());
            }
        });
        int limit = Math.min(matches.size(), 750);
        List<Entry> result = new ArrayList<Entry>();
        for (int i = 0; i < limit; i++) {
            MediaItem item = matches.get(i);
            result.add(Entry.media(item, (item.ready ? "Ready" : "Preparing") + " • In: " + item.locationLabel()));
        }
        return result;
    }

    private static Snapshot snapshot(List<MediaItem> source, long torrentId, String path) {
        List<MediaItem> items = source == null ? Collections.<MediaItem>emptyList() : source;
        Snapshot result = new Snapshot();
        String clean = MediaItem.normalizePath(path);
        String prefix = clean.length() == 0 ? "" : clean + "/";
        Map<String, Boolean> folders = new LinkedHashMap<String, Boolean>();
        for (MediaItem item : items) {
            if (item.torrentId != torrentId) continue;
            String relative = item.relativePath();
            if (prefix.length() > 0 && !relative.startsWith(prefix)) continue;
            String remainder = prefix.length() == 0 ? relative : relative.substring(prefix.length());
            int slash = remainder.indexOf('/');
            if (slash < 0) result.files.add(item);
            else folders.put(remainder.substring(0, slash), Boolean.TRUE);
        }
        result.folders.addAll(folders.keySet());
        Collections.sort(result.folders, new Comparator<String>() {
            @Override public int compare(String a, String b) { return naturalCompare(a, b); }
        });
        return result;
    }

    private static void validate(List<MediaItem> items, Location location) {
        if (location == null || location.isRoot()) return;
        boolean exists = false;
        for (MediaItem item : items) {
            if (item.torrentId == location.torrentId) {
                exists = true;
                location.sourceName = item.displaySourceName();
                break;
            }
        }
        if (!exists) { location.reset(); return; }
        while (location.folderPath.length() > 0 && !hasAny(items, location.torrentId, location.folderPath)) {
            location.folderPath = parent(location.folderPath);
        }
    }

    private static boolean hasAny(List<MediaItem> items, long torrentId, String path) {
        String clean = MediaItem.normalizePath(path);
        String prefix = clean.length() == 0 ? "" : clean + "/";
        for (MediaItem item : items) {
            if (item.torrentId != torrentId) continue;
            if (clean.length() == 0 || item.relativePath().startsWith(prefix)) return true;
        }
        return false;
    }

    public static int naturalCompare(String left, String right) {
        String a = left == null ? "" : left;
        String b = right == null ? "" : right;
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int ai = i, bj = j;
                while (ai < a.length() && a.charAt(ai) == '0') ai++;
                while (bj < b.length() && b.charAt(bj) == '0') bj++;
                int ae = ai, be = bj;
                while (ae < a.length() && Character.isDigit(a.charAt(ae))) ae++;
                while (be < b.length() && Character.isDigit(b.charAt(be))) be++;
                int length = (ae - ai) - (be - bj);
                if (length != 0) return length;
                int cmp = a.substring(ai, ae).compareTo(b.substring(bj, be));
                if (cmp != 0) return cmp;
                i = ae; j = be; continue;
            }
            int cmp = Character.toLowerCase(ca) - Character.toLowerCase(cb);
            if (cmp != 0) return cmp;
            i++; j++;
        }
        return a.length() - b.length();
    }

    public static String join(String left, String right) {
        String a = MediaItem.normalizePath(left), b = MediaItem.normalizePath(right);
        return a.length() == 0 ? b : (b.length() == 0 ? a : a + "/" + b);
    }

    public static String parent(String value) {
        String clean = MediaItem.normalizePath(value);
        int slash = clean.lastIndexOf('/');
        return slash < 0 ? "" : clean.substring(0, slash);
    }

    private static String sizeText(long bytes) {
        if (bytes <= 0) return "";
        if (bytes >= 1024L * 1024L * 1024L) return String.format(Locale.US, " • %.1f GB", bytes / 1073741824.0);
        return " • " + Math.max(1L, Math.round(bytes / 1048576.0)) + " MB";
    }
}
