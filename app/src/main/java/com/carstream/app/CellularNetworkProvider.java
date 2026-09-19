package com.carstream.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Watches cellular availability but deliberately leaves each upstream HTTP socket on
 * Android's ordinary default Internet route. A local-only hotspot has no Internet route,
 * so Android normally keeps app-originated Internet traffic on mobile data. Avoiding
 * Network.openConnection() also avoids vendor-specific EPERM failures seen on Samsung.
 */
public final class CellularNetworkProvider {
    private final ConnectivityManager connectivityManager;
    private final Object networkLock = new Object();
    private volatile Network cellularNetwork;
    private ConnectivityManager.NetworkCallback callback;

    public CellularNetworkProvider(Context context) {
        connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public synchronized void start() {
        if (callback != null || connectivityManager == null) return;
        EventLogger.info("Network", "Starting Internet route monitor");
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build();
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                promoteIfUsable(network);
                EventLogger.info("Network", "Mobile-data route became available");
                signalNetworkChange();
            }

            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                boolean usable = isUsableCellular(capabilities);
                if (usable) cellularNetwork = network;
                else if (network.equals(cellularNetwork)) cellularNetwork = null;
                signalNetworkChange();
            }

            @Override public void onLost(Network network) {
                if (network.equals(cellularNetwork)) cellularNetwork = null;
                EventLogger.warn("Network", "Mobile-data route was lost");
                signalNetworkChange();
            }
        };
        try {
            connectivityManager.requestNetwork(request, callback);
        } catch (SecurityException error) {
            EventLogger.warn("Network", "Could not subscribe to mobile-data changes; ordinary routing will still be used", error);
            callback = null;
        }
    }

    public synchronized void stop() {
        if (callback != null && connectivityManager != null) {
            try { connectivityManager.unregisterNetworkCallback(callback); }
            catch (Exception error) { EventLogger.warn("Network", "Could not unregister Internet route monitor", error); }
        }
        callback = null;
        cellularNetwork = null;
        EventLogger.info("Network", "Internet route monitor stopped");
    }

    public HttpURLConnection open(URL url, boolean preferCellular) throws IOException {
        if (url == null) throw new IOException("Missing upstream URL");
        if (preferCellular) waitForOrdinaryInternetRoute();

        // Do not use Network.openConnection() here. Some Samsung builds reject the
        // per-socket bind with EPERM even though normal phone Internet is working.
        EventLogger.debug("Network", "Opening phone-originated request over "
                + currentRouteName() + ": " + safeEndpoint(url));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(true);
        return connection;
    }

    public boolean hasCellularNetwork() {
        return findUsableCellularNetwork() != null || activeTransportIs(NetworkCapabilities.TRANSPORT_CELLULAR);
    }

    public String getRouteDescription() { return routeSummary(); }

    public String routeSummary() {
        if (connectivityManager == null) return "Internet route: unavailable";
        Network active = connectivityManager.getActiveNetwork();
        NetworkCapabilities capabilities = active == null ? null : connectivityManager.getNetworkCapabilities(active);
        if (capabilities == null) {
            return hasCellularNetwork() ? "Internet route: mobile data available" : "Internet route: not ready";
        }
        boolean internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        if (internet && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return "Internet route: ordinary mobile data";
        }
        if (internet && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "Internet route: ordinary Wi-Fi";
        }
        if (internet && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return "Internet route: ordinary Ethernet";
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && hasCellularNetwork()) {
            return "Local CarStream Wi-Fi plus ordinary mobile-data upstream";
        }
        return hasCellularNetwork() ? "Internet route: mobile data available" : "Internet route: Android default network";
    }

    private void waitForOrdinaryInternetRoute() throws IOException {
        if (hasUsableDefaultInternet()) return;
        long deadline = System.currentTimeMillis() + 8_000L;
        synchronized (networkLock) {
            while (!hasUsableDefaultInternet() && System.currentTimeMillis() < deadline) {
                try { networkLock.wait(250L); }
                catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for the phone's Internet connection", error);
                }
            }
        }
        if (!hasUsableDefaultInternet()) {
            throw new IOException("The phone does not currently have an Internet route. Keep mobile data enabled and try again.");
        }
    }

    private boolean hasUsableDefaultInternet() {
        if (connectivityManager == null) return false;
        try {
            Network active = connectivityManager.getActiveNetwork();
            NetworkCapabilities capabilities = active == null ? null : connectivityManager.getNetworkCapabilities(active);
            if (capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return true;
            }
            // A local-only hotspot can be visible to Android without becoming the Internet
            // default. Mobile data may still be the usable upstream route in that state.
            return findUsableCellularNetwork() != null;
        } catch (SecurityException ignored) {
            return findUsableCellularNetwork() != null;
        }
    }

    private String currentRouteName() {
        if (connectivityManager == null) return "Android default route";
        try {
            Network active = connectivityManager.getActiveNetwork();
            NetworkCapabilities capabilities = active == null ? null : connectivityManager.getNetworkCapabilities(active);
            if (capabilities == null) return "Android default route";
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "ordinary mobile data";
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "ordinary Wi-Fi";
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ordinary Ethernet";
        } catch (SecurityException ignored) { }
        return "Android default route";
    }

    private boolean activeTransportIs(int transport) {
        if (connectivityManager == null) return false;
        try {
            Network active = connectivityManager.getActiveNetwork();
            NetworkCapabilities capabilities = active == null ? null : connectivityManager.getNetworkCapabilities(active);
            return capabilities != null && capabilities.hasTransport(transport)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (SecurityException ignored) { return false; }
    }

    private void promoteIfUsable(Network network) {
        if (network == null || connectivityManager == null) return;
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        if (isUsableCellular(capabilities)) cellularNetwork = network;
    }

    private Network findUsableCellularNetwork() {
        if (connectivityManager == null) return null;
        Network preferred = cellularNetwork;
        if (preferred != null && isUsableCellular(connectivityManager.getNetworkCapabilities(preferred))) return preferred;
        try {
            for (Network network : connectivityManager.getAllNetworks()) {
                if (isUsableCellular(connectivityManager.getNetworkCapabilities(network))) {
                    cellularNetwork = network;
                    return network;
                }
            }
        } catch (SecurityException ignored) { }
        cellularNetwork = null;
        return null;
    }

    private void signalNetworkChange() {
        synchronized (networkLock) { networkLock.notifyAll(); }
    }

    private static boolean isUsableCellular(NetworkCapabilities capabilities) {
        return capabilities != null
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static String safeEndpoint(URL url) {
        return url.getProtocol() + "://" + url.getHost() + url.getPath();
    }
}
