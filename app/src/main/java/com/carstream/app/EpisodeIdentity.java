package com.carstream.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Best-effort identity parsed from TorBox folder and file names. */
public final class EpisodeIdentity {
    private static final Pattern SXXEXX = Pattern.compile("(?i)\\bS(?:eason)?[ ._-]*0*(\\d{1,3})[ ._-]*E(?:p(?:isode)?)?[ ._-]*0*(\\d{1,4})\\b");
    private static final Pattern X_STYLE = Pattern.compile("(?i)\\b0*(\\d{1,3})x0*(\\d{1,4})\\b");
    private static final Pattern SEASON_WORD = Pattern.compile("(?i)\\bSeason[ ._-]*0*(\\d{1,3})\\b");
    private static final Pattern EPISODE_WORD = Pattern.compile("(?i)\\b(?:Episode|Ep|E)[ ._-]*0*(\\d{1,4})\\b");
    private static final Pattern YEAR = Pattern.compile("(?:^|[^0-9])((?:19|20)\\d{2})(?:[^0-9]|$)");
    private static final Pattern IMDB = Pattern.compile("(?i)\\btt\\d{7,10}\\b");

    public final String showTitle;
    public final int season;
    public final int episode;
    public final int year;
    public final String imdbId;
    public final long tvmazeId;
    public final double confidence;
    public final String sourceText;

    public EpisodeIdentity(String showTitle, int season, int episode, int year,
                           String imdbId, long tvmazeId, double confidence, String sourceText) {
        this.showTitle = cleanTitle(showTitle);
        this.season = Math.max(0, season);
        this.episode = Math.max(0, episode);
        this.year = year >= 1900 && year <= 2100 ? year : 0;
        this.imdbId = normalizeImdb(imdbId);
        this.tvmazeId = Math.max(-1L, tvmazeId);
        this.confidence = Math.max(0.0, Math.min(1.0, confidence));
        this.sourceText = sourceText == null ? "" : sourceText.trim();
    }

    public static EpisodeIdentity parse(MediaItem item) {
        if (item == null) return new EpisodeIdentity("", 0, 0, 0, "", -1, 0, "");
        String source = item.sourceName == null ? "" : item.sourceName;
        String path = item.relativePath();
        String file = item.fileName();
        String combined = source + " / " + path;

        int season = 0;
        int episode = 0;
        int markerStart = -1;
        Matcher matcher = SXXEXX.matcher(combined);
        if (matcher.find()) {
            season = parseInt(matcher.group(1));
            episode = parseInt(matcher.group(2));
            markerStart = matcher.start();
        } else {
            matcher = X_STYLE.matcher(combined);
            if (matcher.find()) {
                season = parseInt(matcher.group(1));
                episode = parseInt(matcher.group(2));
                markerStart = matcher.start();
            }
        }
        if (season == 0) {
            Matcher seasonMatcher = SEASON_WORD.matcher(combined);
            if (seasonMatcher.find()) season = parseInt(seasonMatcher.group(1));
        }
        if (episode == 0) {
            Matcher episodeMatcher = EPISODE_WORD.matcher(file);
            if (episodeMatcher.find()) episode = parseInt(episodeMatcher.group(1));
        }

        int year = 0;
        Matcher yearMatcher = YEAR.matcher(source + " " + file);
        if (yearMatcher.find()) year = parseInt(yearMatcher.group(1));
        String imdb = "";
        Matcher imdbMatcher = IMDB.matcher(combined);
        if (imdbMatcher.find()) imdb = imdbMatcher.group();

        String titleSource = source;
        if (titleSource.trim().isEmpty()) titleSource = markerStart > 0 ? combined.substring(0, markerStart) : file;
        String show = cleanReleaseTitle(titleSource);
        if ((show.isEmpty() || looksTechnical(show)) && markerStart > 0) {
            show = cleanReleaseTitle(combined.substring(0, markerStart));
        }
        double confidence = season > 0 && episode > 0 ? 0.76 : 0.35;
        if (!show.isEmpty()) confidence += 0.10;
        if (!imdb.isEmpty()) confidence = 0.98;
        return new EpisodeIdentity(show, season, episode, year, imdb, -1,
                Math.min(1.0, confidence), combined);
    }

    public EpisodeIdentity withLookup(String resolvedTitle, int resolvedYear,
                                      String resolvedImdb, long resolvedTvmaze, double lookupConfidence) {
        return new EpisodeIdentity(resolvedTitle == null || resolvedTitle.trim().isEmpty() ? showTitle : resolvedTitle,
                season, episode, resolvedYear > 0 ? resolvedYear : year,
                resolvedImdb, resolvedTvmaze, Math.max(confidence, lookupConfidence), sourceText);
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("showTitle", showTitle)
                .put("season", season)
                .put("episode", episode)
                .put("year", year)
                .put("imdbId", imdbId)
                .put("tvmazeId", tvmazeId)
                .put("confidence", confidence)
                .put("sourceText", sourceText)
                .put("description", description());
    }

    public static EpisodeIdentity fromJson(JSONObject value) {
        if (value == null) return null;
        return new EpisodeIdentity(value.optString("showTitle", ""),
                value.optInt("season", 0), value.optInt("episode", 0), value.optInt("year", 0),
                value.optString("imdbId", ""), value.optLong("tvmazeId", -1L),
                value.optDouble("confidence", 0.0), value.optString("sourceText", ""));
    }

    public boolean canLookupOnline() {
        return !showTitle.isEmpty() && season > 0 && episode > 0;
    }

    public String showKey() {
        return normalizeKey(showTitle);
    }

    public String episodeKey() {
        return showKey() + ":s" + season + ":e" + episode;
    }

    public String description() {
        StringBuilder value = new StringBuilder(showTitle.isEmpty() ? "Unknown show" : showTitle);
        if (season > 0 && episode > 0) {
            value.append(" • S").append(String.format(Locale.US, "%02d", season))
                    .append("E").append(String.format(Locale.US, "%02d", episode));
        }
        if (year > 0) value.append(" • ").append(year);
        if (!imdbId.isEmpty()) value.append(" • ").append(imdbId);
        return value.toString();
    }

    public static String normalizeKey(String value) {
        return cleanTitle(value).toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String cleanReleaseTitle(String value) {
        String clean = value == null ? "" : value.replace('\\', ' ').replace('/', ' ');
        clean = clean.replaceAll("(?i)\\bS(?:eason)?[ ._-]*\\d{1,3}.*$", " ");
        clean = clean.replaceAll("(?i)\\b(?:19|20)\\d{2}\\b.*$", " ");
        clean = clean.replaceAll("(?i)\\b(?:1080p|720p|2160p|4k|web[- .]?dl|webrip|bluray|brrip|hdtv|x264|x265|h264|h265|hevc|aac|dts|remux|complete|completed)\\b", " ");
        clean = clean.replaceAll("[._-]+", " ").replaceAll("\\s+", " ").trim();
        return cleanTitle(clean);
    }

    private static String cleanTitle(String value) {
        String clean = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (clean.length() > 120) clean = clean.substring(0, 120).trim();
        return clean;
    }

    private static boolean looksTechnical(String value) {
        String clean = value == null ? "" : value.trim();
        return clean.matches("(?i)[a-f0-9]{16,}") || "completed".equalsIgnoreCase(clean);
    }

    private static String normalizeImdb(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.US);
        return clean.matches("tt\\d{7,10}") ? clean : "";
    }

    private static int parseInt(String value) {
        try { return Integer.parseInt(value); }
        catch (Exception ignored) { return 0; }
    }
}
