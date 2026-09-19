package com.carstream.app;

import android.content.Context;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Compatibility wrapper retaining the original class name while using Android's
 * LocalOnlyHotspot API. The network has no Internet route. On Android 16 and
 * later, CarStream asks Android for a short, memorable SSID and password. If the
 * phone does not support configurable local-only hotspots, Android supplies the
 * credentials and the app displays the actual values.
 */
public final class WifiDirectController {
    public interface Listener {
        void onWifiStatus(String status);
        void onGroupReady(String networkName, String passphrase, String ownerAddress);
        void onWifiStopped();
    }

    private static final int ADDRESS_RETRY_LIMIT = 40;
    private static final long ADDRESS_RETRY_DELAY_MS = 250L;

    private final Context context;
    private final WifiManager wifiManager;
    private final Listener listener;
    private final Handler handler;
    private final Set<String> addressesBeforeStart = new HashSet<String>();

    private WifiManager.LocalOnlyHotspotReservation reservation;
    private boolean running;
    private String networkName = "";
    private String passphrase = "";
    private String preferredName = "CarStream";
    private String preferredPassphrase = "carstream";

    public WifiDirectController(Context context, Listener listener) {
        Context application = context == null ? null : context.getApplicationContext();
        this.context = application == null ? context : application;
        this.listener = listener;
        Object service = this.context == null ? null : this.context.getSystemService(Context.WIFI_SERVICE);
        this.wifiManager = service instanceof WifiManager ? (WifiManager) service : null;
        this.handler = new Handler(Looper.getMainLooper());
    }

    public synchronized void start(String requestedName, String requestedPassphrase) {
        EventLogger.info(context, "Network", "Local-only hotspot start requested");
        if (running) {
            if (reservation != null) publishWhenAddressReady(0);
            else listener.onWifiStatus("The private network is still starting...");
            return;
        }
        if (wifiManager == null) {
            listener.onWifiStatus("This phone cannot create an app-only Wi-Fi network");
            return;
        }

        preferredName = cleanName(requestedName);
        EventLogger.info("Hotspot", "Starting Android local-only hotspot; preferredName=" + preferredName);
        preferredPassphrase = cleanPassword(requestedPassphrase);
        running = true;
        networkName = "";
        passphrase = "";
        addressesBeforeStart.clear();
        addressesBeforeStart.addAll(snapshotPrivateIpv4Addresses());
        listener.onWifiStatus("Starting the private network...");

        WifiManager.LocalOnlyHotspotCallback callback = createCallback();
        if (Build.VERSION.SDK_INT >= 36 && tryStartConfigured(callback)) {
            EventLogger.info(context, "Network", "Requested custom local-only hotspot credentials");
            return;
        }
        EventLogger.info(context, "Network", "Using Android-generated local-only hotspot credentials");
        startFrameworkGenerated(callback);
    }

