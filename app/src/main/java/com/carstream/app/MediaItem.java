package com.carstream.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

public final class MediaItem {
    public final String id;
    public final long torrentId;
    public final long fileId;
    public final String sourceName;
    public final String title;
    public final String path;
    public final long size;
    public final String mimeType;
    public final boolean ready;

    public MediaItem(String id, long torrentId, long fileId, String sourceName,
                     String title, String path, long size, String mimeType, boolean ready) {
        this.id = id == null ? "" : id;
        this.torrentId = torrentId;
        this.fileId = fileId;
        this.sourceName = sourceName == null ? "" : sourceName.trim();
        this.title = title == null || title.trim().isEmpty() ? "Untitled video" : title.trim();
        this.path = path == null ? "" : path.trim();
        this.size = size;
        this.mimeType = mimeType == null || mimeType.trim().isEmpty() ? "video/mp4" : mimeType;
        this.ready = ready;
    }

    /** Backward-compatible constructor for older cached/project code. */
    public MediaItem(String id, long torrentId, long fileId, String title, String path,
                     long size, String mimeType, boolean ready) {
        this(id, torrentId, fileId, "", title, path, size, mimeType, ready);
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("torrentId", torrentId)
                .put("fileId", fileId)
                .put("sourceName", sourceName)
                .put("title", title)
                .put("path", path)
                .put("relativePath", relativePath())
                .put("folderPath", folderPath())
                .put("location", locationLabel())
                .put("size", size)
                .put("mimeType", mimeType)
                .put("ready", ready);
    }

    /** The TorBox download/torrent name shown as the first folder level. */
    public String displaySourceName() {
        String value = sourceName.trim();
        if (value.isEmpty()) value = torrentId >= 0 ? "TorBox download " + torrentId : "TorBox download";
        value = value.replace('\\', '∕').replace('/', '∕');
        return value.trim();
    }

    /** File path inside the TorBox download, normalized to forward slashes. */
    public String relativePath() {
        String value = normalizePath(path);
        if (value.isEmpty()) value = normalizePath(title);

        // Some TorBox responses repeat the download name as the first path segment.
        // Remove that one redundant segment, but never remove a single-file name.
        int slash = value.indexOf('/');
        if (slash > 0) {
            String first = value.substring(0, slash).trim();
            String rawSource = normalizePath(sourceName);
            String sourceLeaf = rawSource;
            int sourceSlash = sourceLeaf.lastIndexOf('/');
            if (sourceSlash >= 0) sourceLeaf = sourceLeaf.substring(sourceSlash + 1);
            if (!sourceLeaf.isEmpty() && first.equalsIgnoreCase(sourceLeaf)) {
                value = value.substring(slash + 1);
            }
        }
        return value;
    }

    public String folderPath() {
        String relative = relativePath();
        int slash = relative.lastIndexOf('/');
        return slash > 0 ? relative.substring(0, slash) : "";
    }

    public String fileName() {
        String relative = relativePath();
        int slash = relative.lastIndexOf('/');
        return slash >= 0 ? relative.substring(slash + 1) : relative;
    }

    public String locationLabel() {
        String folder = folderPath();
        return folder.isEmpty() ? displaySourceName() : displaySourceName() + " / " + folder;
    }

    public String shortLocationLabel() {
        String folder = folderPath();
        if (folder.isEmpty()) return displaySourceName();
        int slash = folder.lastIndexOf('/');
        String leaf = slash >= 0 ? folder.substring(slash + 1) : folder;
        return displaySourceName() + " / " + leaf;
    }

    public String searchableText() {
        return (title + " " + sourceName + " " + path + " " + relativePath() + " " + locationLabel())
                .toLowerCase(Locale.US);
    }

    public static boolean isPlayablePath(String path) {
        if (path == null) return false;
        String value = path.toLowerCase(Locale.US);
        return value.endsWith(".mp4") || value.endsWith(".m4v")
                || value.endsWith(".webm") || value.endsWith(".mov")
                || value.endsWith(".mkv");
    }

    public static String mimeFor(String path) {
        String value = path == null ? "" : path.toLowerCase(Locale.US);
        if (value.endsWith(".webm")) return "video/webm";
        if (value.endsWith(".mkv")) return "video/x-matroska";
        if (value.endsWith(".mov")) return "video/quicktime";
        return "video/mp4";
    }

    public static String displayName(String path) {
        if (path == null || path.trim().isEmpty()) return "Untitled video";
        String normalized = normalizePath(path);
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public static String normalizePath(String input) {
        if (input == null) return "";
        String value = input.trim().replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        while (value.contains("//")) value = value.replace("//", "/");
        return value;
    }

    @Override public String toString() {
        String value = title + " | " + shortLocationLabel();
        return ready ? value : value + " (preparing)";
    }
}
