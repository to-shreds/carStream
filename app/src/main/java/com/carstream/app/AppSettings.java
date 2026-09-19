package com.carstream.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

public final class AppSettings {
    private static final String PREFS = "carstream_settings";
    private static final String REMOTE_ENABLED = "remote_enabled";
    private static final String REMOTE_MANIFEST = "remote_manifest";
    private static final String PROJECT_URL = "project_url";
    private static final String WIFI_NAME = "wifi_name";
    private static final String WIFI_PASSWORD = "wifi_password";
    private static final String PAIRING_CODE = "pairing_code";
    private static final String STREMIO_TOKEN = "stremio_token";
    private static final String PORT = "port";
    private static final String BROWSER_LAST_URL = "browser_last_url";
    private static final String BROWSER_HOME = "browser_home";
    private static final String AUTO_NEXT_PHONE = "auto_next_phone";
    private static final String ONLINE_SKIP_LOOKUP = "online_skip_lookup";
    private static final String INTRO_SKIP_MODE = "intro_skip_mode";
    private static final String RECAP_SKIP_MODE = "recap_skip_mode";
    private static final String CREDITS_SKIP_MODE = "credits_skip_mode";
    private static final String SKIP_COUNTDOWN_SECONDS = "skip_countdown_seconds";

    public static final String SKIP_BUTTON = "BUTTON";
    public static final String SKIP_AUTO = "AUTO";
    public static final String SKIP_AUTO_NEXT = "AUTO_NEXT";
    public static final String SKIP_OFF = "OFF";

    private final SharedPreferences preferences;

    public AppSettings(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ensureDefaults();
    }

    private void ensureDefaults() {
        SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;
        if (!preferences.contains(WIFI_NAME)) {
            editor.putString(WIFI_NAME, "CarStream");
            changed = true;
        }
        if (!preferences.contains(WIFI_PASSWORD)) {
            editor.putString(WIFI_PASSWORD, "carstream");
            changed = true;
        }
        if (!preferences.contains(PAIRING_CODE)) {
            editor.putString(PAIRING_CODE, generatePairingCode());
            changed = true;
        }
        if (!preferences.contains(PORT)) {
            editor.putInt(PORT, 8877);
            changed = true;
        }
        if (!preferences.contains(BROWSER_HOME)) {
            editor.putString(BROWSER_HOME, "https://duckduckgo.com/");
            changed = true;
        }
        if (!preferences.contains(ONLINE_SKIP_LOOKUP)) {
            editor.putBoolean(ONLINE_SKIP_LOOKUP, true);
            changed = true;
        }
        if (!preferences.contains(INTRO_SKIP_MODE)) {
            editor.putString(INTRO_SKIP_MODE, SKIP_BUTTON);
            changed = true;
        }
        if (!preferences.contains(RECAP_SKIP_MODE)) {
            editor.putString(RECAP_SKIP_MODE, SKIP_BUTTON);
            changed = true;
        }
        if (!preferences.contains(CREDITS_SKIP_MODE)) {
            editor.putString(CREDITS_SKIP_MODE, SKIP_BUTTON);
            changed = true;
        }
        if (!preferences.contains(SKIP_COUNTDOWN_SECONDS)) {
            editor.putInt(SKIP_COUNTDOWN_SECONDS, 5);
            changed = true;
        }
        if (changed) editor.apply();
    }

    public boolean isRemoteClientEnabled() {
        return preferences.getBoolean(REMOTE_ENABLED, false);
    }

    public void setRemoteClientEnabled(boolean value) {
        preferences.edit().putBoolean(REMOTE_ENABLED, value).apply();
    }

    public String getRemoteManifestUrl() {
        return preferences.getString(REMOTE_MANIFEST, "");
    }

    public void setRemoteManifestUrl(String value) {
        preferences.edit().putString(REMOTE_MANIFEST, clean(value)).apply();
    }

    public String getProjectUrl() {
        return preferences.getString(PROJECT_URL, "");
    }

    public void setProjectUrl(String value) {
        preferences.edit().putString(PROJECT_URL, clean(value)).apply();
    }

    public String getWifiName() {
        String value = clean(preferences.getString(WIFI_NAME, "CarStream"));
        if (value.isEmpty()) return "CarStream";
        return value.length() > 24 ? value.substring(0, 24) : value;
    }

    public void setWifiName(String value) {
        String clean = clean(value);
        if (clean.isEmpty()) clean = "CarStream";
        if (clean.length() > 24) clean = clean.substring(0, 24);
        preferences.edit().putString(WIFI_NAME, clean).apply();
    }

    public String getWifiPassword() {
        String value = preferences.getString(WIFI_PASSWORD, "carstream");
        return isValidWifiPassword(value) ? value : "carstream";
    }

    public void setWifiPassword(String value) {
        String clean = clean(value);
        if (!isValidWifiPassword(clean)) {
            throw new IllegalArgumentException("The preferred network password must be 8 to 20 characters");
        }
        preferences.edit().putString(WIFI_PASSWORD, clean).apply();
    }

    public String generateAndSaveWifiPassword() {
        SecureRandom random = new SecureRandom();
        String value = "car" + String.format(java.util.Locale.US, "%05d", random.nextInt(100000));
        preferences.edit().putString(WIFI_PASSWORD, value).apply();
        return value;
    }