    private WifiManager.LocalOnlyHotspotCallback createCallback() {
        return new WifiManager.LocalOnlyHotspotCallback() {
            @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation value) {
                synchronized (WifiDirectController.this) {
                    if (!running) {
                        try { value.close(); } catch (Exception ignored) { }
                        return;
                    }
                    reservation = value;
                    readCredentials(value);
                    EventLogger.info("Hotspot", "Local-only hotspot started; Android supplied network name=" + networkName);
                    EventLogger.info(context, "Network", "Local-only hotspot reservation started");
                }
                listener.onWifiStatus("Private network started. Finishing setup...");
                publishWhenAddressReady(0);
            }

            @Override public void onStopped() {
                synchronized (WifiDirectController.this) {
                    reservation = null;
                    if (!running) return;
                    running = false;
                }
                EventLogger.warn(context, "Network", "Android stopped the local-only hotspot");
                EventLogger.warn("Hotspot", "Android stopped the local-only hotspot");
                listener.onWifiStopped();
            }

            @Override public void onFailed(int reason) {
                synchronized (WifiDirectController.this) {
                    reservation = null;
                    running = false;
                }
                String failure = failureMessage(reason);
                EventLogger.warn(context, "Network", failure);
                listener.onWifiStatus(failure);
            }
        };
    }

    /** Uses reflection so the APK remains installable on older Android versions. */
    private boolean tryStartConfigured(WifiManager.LocalOnlyHotspotCallback callback) {
        try {
            Class<?> builderClass = Class.forName("android.net.wifi.SoftApConfiguration$Builder");
            Object builder = builderClass.getConstructor().newInstance();

            Class<?> wifiSsidClass = Class.forName("android.net.wifi.WifiSsid");
            Method fromBytes = wifiSsidClass.getMethod("fromBytes", byte[].class);
            Object wifiSsid = fromBytes.invoke(null, new Object[] { preferredName.getBytes(StandardCharsets.UTF_8) });
            builderClass.getMethod("setWifiSsid", wifiSsidClass).invoke(builder, wifiSsid);

            Class<?> configClass = Class.forName("android.net.wifi.SoftApConfiguration");
            int wpa2 = configClass.getField("SECURITY_TYPE_WPA2_PSK").getInt(null);
            builderClass.getMethod("setPassphrase", String.class, int.class)
                    .invoke(builder, preferredPassphrase, Integer.valueOf(wpa2));
            Object configuration = builderClass.getMethod("build").invoke(builder);

            Method start = WifiManager.class.getMethod("startLocalOnlyHotspotWithConfiguration",
                    configClass, Executor.class, WifiManager.LocalOnlyHotspotCallback.class);
            Executor executor = new Executor() {
                @Override public void execute(Runnable command) { handler.post(command); }
            };
            start.invoke(wifiManager, configuration, executor, callback);
            EventLogger.info("Hotspot", "Requested configured local-only hotspot through Android 16 API");
            return true;
        } catch (Throwable error) {
            EventLogger.warn(context, "Network", "Custom hotspot credentials were unavailable: " + safeMessage(error));
            return false;
        }
    }

    private void startFrameworkGenerated(WifiManager.LocalOnlyHotspotCallback callback) {
        try {
            EventLogger.info("Hotspot", "Requesting framework-generated local-only hotspot");
            wifiManager.startLocalOnlyHotspot(callback, handler);
        } catch (SecurityException error) {
            EventLogger.error(context, "Network", "Permission error while starting local-only hotspot", error);
            running = false;
            EventLogger.error("Hotspot", "Permission denied while creating local-only hotspot", error);
            listener.onWifiStatus("Allow Location and Nearby devices so CarStream can create the private network");
        } catch (IllegalStateException error) {
            EventLogger.error(context, "Network", "Android rejected local-only hotspot request", error);
            running = false;
            EventLogger.error("Hotspot", "Android rejected the local-only hotspot request", error);
            listener.onWifiStatus("Android rejected the private-network request: " + safeMessage(error));
        } catch (RuntimeException error) {
            EventLogger.error(context, "Network", "Unexpected local-only hotspot startup error", error);
            running = false;
            EventLogger.error("Hotspot", "Could not start the local-only hotspot", error);
            listener.onWifiStatus("Could not start the private network: " + safeMessage(error));
        }
    }

    public synchronized void stop(boolean ignoredRemoveGroup) {
        EventLogger.info(context, "Network", "Local-only hotspot stop requested");
        running = false;
        handler.removeCallbacksAndMessages(null);
        WifiManager.LocalOnlyHotspotReservation current = reservation;
        reservation = null;
        if (current != null) {
            try { current.close(); }
            catch (Exception ignored) { }
        }
        networkName = "";
        passphrase = "";
        EventLogger.info("Hotspot", "Local-only hotspot stopped by CarStream");
        listener.onWifiStopped();
    }

    private synchronized void readCredentials(WifiManager.LocalOnlyHotspotReservation value) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                SoftApConfiguration configuration = value.getSoftApConfiguration();
                if (configuration != null) {
                    networkName = cleanCredential(configuration.getSsid());
                    passphrase = cleanCredential(configuration.getPassphrase());
                }
            } else {
                WifiConfiguration configuration = value.getWifiConfiguration();
                if (configuration != null) {
                    networkName = cleanCredential(configuration.SSID);
                    passphrase = cleanCredential(configuration.preSharedKey);
                }
            }
        } catch (RuntimeException error) {
            listener.onWifiStatus("The network started, but Android did not show its password: " + safeMessage(error));
        }
        if (networkName.length() == 0) networkName = "CarStream";
    }

    private void publishWhenAddressReady(final int attempt) {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                synchronized (WifiDirectController.this) {
                    if (!running || reservation == null) return;
                }
                String address = findHotspotIpv4Address();
                if (address != null) {
                    EventLogger.info(context, "Network", "Local-only hotspot address ready: " + address);
                    EventLogger.info("Hotspot", "Local-only hotspot ready at " + address);
                    listener.onGroupReady(networkName, passphrase, address);
                    return;
                }
                if (attempt < ADDRESS_RETRY_LIMIT) publishWhenAddressReady(attempt + 1);
                else {
                    EventLogger.error("Hotspot", "Local-only hotspot started but no private IPv4 address was found");
                    listener.onWifiStatus("The network is running, but its local address is not ready. Tap Stop, then Start again.");
                }
            }
        }, attempt == 0 ? 150L : ADDRESS_RETRY_DELAY_MS);
    }

    private String findHotspotIpv4Address() {
        Candidate best = null;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return null;
            for (NetworkInterface networkInterface : Collections.list(interfaces)) {
                String interfaceName = networkInterface.getName();
                String lowerName = interfaceName == null ? "" : interfaceName.toLowerCase(Locale.US);
                if (isDefinitelyNotHotspotInterface(lowerName)) continue;
                try {
                    if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                } catch (Exception ignored) { }
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (!(address instanceof Inet4Address)) continue;
                    if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                            || address.isLinkLocalAddress() || !isPrivate(address)) continue;
                    String value = address.getHostAddress();
                    int score = score(lowerName, value);
                    if (best == null || score > best.score) best = new Candidate(value, score);
                }
            }
        } catch (Exception ignored) { }
        return best == null || best.score < 30 ? null : best.address;
    }

    private int score(String interfaceName, String address) {
        int score = 0;
        if (!addressesBeforeStart.contains(address)) score += 100;
        if (interfaceName.contains("softap")) score += 100;
        if (interfaceName.equals("ap0") || interfaceName.startsWith("ap")) score += 90;
        if (interfaceName.contains("swlan")) score += 80;
        if (interfaceName.contains("wlan")) score += 45;
        if (address.endsWith(".1")) score += 25;
        if (address.startsWith("192.168.")) score += 10;
        return score;
    }

    private static boolean isDefinitelyNotHotspotInterface(String name) {
        return name.equals("lo") || name.contains("rmnet") || name.contains("ccmni")
                || name.contains("wwan") || name.contains("cell") || name.contains("pdp")
                || name.contains("tun") || name.contains("vpn") || name.contains("dummy")
                || name.contains("p2p");
    }

    private static Set<String> snapshotPrivateIpv4Addresses() {
        Set<String> result = new HashSet<String>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return result;
            for (NetworkInterface networkInterface : Collections.list(interfaces)) {
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (address instanceof Inet4Address && isPrivate(address)
                            && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                        result.add(address.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) { }
        return result;
    }

    private static boolean isPrivate(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes == null || bytes.length != 4) return false;
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return first == 10 || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168);
    }

    private static String cleanName(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() == 0) clean = "CarStream";
        return clean.length() > 24 ? clean.substring(0, 24) : clean;
    }

    private static String cleanPassword(String value) {
        String clean = value == null ? "" : value.trim();
        return clean.length() >= 8 && clean.length() <= 20 ? clean : "carstream";
    }

    private static String cleanCredential(String value) {
        if (value == null) return "";
        String clean = value.trim();
        if (clean.length() >= 2 && clean.startsWith("\"") && clean.endsWith("\"")) {
            clean = clean.substring(1, clean.length() - 1);
        }
        return clean;
    }

    private static String failureMessage(int reason) {
        switch (reason) {
            case WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL:
                return "Android could not find an available Wi-Fi channel";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE:
                return "Another Wi-Fi feature is blocking the private network";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED:
                return "This phone's policy does not allow app-only hotspot creation";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC:
            default:
                return "Android could not start the private network (code " + reason + ")";
        }
    }

    private static String safeMessage(Throwable error) {
        String value = error == null ? null : error.getMessage();
        return value == null || value.trim().length() == 0
                ? (error == null ? "unknown error" : error.getClass().getSimpleName())
                : value.trim();
    }

    private static final class Candidate {
        final String address;
        final int score;
        Candidate(String address, int score) { this.address = address; this.score = score; }
    }
}
