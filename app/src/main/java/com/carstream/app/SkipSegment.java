package com.carstream.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** A skippable section of a video, expressed in milliseconds. */
public final class SkipSegment {
    public static final String RECAP = "recap";
    public static final String INTRO = "intro";
    public static final String CREDITS = "credits";
    public static final String PREVIEW = "preview";

    public final String type;
    public final long startMillis;
    /** A negative value means the section continues to the end of the video. */
    public final long endMillis;
    public final String source;
    public final String label;
    public final double confidence;

    public SkipSegment(String type, long startMillis, long endMillis, String source,
                       String label, double confidence) {
        this.type = normalizeType(type);
        this.startMillis = Math.max(0L, startMillis);
        this.endMillis = endMillis < 0L ? -1L : Math.max(this.startMillis, endMillis);
        this.source = source == null ? "" : source.trim();
        this.label = label == null || label.trim().isEmpty() ? defaultLabel(this.type) : label.trim();
        this.confidence = Math.max(0.0, Math.min(1.0, confidence));
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("type", type)
                .put("startMillis", startMillis)
                .put("endMillis", endMillis)
                .put("startSeconds", startMillis / 1000.0)
                .put("endSeconds", endMillis < 0L ? -1 : endMillis / 1000.0)
                .put("source", source)
                .put("label", label)
                .put("confidence", confidence);
    }

    public static SkipSegment fromJson(JSONObject value) {
        if (value == null) return null;
        String type = value.optString("type", value.optString("segment_type", ""));
        long start = readMillis(value, "startMillis", "start_sec", "startSeconds", "start");
        long end = readMillis(value, "endMillis", "end_sec", "endSeconds", "end");
        if (value.has("endMillis") && value.optLong("endMillis", -1L) < 0L) end = -1L;
        if (end == 0L && CREDITS.equals(normalizeType(type))) end = -1L;
        return new SkipSegment(type, start, end,
                value.optString("source", "saved"),
                value.optString("label", ""),
                value.optDouble("confidence", 1.0));
    }

    public boolean isActive(long positionMillis, long durationMillis) {
        long end = effectiveEnd(durationMillis);
        return positionMillis >= startMillis && (end <= startMillis || positionMillis < end);
    }

    public long effectiveEnd(long durationMillis) {
        if (endMillis >= 0L) return endMillis;
        return durationMillis > startMillis ? durationMillis : startMillis;
    }

    public String key() {
        return type + ":" + startMillis + ":" + endMillis;
    }

    public static String normalizeType(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.US);
        if (clean.contains("recap") || clean.contains("previously") || clean.contains("last time")) return RECAP;
        if (clean.contains("credit") || clean.contains("outro") || clean.equals("ending")
                || clean.equals("ed") || clean.contains("end theme")) return CREDITS;
        if (clean.contains("preview") || clean.contains("next time")) return PREVIEW;
        return INTRO;
    }

    public static String defaultLabel(String type) {
        String clean = normalizeType(type);
        if (RECAP.equals(clean)) return "recap";
        if (CREDITS.equals(clean)) return "credits";
        if (PREVIEW.equals(clean)) return "preview";
        return "intro";
    }

    public static String displayName(String type) {
        String clean = normalizeType(type);
        if (RECAP.equals(clean)) return "Recap";
        if (CREDITS.equals(clean)) return "Credits";
        if (PREVIEW.equals(clean)) return "Preview";
        return "Intro";
    }

    public static long parseTimeMillis(Object value) {
        if (value == null || value == JSONObject.NULL) return 0L;
        if (value instanceof Number) return Math.max(0L, Math.round(((Number) value).doubleValue() * 1000.0));
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return 0L;
        try { return Math.max(0L, Math.round(Double.parseDouble(text) * 1000.0)); }
        catch (NumberFormatException ignored) { }
        String[] parts = text.split(":");
        try {
            double seconds = 0.0;
            for (String part : parts) seconds = seconds * 60.0 + Double.parseDouble(part.trim());
            return Math.max(0L, Math.round(seconds * 1000.0));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static long readMillis(JSONObject value, String millisKey, String... secondsKeys) {
        if (value.has(millisKey)) return value.optLong(millisKey, 0L);
        for (String key : secondsKeys) {
            if (value.has(key)) return parseTimeMillis(value.opt(key));
        }
        return 0L;
    }
}
