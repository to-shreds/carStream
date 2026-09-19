package com.carstream.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HostService extends Service
        implements WifiDirectController.Listener, ClientRegistry.Listener {

    public static final String ACTION_START = "com.carstream.app.action.START";
    public static final String ACTION_STOP = "com.carstream.app.action.STOP";
    private static final String CHANNEL_ID = "carstream_server";
    private static final int NOTIFICATION_ID = 8877;

    public interface Observer { void onServiceStateChanged(); }

    public interface PlaybackUrlCallback {
        void onPlaybackUrl(String url, String error);
    }

    public interface SkipMarkersCallback {
        void onSkipMarkers(SkipMarkerResolver.Resolution resolution);
    }

    public final class LocalBinder extends Binder {
        public HostService getService() { return HostService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(4);

    private AppSettings settings;
    private SecureStore secureStore;
    private CellularNetworkProvider networks;
    private TorBoxClient torBox;
    private SkipMarkerStore skipMarkerStore;
    private SkipMarkerResolver skipMarkerResolver;
    private ClientBundleManager bundles;
    private ClientRegistry clients;
    private WifiDirectController wifiDirect;
    private LocalHttpServer server;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private volatile boolean hosting;
    private volatile boolean playerRelayForeground;
    private volatile String status = "Stopped";
    private volatile String networkName = "";
    private volatile String networkPassword = "";
    private volatile String ownerAddress = "";
    private volatile String libraryStatus = "Library has not been refreshed";
    private volatile String lastError = "";
    private volatile boolean libraryRefreshing;
    private volatile long lastLibraryRefreshMillis;
    private volatile long rapidLibraryWatchUntil;

    @Override public void onCreate() {
        super.onCreate();
        settings = new AppSettings(this);
        secureStore = new SecureStore(this);
        networks = new CellularNetworkProvider(this);
        torBox = new TorBoxClient(this, networks, secureStore);
        skipMarkerStore = new SkipMarkerStore(this);
        skipMarkerResolver = new SkipMarkerResolver(networks, skipMarkerStore, settings);
        if (!torBox.currentLibrary().isEmpty()) {
            lastLibraryRefreshMillis = torBox.getLastRefreshMillis();
            libraryStatus = torBox.currentLibrary().size() + " saved playable file"
                    + (torBox.currentLibrary().size() == 1 ? "" : "s") + " loaded";
        }
        bundles = new ClientBundleManager(this, networks, settings);
        clients = new ClientRegistry();
        clients.addListener(this);
        wifiDirect = null;
        createNotificationChannel();
        EventLogger.info(this, "Service", "Background service created; TorBox browsing is available without starting the private network");
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopHosting(true);
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) startHosting();
        return hosting ? START_STICKY : START_NOT_STICKY;
    }

    public void addObserver(Observer observer) {
        observers.addIfAbsent(observer);
        notifyObservers();
    }

    public void removeObserver(Observer observer) { observers.remove(observer); }

    public synchronized void startHosting() {
        EventLogger.info(this, "Service", "Start requested");
        if (hosting) {
            notifyObservers();
            return;
        }
        hosting = true;
        lastError = "";
        status = "Starting...";
        ensureForeground();
        acquireLocks();
        networks.start();

        try {
            String pairingCode = settings.getPairingCode();
            ensureServerRunning();
            server.setPairingCode(pairingCode);
            status = "Creating the private network...";
            if (wifiDirect == null) wifiDirect = new WifiDirectController(this, this);
            wifiDirect.start(settings.getWifiName(), settings.getWifiPassword());
            refreshLibrary();
            if (settings.isRemoteClientEnabled() && !settings.getRemoteManifestUrl().isEmpty()) {
                refreshRemoteBundle();
            }
            scheduleLibraryWatch(20_000L);
        } catch (Exception e) {
            lastError = message(e);
            EventLogger.error(this, "Service", "Could not start CarStream: " + lastError, e);
            status = "Could not start CarStream";
            stopHosting(true);
        }
        updateNotification();
        notifyObservers();
    }

    public synchronized void stopHosting(boolean stopService) {
        EventLogger.info(this, "Service", "Stopping private network and server");
        hosting = false;
        if (wifiDirect != null) wifiDirect.stop(true);
        // Keep the localhost relay available for phone test playback and external-player
        // handoff. It uses the ordinary phone connection whenever hosting is stopped.
        networks.stop();
        releaseLocks();
        networkName = "";
        networkPassword = "";
        ownerAddress = "";
        status = "Stopped";
        mainHandler.removeCallbacks(libraryWatchdog);
        if (!playerRelayForeground) {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
        }
        notifyObservers();
        if (stopService && !playerRelayForeground) stopSelf();
    }

    public void refreshLibrary() {
        synchronized (this) {
            if (libraryRefreshing) {
                EventLogger.debug(this, "TorBox", "Ignored duplicate library refresh request");
                return;
            }
            libraryRefreshing = true;
        }
        EventLogger.info(this, "TorBox", "Library refresh started; privateNetworkRunning=" + hosting);
        libraryStatus = "Refreshing TorBox library...";
        notifyObservers();
        ioExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    List<MediaItem> items = torBox.refresh(hosting);
                    libraryStatus = items.size() + " playable file" + (items.size() == 1 ? "" : "s") + " found";
                    lastLibraryRefreshMillis = System.currentTimeMillis();
                    lastError = "";
                    EventLogger.info(HostService.this, "TorBox", "Library refresh succeeded with " + items.size() + " playable files");
                } catch (Exception e) {
                    libraryStatus = "Library refresh failed";
                    lastError = message(e);
                    EventLogger.error(HostService.this, "TorBox", "Library refresh failed: " + lastError, e);
                } finally {
                    libraryRefreshing = false;
                }
                notifyObservers();
            }
        });
    }

    public void refreshLibraryIfNeeded() {
        long age = System.currentTimeMillis() - lastLibraryRefreshMillis;
        if (torBox.currentLibrary().isEmpty() || lastLibraryRefreshMillis == 0L || age > 5L * 60L * 1000L) {
            refreshLibrary();
        }
    }

    public void addMagnet(String magnet) {
        EventLogger.info(this, "TorBox", "Magnet submission requested");
        libraryStatus = "Submitting magnet to TorBox...";
        notifyObservers();
        final String submittedMagnet = magnet;
        ioExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    libraryStatus = torBox.addMagnet(submittedMagnet, hosting);
                    lastError = "";
                    try { Thread.sleep(1_000L); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    List<MediaItem> items = torBox.refresh(hosting);
                    libraryStatus += "; " + items.size() + " playable file" + (items.size() == 1 ? "" : "s") + " listed";
                    rapidLibraryWatchUntil = System.currentTimeMillis() + 20L * 60L * 1000L;
                    scheduleLibraryWatch(12_000L);
                    EventLogger.info(HostService.this, "TorBox", "Magnet accepted; watching for newly ready files");
                } catch (Exception e) {
                    libraryStatus = "Could not add magnet";
                    lastError = message(e);
                    EventLogger.error(HostService.this, "TorBox", "Magnet submission failed: " + lastError, e);
                }
                notifyObservers();
            }
        });
    }

    public void refreshRemoteBundle() {
        notifyObservers();
        ioExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    bundles.refresh(hosting);
                    lastError = "";
                } catch (Exception e) {
                    lastError = message(e);
                }
                notifyObservers();
            }
        });
    }

    public void settingsChanged() {
        EventLogger.info(this, "Settings", "Settings changed");
        if (hosting) {
            status = "Settings saved. Restart CarStream to apply network or access-token changes.";
            updateNotification();
        }
        notifyObservers();
    }

    public boolean sendPlay(String clientId) {
        try { return clients.send(clientId, PlaybackCommand.simple("play")); }
        catch (JSONException e) { return false; }
    }

    public boolean sendPause(String clientId) {
        try { return clients.send(clientId, PlaybackCommand.simple("pause")); }
        catch (JSONException e) { return false; }
    }

    public boolean sendStop(String clientId) {
        try { return clients.send(clientId, PlaybackCommand.simple("stop")); }
        catch (JSONException e) { return false; }
    }

    public boolean sendSeekRelative(String clientId, double seconds) {
        try { return clients.send(clientId, PlaybackCommand.seekRelative(seconds)); }
        catch (JSONException e) { return false; }
    }

    public boolean sendMedia(String clientId, MediaItem item, double startAt, boolean autoplay) {
        if (item == null || !item.ready) return false;
        try { return clients.send(clientId, PlaybackCommand.setMedia(item, startAt, autoplay)); }
        catch (JSONException e) { return false; }
    }

    public int sendPauseAll() {
        int count = 0;
        for (ClientSession session : clients.snapshot()) {
            if (session.isOnline() && sendPause(session.id)) count++;
        }
        EventLogger.info(this, "Client", "Pause-all sent to " + count + " screen(s)");
        return count;
    }

    public int sendStopAll() {
        int count = 0;
        for (ClientSession session : clients.snapshot()) {
            if (session.isOnline() && sendStop(session.id)) count++;
        }
        EventLogger.info(this, "Client", "Stop-all sent to " + count + " screen(s)");
        return count;
    }

    public int sendMediaAll(MediaItem item, double startAt, boolean autoplay) {
        if (item == null || !item.ready) return 0;
        int count = 0;
        for (ClientSession session : clients.snapshot()) {
            if (session.isOnline() && sendMedia(session.id, item, startAt, autoplay)) count++;
        }
        EventLogger.info(this, "Client", "Selected media sent to " + count + " screen(s)");
        return count;
    }

    public boolean setControlMode(String clientId, ControlMode mode) {
        return clients.setControlMode(clientId, mode);
    }

    /** Keeps the localhost relay alive while another installed player is in front. */
    public synchronized boolean keepLocalRelayAlive() {
        try {
            ensureServerRunning();
            // A started service survives when MainActivity unbinds as the external player opens.
            startService(new Intent(this, HostService.class));
            playerRelayForeground = true;
            if (!hosting) status = "Phone test stream active";
            ensureForeground();
            updateNotification();
            EventLogger.info(this, "Player", "Local relay promoted to foreground for external-player handoff");
            return true;
        } catch (Exception error) {
            lastError = message(error);
            EventLogger.error(this, "Player", "Could not keep the local relay alive: " + lastError, error);
            return false;
        }
    }

    /** Releases the temporary foreground relay after MainActivity returns. */
    public synchronized void releaseLocalRelay() {
        if (!playerRelayForeground || hosting) return;
        playerRelayForeground = false;
        status = "Stopped";
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
        EventLogger.info(this, "Player", "External-player relay hold released");
        notifyObservers();
    }

    /**
     * Returns a localhost stream for the phone's built-in player. CarStream always
     * keeps the TorBox request inside its own HTTP proxy so redirects, byte ranges,
     * and Android's ordinary Internet route are handled consistently. The local-only
     * Wi-Fi network has no Internet route, so this avoids Samsung's rejected per-socket
     * network binding while keeping the TorBox request inside the phone app.
     */
    public void requestLocalPlaybackUrl(final MediaItem item, final PlaybackUrlCallback callback) {
        if (callback == null) return;
        if (item == null) {
            dispatchPlaybackUrl(callback, "", "Choose a video first");
            return;
        }
        if (!item.ready) {
            dispatchPlaybackUrl(callback, "", "That TorBox file is still preparing");
            return;
        }
        try {
            ensureServerRunning();
            String url = "http://127.0.0.1:" + settings.getPort()
                    + "/stream/" + urlEncode(item.id)
                    + "?code=" + urlEncode(settings.getPairingCode());
            EventLogger.info(this, "Player", "Using the localhost proxy with ordinary app routing; nearbyScreenMode=" + hosting + ": " + item.title);
            dispatchPlaybackUrl(callback, url, "");
        } catch (Exception error) {
            String detail = message(error);
            EventLogger.error(this, "Player", "Could not prepare phone playback: " + detail, error);
            dispatchPlaybackUrl(callback, "", detail);
        }
    }

    private void dispatchPlaybackUrl(final PlaybackUrlCallback callback, final String url, final String error) {
        mainHandler.post(new Runnable() {
            @Override public void run() { callback.onPlaybackUrl(url, error); }
        });
    }

    private static String urlEncode(String value) {
        try { return URLEncoder.encode(value == null ? "" : value, "UTF-8"); }
        catch (Exception ignored) { return value == null ? "" : value; }
    }


    public void resolveSkipMarkers(final MediaItem item, final boolean forceOnline,
                                   final SkipMarkersCallback callback) {
        if (callback == null || item == null) return;
        ioExecutor.execute(new Runnable() {
            @Override public void run() {
                final SkipMarkerResolver.Resolution resolution =
                        skipMarkerResolver.resolve(item, hosting, forceOnline);
                mainHandler.post(new Runnable() {
                    @Override public void run() { callback.onSkipMarkers(resolution); }
                });
            }
        });
    }

    public EpisodeIdentity getEpisodeIdentity(MediaItem item) {
        return skipMarkerStore.identityFor(item);
    }

    public void saveEpisodeIdentity(MediaItem item, EpisodeIdentity identity) {
        if (item != null && identity != null) skipMarkerStore.saveIdentity(item.id, identity);
    }

    public SkipMarkerStore.ManualMatch getManualSkipMarkers(MediaItem item, EpisodeIdentity identity) {
        return skipMarkerStore.findManual(item, identity);
    }

    public void saveManualSkipMarkers(String scope, MediaItem item, EpisodeIdentity identity,
                                      List<SkipSegment> segments) {
        skipMarkerStore.saveManual(scope, item, identity, segments);
        EventLogger.info(this, "Skip", "Saved manual markers for " + (item == null ? "unknown media" : item.title));
        notifyObservers();
    }

    public void clearManualSkipMarkers(String scope, MediaItem item, EpisodeIdentity identity) {
        skipMarkerStore.clearManual(scope, item, identity);
        EventLogger.info(this, "Skip", "Cleared manual markers for " + (item == null ? "unknown media" : item.title));
        notifyObservers();
    }

    public JSONObject getSkipMarkerDiagnostics() {
        try { return skipMarkerStore.diagnostics(); }
        catch (JSONException error) { return new JSONObject(); }
    }

    public boolean isHosting() { return hosting; }
    public String getStatus() { return status; }
    public String getNetworkName() { return networkName; }
    public String getNetworkPassword() { return networkPassword; }
    public String getOwnerAddress() { return ownerAddress; }
    public String getLibraryStatus() { return libraryStatus; }
    public boolean isLibraryRefreshing() { return libraryRefreshing; }
    public long getLastLibraryRefreshMillis() { return lastLibraryRefreshMillis; }
    public String getLastError() { return lastError; }
    public String getRemoteBundleStatus() { return bundles.getStatus(); }
    public String getRemoteBundleVersion() { return bundles.getVersion(); }
    public boolean hasCellularNetwork() { return networks.hasCellularNetwork(); }
    public String getRouteSummary() { return networks.routeSummary(); }
    public int getActiveStreamCount() { return server == null ? 0 : server.getStreamRegistry().getActiveCount(); }
    public int getMaximumStreamCount() { return server == null ? StreamRegistry.DEFAULT_MAX_STREAMS : server.getStreamRegistry().getMaximum(); }
    public long getTotalStreamBytes() { return server == null ? 0L : server.getStreamRegistry().getTotalBytes(); }
    public String getStreamSummary() { return server == null ? "0 active streams" : server.getStreamRegistry().summary(); }

    public String getPairingCode() { return settings.getPairingCode(); }
    public String getWebDavUsername() { return "carstream"; }
    public String getWebDavPassword() { return settings.getPairingCode(); }
    public String getWebDavUrl() {
        if (ownerAddress.isEmpty()) return "";
        return "http://" + ownerAddress + ":" + settings.getPort() + "/dav/";
    }

    public String getTabletUrl() {
        if (ownerAddress.isEmpty()) return "";
        return "http://" + ownerAddress + ":" + settings.getPort() + "/";
    }

    public String getStremioSetupUrl() {
        String base = getTabletUrl();
        return base.isEmpty() ? "" : base + "stremio/" + settings.getStremioToken() + "/setup";
    }

    public List<ClientSession> getClients() { return clients.snapshot(); }
    public List<MediaItem> getLibrary() { return new ArrayList<>(torBox.currentLibrary()); }

    @Override public void onWifiStatus(String value) {
        EventLogger.info(this, "Network", value);
        status = value;
        updateNotification();
        notifyObservers();
    }

    @Override public void onGroupReady(String name, String passphrase, String address) {
        EventLogger.info(this, "Network", "Private network ready: name=" + (name == null ? "" : name) + ", address=" + (address == null ? "" : address));
        networkName = name == null ? "" : name;
        networkPassword = passphrase == null ? "" : passphrase;
        ownerAddress = address == null ? "" : address;
        status = "Ready";
        updateNotification();
        notifyObservers();
    }

    @Override public void onWifiStopped() {
        if (hosting) EventLogger.warn(this, "Network", "Private local network stopped unexpectedly");
        if (hosting) status = "Private local network stopped unexpectedly";
        notifyObservers();
    }

    @Override public void onClientsChanged() { notifyObservers(); }

    private synchronized void ensureServerRunning() throws IOException {
        if (server != null && server.isOpen()) return;
        server = new LocalHttpServer(this, settings.getPort(), settings.getPairingCode(),
                clients, torBox, networks, bundles, skipMarkerResolver, settings,
                new LocalHttpServer.NetworkMode() {
            @Override public boolean requireCellular() { return hosting; }
        });
        server.start();
    }

    private void scheduleLibraryWatch(long delayMillis) {
        mainHandler.removeCallbacks(libraryWatchdog);
        if (hosting) mainHandler.postDelayed(libraryWatchdog, Math.max(5_000L, delayMillis));
    }

    private final Runnable libraryWatchdog = new Runnable() {
        @Override public void run() {
            if (!hosting) return;
            if (!libraryRefreshing) {
                EventLogger.debug(HostService.this, "TorBox", "Automatic nearby-screen library refresh");
                refreshLibrary();
            }
            long delay = System.currentTimeMillis() < rapidLibraryWatchUntil ? 15_000L : 45_000L;
            scheduleLibraryWatch(delay);
        }
    };

    private void notifyObservers() {
        mainHandler.removeCallbacks(observerDispatch);
        mainHandler.postDelayed(observerDispatch, 100L);
    }

    private final Runnable observerDispatch = new Runnable() {
        @Override public void run() {
            for (Observer observer : observers) {
                try { observer.onServiceStateChanged(); }
                catch (Exception ignored) { }
            }
        }
    };

    private void ensureForeground() {
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    private void updateNotification() {
        if (!hosting && !playerRelayForeground) return;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_carstream)
                .setContentTitle("CarStream")
                .setContentText(status)
                .setContentIntent(pending)
                .setOngoing(hosting || playerRelayForeground)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.channel_description));
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (power != null && (wakeLock == null || !wakeLock.isHeld())) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CarStream:Server");
            wakeLock.acquire();
        }
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null && (wifiLock == null || !wifiLock.isHeld())) {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CarStream:Streaming");
            wifiLock.acquire();
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        wakeLock = null;
        wifiLock = null;
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value.trim();
    }

    @Override public void onDestroy() {
        EventLogger.info(this, "Service", "Host service destroyed");
        clients.removeListener(this);
        stopHosting(false);
        if (server != null) server.close();
        server = null;
        networks.stop();
        ioExecutor.shutdownNow();
        super.onDestroy();
    }
}
