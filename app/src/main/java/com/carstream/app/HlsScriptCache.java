package com.carstream.app;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Downloads and caches a pinned hls.js browser bundle through the phone.
 * Nearby browsers cannot reach the public Internet on a local-only hotspot,
 * so CarStream serves this cached copy from its own local HTTP server.
 */
public final class HlsScriptCache {
    private static final String VERSION = "1.7.0";
    private static final String[] URL_VALUES = new String[] {
            "https://cdn.jsdelivr.net/npm/hls.js@" + VERSION + "/dist/hls.light.min.js",
            "https://unpkg.com/hls.js@" + VERSION + "/dist/hls.light.min.js"
    };
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MIN_BYTES = 180 * 1024;

    private final Context context;
    private final CellularNetworkProvider networks;
    private final Object lock = new Object();
    private volatile byte[] memory;
    private volatile String status = "Player helper has not been cached yet";

    public HlsScriptCache(Context context, CellularNetworkProvider networks) {
        this.context = context == null ? null : context.getApplicationContext();
        this.networks = networks;
    }

    public String getVersion() { return VERSION; }
    public String getStatus() { return status; }

    public byte[] getScript() throws IOException {
        byte[] cached = memory;
        if (valid(cached)) return cached;
        synchronized (lock) {
            cached = memory;
            if (valid(cached)) return cached;
            cached = readDisk();
            if (valid(cached)) {
                memory = cached;
                status = "Chrome player helper " + VERSION + " loaded from this phone";
                return cached;
            }
            cached = download();
            memory = cached;
            return cached;
        }
    }

    public void prefetch() {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                try { getScript(); }
                catch (Exception error) {
                    status = "Could not cache the Chrome player helper: " + safeMessage(error);
                    EventLogger.warn(context, "Browser", status, error);
                }
            }
        }, "CarStream-hlsjs");
        thread.setDaemon(true);
        thread.start();
    }

    private byte[] download() throws IOException {
        if (context == null || networks == null) throw new IOException("Browser compatibility library is unavailable");
        IOException last = null;
        for (String urlValue : URL_VALUES) {
            HttpURLConnection connection = null;
            InputStream input = null;
            try {
                status = "Downloading the Chrome player helper through the phone";
                EventLogger.info(context, "Browser", status + " from " + new URL(urlValue).getHost());
                connection = networks.open(new URL(urlValue), false);
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(45_000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "CarStream/" + BuildInfo.VERSION_NAME + " Android");
                connection.setRequestProperty("Accept", "application/javascript,text/javascript,*/*;q=0.8");
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) {
                    String detail = IoUtils.readUtf8(connection.getErrorStream(), 64 * 1024);
                    throw new IOException("Player-helper download returned HTTP " + code
                            + (detail.length() == 0 ? "" : ": " + detail));
                }
                input = connection.getInputStream();
                byte[] value = IoUtils.readAll(input, MAX_BYTES);
                if (!valid(value)) throw new IOException("The downloaded player helper was incomplete or invalid");
                writeDisk(value);
                status = "Chrome player helper " + VERSION + " cached on this phone";
                EventLogger.info(context, "Browser", status + " (" + value.length + " bytes)");
                return value;
            } catch (IOException error) {
                last = error;
                EventLogger.warn(context, "Browser", "Player-helper mirror failed: " + safeMessage(error));
            } finally {
                if (input != null) try { input.close(); } catch (Exception ignored) { }
                if (connection != null) connection.disconnect();
            }
        }
        throw last == null ? new IOException("Could not download the Chrome player helper") : last;
    }

    private byte[] readDisk() {
        File file = file();
        if (file == null || !file.isFile() || file.length() < MIN_BYTES || file.length() > MAX_BYTES) return null;
        FileInputStream input = null;
        try {
            input = new FileInputStream(file);
            byte[] value = IoUtils.readAll(input, MAX_BYTES);
            return valid(value) ? value : null;
        } catch (Exception error) {
            EventLogger.warn(context, "Browser", "Could not read the cached Chrome player helper", error);
            return null;
        } finally {
            if (input != null) try { input.close(); } catch (Exception ignored) { }
        }
    }

    private void writeDisk(byte[] value) {
        File file = file();
        if (file == null) return;
        File parent = file.getParentFile();
        File temp = new File(parent, file.getName() + ".tmp");
        FileOutputStream output = null;
        try {
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
            output = new FileOutputStream(temp, false);
            output.write(value);
            output.flush();
            output.close();
            output = null;
            if (file.exists() && !file.delete()) return;
            if (!temp.renameTo(file)) {
                FileOutputStream direct = new FileOutputStream(file, false);
                try { direct.write(value); direct.flush(); }
                finally { direct.close(); }
                temp.delete();
            }
        } catch (Exception error) {
            EventLogger.warn(context, "Browser", "Could not save the Chrome player helper", error);
        } finally {
            if (output != null) try { output.close(); } catch (Exception ignored) { }
            if (temp.exists() && !file.exists()) temp.delete();
        }
    }

    private File file() {
        if (context == null) return null;
        return new File(new File(context.getFilesDir(), "browser"), "hls-light-" + VERSION + ".min.js");
    }

    private static boolean valid(byte[] value) {
        if (value == null || value.length < MIN_BYTES || value.length > MAX_BYTES) return false;
        int sampleLength = Math.min(value.length, 48 * 1024);
        String sample = new String(value, 0, sampleLength, StandardCharsets.UTF_8);
        return sample.contains("Hls") || sample.contains("hls.js") || sample.contains("HLS");
    }

    public static byte[] failureScript(String detail) {
        String safe = detail == null ? "The Chrome player helper could not be loaded" : detail
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", " ").replace("\n", " ");
        return ("window.__carstreamHlsLoadError=\"" + safe + "\";window.Hls=window.Hls||null;")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown error";
        String value = error.getMessage();
        return value == null || value.trim().length() == 0 ? error.getClass().getSimpleName() : value.trim();
    }
}