    public String getPairingCode() {
        String value = preferences.getString(PAIRING_CODE, "");
        if (!isValidPairingCode(value)) return regeneratePairingCode();
        return value;
    }

    public String regeneratePairingCode() {
        String value = generatePairingCode();
        preferences.edit().putString(PAIRING_CODE, value).remove(STREMIO_TOKEN).apply();
        return value;
    }

    public synchronized String getStremioToken() {
        String value = preferences.getString(STREMIO_TOKEN, "");
        if (value.length() != 32) {
            value = java.util.UUID.randomUUID().toString().replace("-", "");
            preferences.edit().putString(STREMIO_TOKEN, value).apply();
        }
        return value;
    }

    /** Kept for compatibility with older source references. */
    public String getAccessToken() { return getPairingCode(); }

    /** Kept for compatibility with older source references. */
    public String regenerateAccessToken() { return regeneratePairingCode(); }


    public String getBrowserHome() {
        String value = clean(preferences.getString(BROWSER_HOME, "https://duckduckgo.com/"));
        if (!(value.startsWith("https://") || value.startsWith("http://"))) {
            return "https://duckduckgo.com/";
        }
        return value;
    }

    public void setBrowserHome(String value) {
        String clean = clean(value);
        if (clean.length() == 0) clean = "https://duckduckgo.com/";
        if (!(clean.startsWith("https://") || clean.startsWith("http://"))) {
            throw new IllegalArgumentException("The browser home page must start with http:// or https://");
        }
        preferences.edit().putString(BROWSER_HOME, clean).apply();
    }


    public String getBrowserLastUrl() {
        String value = clean(preferences.getString(BROWSER_LAST_URL, "https://duckduckgo.com/"));
        return value.startsWith("https://") || value.startsWith("http://")
                ? value : "https://duckduckgo.com/";
    }

    public void setBrowserLastUrl(String value) {
        String clean = clean(value);
        if (clean.startsWith("https://") || clean.startsWith("http://")) {
            preferences.edit().putString(BROWSER_LAST_URL, clean).apply();
        }
    }

    public boolean isAutoNextOnPhoneEnabled() {
        return preferences.getBoolean(AUTO_NEXT_PHONE, false);
    }

    public void setAutoNextOnPhoneEnabled(boolean value) {
        preferences.edit().putBoolean(AUTO_NEXT_PHONE, value).apply();
    }


    public boolean isOnlineSkipLookupEnabled() {
        return preferences.getBoolean(ONLINE_SKIP_LOOKUP, true);
    }

    public void setOnlineSkipLookupEnabled(boolean value) {
        preferences.edit().putBoolean(ONLINE_SKIP_LOOKUP, value).apply();
    }

    public String getIntroSkipMode() { return validSkipMode(preferences.getString(INTRO_SKIP_MODE, SKIP_BUTTON), false); }
    public void setIntroSkipMode(String value) { preferences.edit().putString(INTRO_SKIP_MODE, validSkipMode(value, false)).apply(); }
    public String getRecapSkipMode() { return validSkipMode(preferences.getString(RECAP_SKIP_MODE, SKIP_BUTTON), false); }
    public void setRecapSkipMode(String value) { preferences.edit().putString(RECAP_SKIP_MODE, validSkipMode(value, false)).apply(); }
    public String getCreditsSkipMode() { return validSkipMode(preferences.getString(CREDITS_SKIP_MODE, SKIP_BUTTON), true); }
    public void setCreditsSkipMode(String value) { preferences.edit().putString(CREDITS_SKIP_MODE, validSkipMode(value, true)).apply(); }

    public int getSkipCountdownSeconds() {
        int value = preferences.getInt(SKIP_COUNTDOWN_SECONDS, 5);
        return value == 3 || value == 8 ? value : 5;
    }

    public void setSkipCountdownSeconds(int value) {
        preferences.edit().putInt(SKIP_COUNTDOWN_SECONDS, value == 3 || value == 8 ? value : 5).apply();
    }

    public String skipModeForType(String type) {
        String clean = SkipSegment.normalizeType(type);
        if (SkipSegment.RECAP.equals(clean)) return getRecapSkipMode();
        if (SkipSegment.CREDITS.equals(clean) || SkipSegment.PREVIEW.equals(clean)) return getCreditsSkipMode();
        return getIntroSkipMode();
    }

    private static String validSkipMode(String value, boolean credits) {
        String clean = value == null ? "" : value.trim().toUpperCase(java.util.Locale.US);
        if (SKIP_OFF.equals(clean) || SKIP_BUTTON.equals(clean)) return clean;
        if (credits && SKIP_AUTO_NEXT.equals(clean)) return clean;
        if (!credits && SKIP_AUTO.equals(clean)) return clean;
        return SKIP_BUTTON;
    }

    public int getPort() {
        int value = preferences.getInt(PORT, 8877);
        return value >= 1024 && value <= 65535 ? value : 8877;
    }

    private static boolean isValidWifiPassword(String value) {
        return value != null && value.length() >= 8 && value.length() <= 20;
    }

    private static boolean isValidPairingCode(String value) {
        return value != null && value.matches("[0-9]{4}");
    }

    private static String generatePairingCode() {
        int number = new SecureRandom().nextInt(10000);
        return String.format(java.util.Locale.US, "%04d", number);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
