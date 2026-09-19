package com.carstream.app;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class ClientBundleManager {
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private static final int MAX_ASSET_BYTES = 2 * 1024 * 1024;
    private static final Set<String> REQUIRED_FILES = new HashSet<>(
            Arrays.asList("index.html", "app.js", "styles.css"));

    private final Context context;
    private final CellularNetworkProvider networks;
    private final AppSettings settings;
    private final File currentDirectory;
    private final File stagingDirectory;

    private volatile String status = "Using the built-in tablet client";
    private volatile String version = "embedded";

    public ClientBundleManager(Context context, CellularNetworkProvider networks, AppSettings settings) {
        this.context = context.getApplicationContext();
        this.networks = networks;
        this.settings = settings;
        File base = new File(context.getFilesDir(), "client-bundle");
        currentDirectory = new File(base, "current");
        stagingDirectory = new File(base, "staging");
        loadCachedStatus();
    }

    public synchronized void refresh(boolean requireCellular) throws Exception {
        String manifestText = settings.getRemoteManifestUrl();
        if (!settings.isRemoteClientEnabled()) {
            status = "Remote bundle disabled; using the built-in tablet client";
            version = "embedded";
            return;
        }
        if (manifestText.isEmpty()) throw new IOException("Enter a remote client manifest URL first");

        URL manifestUrl = requireHttps(manifestText);
        status = "Downloading remote client manifest...";
        HttpURLConnection manifestConnection = networks.open(manifestUrl, requireCellular);
        configure(manifestConnection);
        int manifestStatus = manifestConnection.getResponseCode();
        String manifestBody = IoUtils.readUtf8(
                manifestStatus >= 400 ? manifestConnection.getErrorStream() : manifestConnection.getInputStream(),
                MAX_MANIFEST_BYTES);
        manifestConnection.disconnect();
        if (manifestStatus < 200 || manifestStatus >= 300) {
            throw new IOException("Client manifest returned HTTP " + manifestStatus);
        }

        JSONObject manifest = new JSONObject(manifestBody);
        String nextVersion = manifest.optString("version", "unversioned");
        JSONObject files = manifest.optJSONObject("files");
        if (files == null) throw new IOException("Manifest must contain a files object");

        deleteRecursively(stagingDirectory);
        if (!stagingDirectory.mkdirs()) throw new IOException("Could not create client bundle staging directory");

        try {
            for (String name : REQUIRED_FILES) {
                JSONObject entry = files.optJSONObject(name);
                if (entry == null) throw new IOException("Manifest is missing " + name);
                String urlText = entry.optString("url", "").trim();
                String expectedSha = entry.optString("sha256", "").trim().toLowerCase();
                if (urlText.isEmpty() || expectedSha.length() != 64) {
                    throw new IOException(name + " must include an HTTPS url and SHA-256 checksum");
                }
                URL assetUrl = new URL(manifestUrl, urlText);
                requireHttps(assetUrl.toString());
                status = "Downloading " + name + "...";
                HttpURLConnection connection = networks.open(assetUrl, requireCellular);
                configure(connection);
                int responseCode = connection.getResponseCode();
                byte[] data = IoUtils.readAll(
                        responseCode >= 400 ? connection.getErrorStream() : connection.getInputStream(),
                        MAX_ASSET_BYTES);
                connection.disconnect();
                if (responseCode < 200 || responseCode >= 300) {
                    throw new IOException(name + " returned HTTP " + responseCode);
                }
                String actualSha = IoUtils.sha256(data);
                if (!IoUtils.constantTimeEquals(expectedSha, actualSha)) {
                    throw new IOException(name + " failed SHA-256 verification");
                }
                IoUtils.writeAtomically(new File(stagingDirectory, name), data);
            }

            IoUtils.writeAtomically(new File(stagingDirectory, "version.txt"),
                    nextVersion.getBytes(StandardCharsets.UTF_8));

            File previousDirectory = new File(currentDirectory.getParentFile(), "previous");
            deleteRecursively(previousDirectory);
            boolean hadCurrent = currentDirectory.exists();
            if (hadCurrent && !currentDirectory.renameTo(previousDirectory)) {
                throw new IOException("Could not preserve the existing client bundle");
            }
            if (!stagingDirectory.renameTo(currentDirectory)) {
                if (hadCurrent) previousDirectory.renameTo(currentDirectory);
                throw new IOException("Could not activate the downloaded client bundle");
            }
            deleteRecursively(previousDirectory);
            version = nextVersion;
            status = "Remote client bundle " + nextVersion + " is cached and active";
        } catch (Exception e) {
            deleteRecursively(stagingDirectory);
            status = "Remote bundle refresh failed; existing client remains active";
            throw e;
        }
    }

    public byte[] getAsset(String name) throws IOException {
        if (!REQUIRED_FILES.contains(name)) throw new IOException("Unknown client asset");
        if (settings.isRemoteClientEnabled()) {
            File cached = new File(currentDirectory, name);
            if (cached.isFile()) return IoUtils.readFile(cached, MAX_ASSET_BYTES);
        }
        try (InputStream input = context.getAssets().open("client/" + name)) {
            return IoUtils.readAll(input, MAX_ASSET_BYTES);
        }
    }

    public String getStatus() { return status; }
    public String getVersion() { return version; }

    private void loadCachedStatus() {
        File marker = new File(currentDirectory, "version.txt");
        if (marker.isFile()) {
            try {
                version = new String(IoUtils.readFile(marker, 4096), StandardCharsets.UTF_8).trim();
                status = "Cached remote client bundle " + version + " is available";
            } catch (IOException ignored) { }
        }
    }

    private static void configure(HttpURLConnection connection) throws IOException {
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json,text/html,application/javascript,text/css,*/*");
        connection.setRequestProperty("User-Agent", "CarStream/0.2 Android");
    }

    private static URL requireHttps(String value) throws IOException {
        URL url = new URL(value);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Remote client URLs must use HTTPS");
        }
        return url;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }
}
