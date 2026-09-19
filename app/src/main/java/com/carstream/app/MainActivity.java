package com.carstream.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Fresh single-activity UI shell. Every main page lives in this Activity so Library and
 * Settings cannot fail because of a missing Activity declaration or a stale cross-screen
 * dependency. Page construction and every button action are guarded so a recoverable UI
 * problem becomes an on-screen error instead of a process crash.
 */
public final class MainActivity extends Activity implements HostService.Observer {
    private static final int REQUEST_PERMISSIONS = 4107;

    private static final int PAGE_HOME = 0;
    private static final int PAGE_SCREENS = 1;
    private static final int PAGE_LIBRARY = 2;
    private static final int PAGE_SETTINGS = 3;
    private static final int PAGE_ADVANCED = 4;
    private static final int PAGE_BROWSER = 5;
    private static final int PAGE_DIAGNOSTICS = 6;
    private static final int PAGE_HELP = 7;

    private static final int BG = Color.rgb(13, 18, 27);
    private static final int CARD = Color.rgb(28, 35, 48);
    private static final int CARD_ALT = Color.rgb(37, 45, 61);
    private static final int ACCENT = Color.rgb(72, 116, 238);
    private static final int GOOD = Color.rgb(92, 214, 143);
    private static final int WARN = Color.rgb(255, 198, 105);
    private static final int BAD = Color.rgb(255, 138, 138);
    private static final int MUTED = Color.rgb(180, 190, 207);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Integer> pageHistory = new ArrayList<Integer>();

    private AppSettings appSettings;
    private SecureStore secureStore;
    private HostService service;
    private boolean bound;
    private boolean binding;
    private boolean pendingStartAfterPermission;
    private int currentPage = PAGE_HOME;

    private TextView homeStatus;
    private TextView homeDetail;
    private TextView homePermission;
    private Button homeStartButton;
    private LinearLayout homeConnectionCard;
    private TextView homeNetwork;
    private TextView homePassword;
    private TextView homeUrl;
    private TextView homePairing;
    private TextView homeWebDav;
    private TextView homeCounts;

    private final LibraryNavigator.Location libraryLocation = new LibraryNavigator.Location();
    private final ArrayList<LibraryNavigator.Entry> libraryEntries = new ArrayList<LibraryNavigator.Entry>();
    private LibraryEntryAdapter libraryAdapter;
    private EditText librarySearch;
    private TextView libraryPath;
    private TextView libraryStatus;
    private ListView libraryList;
    private boolean librarySelectionMode;
    private int libraryReturnPage = PAGE_LIBRARY;
    private MediaItem selectedMedia;

    private LinearLayout screensClients;
    private TextView screensSelected;
    private TextView screensSummary;

    private EditText settingApiKey;
    private EditText settingWifiName;
    private EditText settingWifiPassword;
    private TextView settingPairingCode;
    private CheckBox settingOnlineMarkers;
    private Button settingIntroMode;
    private Button settingRecapMode;
    private Button settingCreditsMode;
    private Button settingCountdown;

    private CheckBox advancedRemoteEnabled;
    private EditText advancedManifest;
    private EditText advancedBrowserHome;
    private EditText advancedProjectUrl;
    private TextView advancedBundleStatus;

    private WebView miniBrowser;
    private EditText miniBrowserAddress;
    private TextView miniBrowserStatus;

    private TextView diagnosticsSummary;
    private TextView diagnosticsLog;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            try {
                service = ((HostService.LocalBinder) binder).getService();
                bound = true;
                binding = false;
                service.addObserver(MainActivity.this);
                EventLogger.info(MainActivity.this, "UI", "Fresh UI connected to host service");
                updateCurrentPage();
                if (service.getLibrary().isEmpty()) service.refreshLibraryIfNeeded();
                if (pendingStartAfterPermission && allRequiredPermissionsGranted()) {
                    pendingStartAfterPermission = false;
                    startHostingNow();
                }
            } catch (Throwable error) {
                binding = false;
                bound = false;
                service = null;
                showRecoverableProblem("Could not connect to CarStream", error);
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            if (service != null) service.removeObserver(MainActivity.this);
            service = null;
            bound = false;
            binding = false;
            updateCurrentPage();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            appSettings = new AppSettings(this);
            secureStore = new SecureStore(this);
            CrashReporter.archivePreviousCrash(this);
            EventLogger.info(this, "UI", "CarStream rebuilt UI opening");
            showPage(PAGE_HOME, false);
            handler.postDelayed(new Runnable() {
                @Override public void run() { requestMissingPermissions(false); }
            }, 350L);
        } catch (Throwable error) {
            CrashReporter.record(this, error);
            showFatalFallback(error);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        bindToService();
    }

    @Override protected void onStop() {
        if (bound) {
            try {
                if (service != null) service.removeObserver(this);
                unbindService(serviceConnection);
            } catch (Throwable ignored) { }
        }
        service = null;
        bound = false;
        binding = false;
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyMiniBrowser();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (currentPage == PAGE_HOME || pageHistory.isEmpty()) {
            super.onBackPressed();
            return;
        }
        int previous = pageHistory.remove(pageHistory.size() - 1).intValue();
        showPage(previous, false);
    }

    @Override public void onServiceStateChanged() {
        runOnUiThread(new Runnable() {
            @Override public void run() { safeRun("refreshing the current page", new Runnable() {
                @Override public void run() { updateCurrentPage(); }
            }); }
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_PERMISSIONS) return;
        EventLogger.info(this, "Permissions", "Permission response received");
        updateCurrentPage();
        if (allRequiredPermissionsGranted()) {
            if (pendingStartAfterPermission) {
                pendingStartAfterPermission = false;
                startHostingNow();
            }
        } else {
            pendingStartAfterPermission = false;
            showPermissionExplanation();
        }
    }

    private void showPage(final int page, boolean pushHistory) {
        if (pushHistory && page != currentPage) pageHistory.add(Integer.valueOf(currentPage));
        if (currentPage == PAGE_BROWSER && page != PAGE_BROWSER) destroyMiniBrowser();
        currentPage = page;
        try {
            View view;
            switch (page) {
                case PAGE_SCREENS: view = buildScreensPage(); break;
                case PAGE_LIBRARY: view = buildLibraryPage(); break;
                case PAGE_SETTINGS: view = buildSettingsPage(); break;
                case PAGE_ADVANCED: view = buildAdvancedPage(); break;
                case PAGE_BROWSER: view = buildBrowserPage(); break;
                case PAGE_DIAGNOSTICS: view = buildDiagnosticsPage(); break;
                case PAGE_HELP: view = buildHelpPage(); break;
                case PAGE_HOME:
                default: view = buildHomePage(); break;
            }
            setContentView(view);
            updateCurrentPage();
        } catch (Throwable error) {
            EventLogger.error(this, "UI", "Could not build page " + pageName(page), error);
            showPageFailure(page, error);
        }
    }

    private void navigate(int page) { showPage(page, true); }

    private View buildHomePage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = pageBody();
        scroll.addView(body, matchWrap());

        LinearLayout hero = card();
        LinearLayout heroTop = horizontal();
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_carstream);
        icon.setContentDescription("CarStream");
        heroTop.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout labels = vertical();
        labels.addView(text("CarStream", 27, Color.WHITE, true));
        labels.addView(text("Private in-car streaming", 14, MUTED, false), topMargin(matchWrap(), 2));
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelsParams.leftMargin = dp(12);
        heroTop.addView(labels, labelsParams);
        body.addView(heroTop, matchWrap());

        homeStatus = text("Stopped", 21, Color.WHITE, true);
        body.addView(homeStatus, topMargin(matchWrap(), 18));
        homeDetail = text("Tap Start. CarStream handles the rest.", 14, MUTED, false);
        body.addView(homeDetail, topMargin(matchWrap(), 4));
        homePermission = text("Checking permissions...", 12, MUTED, false);
        body.addView(homePermission, topMargin(matchWrap(), 8));
        homeStartButton = fullButton("Start CarStream", true, new Runnable() {
            @Override public void run() { startOrStop(); }
        });
        body.addView(homeStartButton, topMargin(matchWrap(), 14));

        homeConnectionCard = card();
        homeConnectionCard.addView(sectionTitle("Connect a screen", "On each tablet: scan 1 to join Wi-Fi, then scan 2 to open the shows."));
        homeNetwork = connectionValue();
        homePassword = connectionValue();
        homeUrl = connectionValue();
        homePairing = connectionValue();
        homeWebDav = connectionValue();
        homeConnectionCard.addView(connectionRow("Wi-Fi network", homeNetwork, new Runnable() {
            @Override public void run() { copy("Wi-Fi network", service == null ? "" : service.getNetworkName()); }
        }), topMargin(matchWrap(), 10));
        homeConnectionCard.addView(connectionRow("Wi-Fi password", homePassword, new Runnable() {
            @Override public void run() { copy("Wi-Fi password", service == null ? "" : service.getNetworkPassword()); }
        }), topMargin(matchWrap(), 8));
        homeConnectionCard.addView(connectionRow("Open in Chrome", homeUrl, new Runnable() {
            @Override public void run() { copy("Chrome address", service == null ? "" : service.getTabletUrl()); }
        }), topMargin(matchWrap(), 8));
        homeConnectionCard.addView(connectionRow("Pairing code", homePairing, new Runnable() {
            @Override public void run() { copy("Pairing code", service == null ? "" : service.getPairingCode()); }
        }), topMargin(matchWrap(), 8));
        // WebDAV remains available in the service; keep Chrome setup focused on the two scans.
        LinearLayout connectButtons = horizontal();
        connectButtons.addView(smallButton("1. Join Wi-Fi", "Scan this on the tablet to join the exact network Android created.", new Runnable() {
            @Override public void run() { showWifiQr(); }
        }), weight());
        connectButtons.addView(smallButton("2. Watch QR", "Scan after joining Wi-Fi. Opens the show list with the pairing code filled in.", new Runnable() {
            @Override public void run() { showWatchQr(); }
        }), weightLeft());
        homeConnectionCard.addView(connectButtons, topMargin(matchWrap(), 10));
        homeConnectionCard.addView(smallButton("Stremio setup", "Try Stremio on a tablet connected to CarStream Wi-Fi.", new Runnable() {
            @Override public void run() { showStremioQr(); }
        }), topMargin(matchWrap(), 8));
        body.addView(homeConnectionCard, topMargin(matchWrap(), 14));

        body.addView(sectionHeading("Manage"), topMargin(matchWrap(), 20));
        body.addView(navCard("Screens", "See what every browser is watching and control each one.", new Runnable() {
            @Override public void run() { navigate(PAGE_SCREENS); }
        }), topMargin(matchWrap(), 8));
        body.addView(navCard("Library", "Browse TorBox by folder, search, and add magnets.", new Runnable() {
            @Override public void run() { librarySelectionMode = false; navigate(PAGE_LIBRARY); }
        }), topMargin(matchWrap(), 9));
        body.addView(navCard("Settings", "TorBox key, connection details, and Smart Skip.", new Runnable() {
            @Override public void run() { navigate(PAGE_SETTINGS); }
        }), topMargin(matchWrap(), 9));
        body.addView(navCard("Diagnostics", "Detailed logs and a copyable troubleshooting report.", new Runnable() {
            @Override public void run() { navigate(PAGE_DIAGNOSTICS); }
        }), topMargin(matchWrap(), 9));
        body.addView(navCard("Help", "Simple setup and troubleshooting instructions.", new Runnable() {
            @Override public void run() { navigate(PAGE_HELP); }
        }), topMargin(matchWrap(), 9));

        homeCounts = text("", 12, MUTED, false);
        body.addView(homeCounts, topMargin(matchWrap(), 14));
        return scroll;
    }

    private View buildScreensPage() {
        ScrollView scroll = pageScroll("Screens", "Pick a video, then control each connected Chrome screen independently.");
        LinearLayout body = (LinearLayout) scroll.getChildAt(0);

        LinearLayout selected = card();
        selected.addView(sectionTitle("Selected video", "Choose through the same folder browser used by Library. Nothing is flattened into one giant list."));
        screensSelected = text("No video selected", 15, WARN, true);
        screensSelected.setMaxLines(4);
        selected.addView(screensSelected, topMargin(matchWrap(), 8));
        LinearLayout chooseRow = horizontal();
        chooseRow.addView(smallButton("Choose video", "Open the folder browser and return here after choosing.", new Runnable() {
            @Override public void run() {
                librarySelectionMode = true;
                libraryReturnPage = PAGE_SCREENS;
                navigate(PAGE_LIBRARY);
            }
        }), weight());
        chooseRow.addView(smallButton("Open on phone", "Test the selected stream in VLC or another installed player.", new Runnable() {
            @Override public void run() { openSelectedExternally(); }
        }), weightLeft());
        chooseRow.addView(smallButton("Clear", null, new Runnable() {
            @Override public void run() { selectedMedia = null; updateScreensPage(); }
        }), weightLeft());
        selected.addView(chooseRow, topMargin(matchWrap(), 10));
        body.addView(selected, matchWrap());

        LinearLayout group = card();
        group.addView(sectionTitle("Everyone", "These affect all currently connected browser screens."));
        LinearLayout row = horizontal();
        row.addView(smallButton("Play selected", null, new Runnable() {
            @Override public void run() { sendSelectedToAll(); }
        }), weight());
        row.addView(smallButton("Pause all", null, new Runnable() {
            @Override public void run() { toastCount(service == null ? 0 : service.sendPauseAll(), "paused"); }
        }), weightLeft());
        row.addView(smallButton("Stop all", null, new Runnable() {
            @Override public void run() { toastCount(service == null ? 0 : service.sendStopAll(), "stopped"); }
        }), weightLeft());
        group.addView(row, topMargin(matchWrap(), 9));
        screensSummary = text("No connected screens", 12, MUTED, false);
        group.addView(screensSummary, topMargin(matchWrap(), 8));
        body.addView(group, topMargin(matchWrap(), 14));

        body.addView(sectionHeading("Connected screens"), topMargin(matchWrap(), 20));
        screensClients = vertical();
        body.addView(screensClients, topMargin(matchWrap(), 7));
        return scroll;
    }

    private View buildLibraryPage() {
        LinearLayout root = vertical();
        root.setBackgroundColor(BG);
        root.setPadding(dp(14), dp(12), dp(14), dp(10));
        root.addView(pageHeader(librarySelectionMode ? "Choose a video" : "TorBox Library",
                librarySelectionMode ? "Tap a file to select it for Screens." : "Browse whenever the phone has Internet. CarStream does not need to be started."), matchWrap());

        libraryStatus = text("Connecting to TorBox library...", 13, MUTED, false);
        root.addView(libraryStatus, topMargin(matchWrap(), 8));

        librarySearch = input("Search every folder");
        librarySearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateLibraryPage(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        root.addView(librarySearch, topMargin(matchWrap(), 10));

        LinearLayout actions = horizontal();
        actions.addView(tinyButton("All", "Return to all TorBox downloads.", new Runnable() {
            @Override public void run() { libraryLocation.reset(); clearLibrarySearch(); updateLibraryPage(); }
        }), weight());
        actions.addView(tinyButton("Up", "Move up one folder.", new Runnable() {
            @Override public void run() { LibraryNavigator.up(libraryLocation); clearLibrarySearch(); updateLibraryPage(); }
        }), weightLeft());
        actions.addView(tinyButton("Skip wrappers", "Jump through useless single-folder hash/completed wrappers.", new Runnable() {
            @Override public void run() {
                int count = LibraryNavigator.skipWrappers(currentLibrary(), libraryLocation);
                toast(count == 0 ? "No wrapper folders to skip here" : "Skipped " + count + " wrapper folder" + (count == 1 ? "" : "s"));
                updateLibraryPage();
            }
        }), weightLeft());
        actions.addView(tinyButton("Refresh", "Reload the TorBox library.", new Runnable() {
            @Override public void run() { ensureServiceThen(new Runnable() {
                @Override public void run() { service.refreshLibrary(); }
            }); }
        }), weightLeft());
        root.addView(actions, topMargin(matchWrap(), 8));

        libraryPath = text("All TorBox downloads", 14, Color.WHITE, true);
        libraryPath.setMaxLines(3);
        root.addView(libraryPath, topMargin(matchWrap(), 9));

        libraryList = new ListView(this);
        libraryList.setDivider(null);
        libraryList.setDividerHeight(dp(7));
        libraryList.setCacheColorHint(Color.TRANSPARENT);
        libraryAdapter = new LibraryEntryAdapter();
        libraryList.setAdapter(libraryAdapter);
        libraryList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                safeRun("opening a library item", new Runnable() {
                    @Override public void run() { handleLibraryClick(position); }
                });
            }
        });
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        listParams.topMargin = dp(7);
        root.addView(libraryList, listParams);

        LinearLayout bottom = horizontal();
        bottom.addView(smallButton("Add magnet", "Paste a magnet link and send it to TorBox.", new Runnable() {
            @Override public void run() { showMagnetDialog(); }
        }), weight());
        bottom.addView(smallButton("Mini browser", "Search the web and tap or paste a magnet without leaving CarStream.", new Runnable() {
            @Override public void run() { navigate(PAGE_BROWSER); }
        }), weightLeft());
        root.addView(bottom, topMargin(matchWrap(), 8));
        return root;
    }

    private View buildSettingsPage() {
        ScrollView scroll = pageScroll("Settings", "Only everyday settings are here. Technical options are under Advanced.");
        LinearLayout body = (LinearLayout) scroll.getChildAt(0);

        LinearLayout torbox = card();
        torbox.addView(sectionTitle("TorBox", "Your API key stays encrypted on this phone and is never sent to a browser screen."));
        settingApiKey = input("TorBox API key");
        settingApiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        settingApiKey.setText(secureStore.loadTorBoxKey());
        torbox.addView(settingApiKey, topMargin(matchWrap(), 8));
        body.addView(torbox, matchWrap());

        LinearLayout network = card();
        network.addView(sectionTitle("Preferred network details", "Android may replace these with its own credentials. Home always shows the actual name and password Android created."));
        settingWifiName = input("Preferred Wi-Fi name");
        settingWifiName.setText(appSettings.getWifiName());
        network.addView(settingWifiName, topMargin(matchWrap(), 8));
        settingWifiPassword = input("Preferred Wi-Fi password (8–20 characters)");
        settingWifiPassword.setText(appSettings.getWifiPassword());
        network.addView(settingWifiPassword, topMargin(matchWrap(), 8));
        settingPairingCode = text("Pairing code: " + appSettings.getPairingCode(), 16, Color.WHITE, true);
        network.addView(settingPairingCode, topMargin(matchWrap(), 10));
        network.addView(smallButton("New pairing code", "Creates a new browser/WebDAV code and revokes old Stremio links. Reinstall the Stremio add-on afterward.", new Runnable() {
            @Override public void run() {
                appSettings.regeneratePairingCode();
                settingPairingCode.setText("Pairing code: " + appSettings.getPairingCode());
                if (service != null) service.settingsChanged();
            }
        }), topMargin(matchWrap(), 8));
        body.addView(network, topMargin(matchWrap(), 14));

        LinearLayout skip = card();
        skip.addView(sectionTitle("Smart Skip", "Choose what browser screens do when CarStream knows where a recap, intro, or credits begin."));
        settingOnlineMarkers = checkBox("Use online community timestamps when available", appSettings.isOnlineSkipLookupEnabled());
        skip.addView(settingOnlineMarkers, topMargin(matchWrap(), 7));
        settingRecapMode = modeButton("Recaps", appSettings.getRecapSkipMode(), false);
        skip.addView(settingRecapMode, topMargin(matchWrap(), 7));
        settingIntroMode = modeButton("Intros", appSettings.getIntroSkipMode(), false);
        skip.addView(settingIntroMode, topMargin(matchWrap(), 7));
        settingCreditsMode = modeButton("Credits", appSettings.getCreditsSkipMode(), true);
        skip.addView(settingCreditsMode, topMargin(matchWrap(), 7));
        settingCountdown = countdownButton(appSettings.getSkipCountdownSeconds());
        skip.addView(settingCountdown, topMargin(matchWrap(), 7));
        body.addView(skip, topMargin(matchWrap(), 14));

        body.addView(fullButton("Save settings", true, new Runnable() {
            @Override public void run() { saveBasicSettings(); }
        }), topMargin(matchWrap(), 16));
        body.addView(navCard("Advanced", "Remote browser scripts, mini-browser home page, and project link.", new Runnable() {
            @Override public void run() { navigate(PAGE_ADVANCED); }
        }), topMargin(matchWrap(), 12));
        body.addView(navCard("Permissions", "Check or reopen Android's permission settings.", new Runnable() {
            @Override public void run() { showPermissionManagement(); }
        }), topMargin(matchWrap(), 9));
        return scroll;
    }

    private View buildAdvancedPage() {
        ScrollView scroll = pageScroll("Advanced", "Normal use does not require anything on this page.");
        LinearLayout body = (LinearLayout) scroll.getChildAt(0);

        LinearLayout remote = card();
        remote.addView(sectionTitle("Remote browser interface", "Optional: download a newer browser interface from a manifest URL. The embedded version remains the fallback."));
        advancedRemoteEnabled = checkBox("Use remote browser bundle", appSettings.isRemoteClientEnabled());
        remote.addView(advancedRemoteEnabled, topMargin(matchWrap(), 6));
        advancedManifest = input("Remote client manifest URL");
        advancedManifest.setText(appSettings.getRemoteManifestUrl());
        remote.addView(advancedManifest, topMargin(matchWrap(), 7));
        advancedBundleStatus = text(service == null ? "Service is not connected" : service.getRemoteBundleStatus(), 12, MUTED, false);
        remote.addView(advancedBundleStatus, topMargin(matchWrap(), 7));
        remote.addView(smallButton("Refresh remote scripts", "Download and verify the manifest and files now.", new Runnable() {
            @Override public void run() {
                saveAdvancedSettings(false);
                ensureServiceThen(new Runnable() { @Override public void run() { service.refreshRemoteBundle(); } });
            }
        }), topMargin(matchWrap(), 8));
        body.addView(remote, matchWrap());

        LinearLayout browser = card();
        browser.addView(sectionTitle("Mini browser", "The page opened by Search home in CarStream's built-in browser."));
        advancedBrowserHome = input("Browser home page");
        advancedBrowserHome.setText(appSettings.getBrowserHome());
        browser.addView(advancedBrowserHome, topMargin(matchWrap(), 8));
        body.addView(browser, topMargin(matchWrap(), 14));

        LinearLayout project = card();
        project.addView(sectionTitle("Project link", "Optional documentation or source-code address."));
        advancedProjectUrl = input("Project URL");
        advancedProjectUrl.setText(appSettings.getProjectUrl());
        project.addView(advancedProjectUrl, topMargin(matchWrap(), 8));
        body.addView(project, topMargin(matchWrap(), 14));

        body.addView(fullButton("Save advanced settings", true, new Runnable() {
            @Override public void run() { saveAdvancedSettings(true); }
        }), topMargin(matchWrap(), 16));
        return scroll;
    }

    private View buildBrowserPage() {
        LinearLayout root = vertical();
        root.setBackgroundColor(BG);
        root.setPadding(dp(10), dp(10), dp(10), dp(8));
        root.addView(pageHeader("Mini browser", "Search for a magnet, then tap it or use Paste magnet."), matchWrap());

        miniBrowserAddress = input("Search or enter a website");
        root.addView(miniBrowserAddress, topMargin(matchWrap(), 8));
        LinearLayout row = horizontal();
        row.addView(tinyButton("Back", null, new Runnable() {
            @Override public void run() { if (miniBrowser != null && miniBrowser.canGoBack()) miniBrowser.goBack(); }
        }), weight());
        row.addView(tinyButton("Forward", null, new Runnable() {
            @Override public void run() { if (miniBrowser != null && miniBrowser.canGoForward()) miniBrowser.goForward(); }
        }), weightLeft());
        row.addView(tinyButton("Go", null, new Runnable() {
            @Override public void run() { browserGo(miniBrowserAddress.getText().toString()); }
        }), weightLeft());
        row.addView(tinyButton("Home", null, new Runnable() {
            @Override public void run() { browserGo(appSettings.getBrowserHome()); }
        }), weightLeft());
        row.addView(tinyButton("Paste magnet", null, new Runnable() {
            @Override public void run() { pasteMagnetFromClipboard(); }
        }), weightLeft());
        root.addView(row, topMargin(matchWrap(), 7));
        miniBrowserStatus = text("", 11, MUTED, false);
        root.addView(miniBrowserStatus, topMargin(matchWrap(), 5));

        miniBrowser = new WebView(this);
        WebSettings settings = miniBrowser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        if (Build.VERSION.SDK_INT >= 21) settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= 26) settings.setSafeBrowsingEnabled(true);
        miniBrowser.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return interceptBrowserUrl(url);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return request != null && interceptBrowserUrl(request.getUrl() == null ? "" : request.getUrl().toString());
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (miniBrowserAddress != null && url != null) miniBrowserAddress.setText(url);
                if (miniBrowserStatus != null) miniBrowserStatus.setText(view.getTitle() == null ? "Ready" : view.getTitle());
                if (url != null) appSettings.setBrowserLastUrl(url);
            }
            @Override public void onReceivedError(WebView view, int code, String description, String failingUrl) {
                if (miniBrowserStatus != null) miniBrowserStatus.setText("Page error: " + description);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (miniBrowserStatus != null) miniBrowserStatus.setText("Page error: " + (error == null ? "unknown" : error.getDescription()));
            }
        });
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        webParams.topMargin = dp(7);
        root.addView(miniBrowser, webParams);
        browserGo(appSettings.getBrowserLastUrl());
        return root;
    }

    private View buildDiagnosticsPage() {
        ScrollView scroll = pageScroll("Diagnostics", "Everything needed to troubleshoot a real-device problem, with secrets redacted.");
        LinearLayout body = (LinearLayout) scroll.getChildAt(0);
        diagnosticsSummary = text("Loading live state...", 13, Color.WHITE, false);
        diagnosticsSummary.setTextIsSelectable(true);
        LinearLayout summaryCard = card();
        summaryCard.addView(sectionTitle("Live state", "Current service, network, TorBox, browser-screen, and permission information."));
        summaryCard.addView(diagnosticsSummary, topMargin(matchWrap(), 8));
        body.addView(summaryCard, matchWrap());

        LinearLayout controls = horizontal();
        controls.addView(smallButton("Copy report", null, new Runnable() {
            @Override public void run() { copy("CarStream diagnostics", EventLogger.buildReport(MainActivity.this, liveSummary())); }
        }), weight());
        controls.addView(smallButton("Share report", null, new Runnable() {
            @Override public void run() { shareDiagnostics(); }
        }), weightLeft());
        controls.addView(smallButton("Clear log", null, new Runnable() {
            @Override public void run() { EventLogger.clear(MainActivity.this); updateDiagnosticsPage(); }
        }), weightLeft());
        body.addView(controls, topMargin(matchWrap(), 12));

        diagnosticsLog = text("", 11, MUTED, false);
        diagnosticsLog.setTextIsSelectable(true);
        LinearLayout logCard = card();
        logCard.addView(sectionTitle("Event log", "Old entries rotate automatically. API keys, passwords, pairing codes, tokens, and magnets are redacted."));
        logCard.addView(diagnosticsLog, topMargin(matchWrap(), 8));
        body.addView(logCard, topMargin(matchWrap(), 14));
        return scroll;
    }

    private View buildHelpPage() {
        ScrollView scroll = pageScroll("Help", "The normal setup should take four steps.");
        LinearLayout body = (LinearLayout) scroll.getChildAt(0);
        body.addView(helpCard("1. Save the TorBox key", "Open Settings, paste the API key, and tap Save settings."));
        body.addView(helpCard("2. Start CarStream", "Home asks for the needed Android permissions automatically. Tap Start CarStream and wait for Ready."), topMargin(matchWrap(), 10));
        body.addView(helpCard("3. Join the private Wi-Fi", "On the tablet, scan Wi-Fi QR or enter the exact network and password shown on Home. Stay connected when Android warns that the network has no Internet."), topMargin(matchWrap(), 10));
        body.addView(helpCard("4. Open Chrome", "Open the address shown on Home, name the screen, and enter the four-digit pairing code."), topMargin(matchWrap(), 10));
        body.addView(helpCard("Videos", "MP4/H.264/AAC is the safest direct browser format. CarStream first asks TorBox for its browser-oriented stream and otherwise falls back to the original file."), topMargin(matchWrap(), 10));
        body.addView(helpCard("Two kids at once", "Each browser screen has independent playback state and can request a different title. Actual capacity depends on cellular speed, source bitrate, and phone temperature."), topMargin(matchWrap(), 10));
        body.addView(helpCard("VLC fallback", "Home shows a read-only WebDAV address. Username is carstream and the password is the current four-digit pairing code."), topMargin(matchWrap(), 10));
        body.addView(fullButton("Open diagnostics", false, new Runnable() {
            @Override public void run() { navigate(PAGE_DIAGNOSTICS); }
        }), topMargin(matchWrap(), 14));
        return scroll;
    }

    private void updateCurrentPage() {
        switch (currentPage) {
            case PAGE_HOME: updateHomePage(); break;
            case PAGE_SCREENS: updateScreensPage(); break;
            case PAGE_LIBRARY: updateLibraryPage(); break;
            case PAGE_ADVANCED: if (advancedBundleStatus != null) advancedBundleStatus.setText(service == null ? "Service is not connected" : service.getRemoteBundleStatus()); break;
            case PAGE_DIAGNOSTICS: updateDiagnosticsPage(); break;
            default: break;
        }
    }

    private void updateHomePage() {
        if (homeStatus == null) return;
        boolean hosting = service != null && service.isHosting();
        boolean ready = hosting && service.getTabletUrl().length() > 0;
        String status = service == null ? (binding ? "Connecting..." : "Service unavailable") : service.getStatus();
        homeStatus.setText(status);
        homeStatus.setTextColor(ready ? GOOD : (hosting ? WARN : Color.WHITE));
        if (service == null) homeDetail.setText("Connecting to the background service...");
        else if (service.getLastError().length() > 0) homeDetail.setText(service.getLastError());
        else if (ready) homeDetail.setText("Ready. Connect a tablet and open Chrome.");
        else if (hosting) homeDetail.setText("CarStream is preparing the private network.");
        else homeDetail.setText("Tap Start. CarStream handles the rest.");
        homePermission.setText(permissionSummary());
        homePermission.setTextColor(allRequiredPermissionsGranted() ? GOOD : WARN);
        homeStartButton.setText(hosting ? "Stop CarStream" : "Start CarStream");
        homeStartButton.setBackground(round(hosting ? Color.rgb(145, 57, 57) : ACCENT, 14));
        homeConnectionCard.setVisibility(ready ? View.VISIBLE : View.GONE);
        if (ready) {
            homeNetwork.setText(service.getNetworkName());
            homePassword.setText(service.getNetworkPassword().length() == 0 ? "No password" : service.getNetworkPassword());
            homeUrl.setText(service.getTabletUrl());
            homePairing.setText(service.getPairingCode());
            homeWebDav.setText(service.getWebDavUrl());
        }
        if (service == null) homeCounts.setText("TorBox and screen information will appear after the service connects.");
        else homeCounts.setText(service.getLibraryStatus() + " • " + onlineClients().size() + " connected screen" + (onlineClients().size() == 1 ? "" : "s") + " • " + service.getStreamSummary());
    }

    private void updateScreensPage() {
        if (screensSelected == null || screensClients == null) return;
        screensSelected.setText(selectedMedia == null ? "No video selected" : selectedMedia.fileName() + "\n" + selectedMedia.locationLabel());
        screensSelected.setTextColor(selectedMedia == null ? WARN : GOOD);
        List<ClientSession> clients = onlineClients();
        screensSummary.setText((service == null ? "Service is connecting" : service.getStreamSummary()) + " • " + clients.size() + " screen" + (clients.size() == 1 ? "" : "s") + " online");
        screensClients.removeAllViews();
        if (clients.isEmpty()) {
            screensClients.addView(messageCard("No browser screens are connected", "Start CarStream, join its Wi-Fi on a tablet, and open the Chrome address shown on Home."));
            return;
        }
        for (ClientSession client : clients) screensClients.addView(clientCard(client), topMargin(matchWrap(), screensClients.getChildCount() == 0 ? 0 : 10));
    }

    private void updateLibraryPage() {
        if (libraryAdapter == null || libraryPath == null || libraryStatus == null) return;
        List<MediaItem> items = currentLibrary();
        String query = librarySearch == null ? "" : librarySearch.getText().toString();
        List<LibraryNavigator.Entry> fresh = LibraryNavigator.entries(items, libraryLocation, query);
        libraryEntries.clear();
        libraryEntries.addAll(fresh);
        libraryAdapter.notifyDataSetChanged();
        libraryPath.setText(query.trim().length() > 0 ? "Search all folders" : libraryLocation.label());
        if (service == null) libraryStatus.setText("Connecting to CarStream service...");
        else {
            String suffix = libraryEntries.isEmpty() ? "" : " • " + libraryEntries.size() + " visible item" + (libraryEntries.size() == 1 ? "" : "s");
            libraryStatus.setText(service.getLibraryStatus() + suffix);
        }
    }

    private void updateDiagnosticsPage() {
        if (diagnosticsSummary == null || diagnosticsLog == null) return;
        diagnosticsSummary.setText(liveSummary());
        String log = EventLogger.readRecent(this, 220 * 1024);
        diagnosticsLog.setText(log.length() == 0 ? "No events recorded yet." : log);
    }

    private void handleLibraryClick(int position) {
        if (position < 0 || position >= libraryEntries.size()) return;
        LibraryNavigator.Entry entry = libraryEntries.get(position);
        if (entry.folder) {
            LibraryNavigator.open(libraryLocation, entry);
            LibraryNavigator.skipWrappers(currentLibrary(), libraryLocation);
            clearLibrarySearch();
            updateLibraryPage();
            return;
        }
        final MediaItem item = entry.item;
        if (item == null) return;
        if (!item.ready) { toast("TorBox is still preparing that video"); return; }
        if (librarySelectionMode) {
            selectedMedia = item;
            librarySelectionMode = false;
            showPage(libraryReturnPage, false);
            return;
        }
        showMediaActions(item);
    }

    private void showMediaActions(final MediaItem item) {
        List<String> labels = new ArrayList<String>();
        final List<ClientSession> clients = onlineClients();
        for (ClientSession client : clients) labels.add("Play on " + client.name);
        if (!clients.isEmpty()) labels.add("Play on every connected screen");
        labels.add("Select for Screens");
        labels.add("Open on this phone");
        labels.add("Edit Smart Skip markers");
        labels.add("Cancel");
        final int allIndex = clients.size();
        final int selectIndex = allIndex + (clients.isEmpty() ? 0 : 1);
        final int phoneIndex = selectIndex + 1;
        final int markersIndex = phoneIndex + 1;
        new AlertDialog.Builder(this)
                .setTitle(item.fileName())
                .setMessage(item.locationLabel())
                .setItems(labels.toArray(new String[labels.size()]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        safeRun("performing a video action", new Runnable() {
                            @Override public void run() {
                                if (which < clients.size()) {
                                    boolean sent = service != null && service.sendMedia(clients.get(which).id, item, 0, true);
                                    toast(sent ? "Sent to " + clients.get(which).name : "That screen disconnected");
                                } else if (!clients.isEmpty() && which == allIndex) {
                                    sendItemToAll(item);
                                } else if (which == selectIndex) {
                                    selectedMedia = item;
                                    showPage(PAGE_SCREENS, true);
                                } else if (which == phoneIndex) {
                                    openExternal(item);
                                } else if (which == markersIndex) {
                                    showSkipMarkerEditor(item);
                                }
                            }
                        });
                    }
                }).show();
    }

    private View clientCard(final ClientSession client) {
        LinearLayout card = card();
        LinearLayout heading = horizontal();
        LinearLayout labels = vertical();
        labels.addView(text(client.name, 17, Color.WHITE, true));
        String state = client.buffering ? "Buffering" : (client.paused ? "Paused" : "Playing");
        labels.addView(text(state + " • " + formatTime(client.positionSeconds) + " / " + formatTime(client.durationSeconds), 12, client.buffering ? WARN : MUTED, false), topMargin(matchWrap(), 2));
        heading.addView(labels, weight());
        Button mode = tinyButton(client.controlMode.label, client.controlMode.description, new Runnable() {
            @Override public void run() { cycleClientMode(client); }
        });
        heading.addView(mode, wrap());
        card.addView(heading, matchWrap());
        TextView title = text(client.title == null ? "Nothing playing" : client.title, 14, Color.WHITE, false);
        title.setMaxLines(3);
        card.addView(title, topMargin(matchWrap(), 8));
        ProgressBar progress = new ProgressBar(this);
        progress.setMax(1000);
        progress.setProgress(client.progressPermille());
        card.addView(progress, topMargin(matchWrap(), 7));

        LinearLayout first = horizontal();
        first.addView(tinyButton("-10", null, new Runnable() { @Override public void run() { if (service != null) service.sendSeekRelative(client.id, -10); } }), weight());
        first.addView(tinyButton(client.paused ? "Play" : "Pause", null, new Runnable() { @Override public void run() { if (service != null) { if (client.paused) service.sendPlay(client.id); else service.sendPause(client.id); } } }), weightLeft());
        first.addView(tinyButton("+10", null, new Runnable() { @Override public void run() { if (service != null) service.sendSeekRelative(client.id, 10); } }), weightLeft());
        first.addView(tinyButton("+30", null, new Runnable() { @Override public void run() { if (service != null) service.sendSeekRelative(client.id, 30); } }), weightLeft());
        card.addView(first, topMargin(matchWrap(), 9));
        LinearLayout second = horizontal();
        second.addView(tinyButton("Next", "Play the next ready file in the same folder.", new Runnable() { @Override public void run() { sendNext(client); } }), weight());
        second.addView(tinyButton("Stop", null, new Runnable() { @Override public void run() { if (service != null) service.sendStop(client.id); } }), weightLeft());
        second.addView(tinyButton("Play selected", null, new Runnable() { @Override public void run() { sendSelected(client); } }), weightLeft());
        card.addView(second, topMargin(matchWrap(), 7));
        if (client.needsGesture) card.addView(text("Chrome needs one tap on the video before remote Play can work.", 11, WARN, false), topMargin(matchWrap(), 7));
        return card;
    }

    private void cycleClientMode(ClientSession client) {
        ControlMode next = client.controlMode == ControlMode.FREE ? ControlMode.GUIDED
                : client.controlMode == ControlMode.GUIDED ? ControlMode.LOCKED : ControlMode.FREE;
        if (service != null) service.setControlMode(client.id, next);
    }

    private void sendSelected(ClientSession client) {
        if (selectedMedia == null) { toast("Choose a video first"); return; }
        boolean sent = service != null && service.sendMedia(client.id, selectedMedia, 0, true);
        toast(sent ? "Sent to " + client.name : "That screen disconnected");
    }

    private void sendSelectedToAll() {
        if (selectedMedia == null) { toast("Choose a video first"); return; }
        sendItemToAll(selectedMedia);
    }

    private void sendItemToAll(MediaItem item) {
        int count = service == null ? 0 : service.sendMediaAll(item, 0, true);
        toastCount(count, "started");
    }

    private void sendNext(ClientSession client) {
        if (service == null) return;
        MediaItem current = findMedia(client.mediaId);
        MediaItem next = LibraryNavigator.nextInFolder(service.getLibrary(), current);
        if (next == null) { toast("No next ready episode in this folder"); return; }
        service.sendMedia(client.id, next, 0, true);
    }

    private void startOrStop() {
        if (service != null && service.isHosting()) {
            service.stopHosting(false);
            return;
        }
        requestMissingPermissions(true);
    }

    private void startHostingNow() {
        ensureServiceThen(new Runnable() {
            @Override public void run() {
                try {
                    Intent intent = new Intent(MainActivity.this, HostService.class);
                    intent.setAction(HostService.ACTION_START);
                    startService(intent);
                    service.startHosting();
                } catch (Throwable error) {
                    EventLogger.error(MainActivity.this, "UI", "Could not start CarStream", error);
                    showRecoverableProblem("Could not start CarStream", error);
                }
            }
        });
    }

    private void bindToService() {
        if (bound || binding) return;
        try {
            binding = bindService(new Intent(this, HostService.class), serviceConnection, Context.BIND_AUTO_CREATE);
            if (!binding) EventLogger.error(this, "UI", "Android refused service binding");
        } catch (Throwable error) {
            binding = false;
            EventLogger.error(this, "UI", "Service binding failed", error);
            if (currentPage == PAGE_HOME) updateHomePage();
        }
    }

    private void ensureServiceThen(final Runnable action) {
        if (service != null) { action.run(); return; }
        bindToService();
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (service != null) action.run();
                else toast("CarStream service is still connecting. Try again in a moment.");
            }
        }, 700L);
    }

    private void requestMissingPermissions(boolean startAfter) {
        if (Build.VERSION.SDK_INT < 23) {
            if (startAfter) startHostingNow();
            return;
        }
        ArrayList<String> missing = missingPermissions();
        if (missing.isEmpty()) {
            if (startAfter) startHostingNow();
            return;
        }
        pendingStartAfterPermission = startAfter;
        EventLogger.info(this, "Permissions", "Requesting " + missing.size() + " missing permission(s)");
        requestPermissions(missing.toArray(new String[missing.size()]), REQUEST_PERMISSIONS);
    }

    private ArrayList<String> missingPermissions() {
        ArrayList<String> result = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            result.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            String nearby = "android.permission.NEARBY_WIFI_DEVICES";
            String notifications = "android.permission.POST_NOTIFICATIONS";
            if (checkSelfPermission(nearby) != PackageManager.PERMISSION_GRANTED) result.add(nearby);
            if (checkSelfPermission(notifications) != PackageManager.PERMISSION_GRANTED) result.add(notifications);
        }
        return result;
    }

    private boolean allRequiredPermissionsGranted() { return missingPermissions().isEmpty(); }

    private String permissionSummary() {
        ArrayList<String> missing = missingPermissions();
        return missing.isEmpty() ? "Permissions ready" : missing.size() + " Android permission" + (missing.size() == 1 ? "" : "s") + " still needed";
    }

    private void showPermissionExplanation() {
        new AlertDialog.Builder(this)
                .setTitle("CarStream needs permission")
                .setMessage("CarStream needs nearby Wi-Fi/location permission to create the private network. Notifications let Android keep the server running while you use another app. You can still browse TorBox without starting the network.")
                .setPositiveButton("Try again", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { requestMissingPermissions(false); }
                })
                .setNegativeButton("Not now", null)
                .setNeutralButton("App settings", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { openAndroidAppSettings(); }
                }).show();
    }

    private void showPermissionManagement() {
        String message = permissionSummary() + "\n\nCarStream asks automatically when it opens and again when Start is pressed. If Android no longer shows a prompt, open App settings and allow the permissions there.";
        new AlertDialog.Builder(this)
                .setTitle("Permissions")
                .setMessage(message)
                .setPositiveButton("Request now", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { requestMissingPermissions(false); }
                })
                .setNeutralButton("App settings", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { openAndroidAppSettings(); }
                })
                .setNegativeButton("Done", null).show();
    }

    private void openAndroidAppSettings() {
        try {
            Intent intent = new Intent("android.settings.APPLICATION_DETAILS_SETTINGS",
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable error) { showRecoverableProblem("Could not open Android settings", error); }
    }

    private void saveBasicSettings() {
        try {
            secureStore.saveTorBoxKey(settingApiKey.getText().toString());
            appSettings.setWifiName(settingWifiName.getText().toString());
            appSettings.setWifiPassword(settingWifiPassword.getText().toString());
            appSettings.setOnlineSkipLookupEnabled(settingOnlineMarkers.isChecked());
            appSettings.setIntroSkipMode((String) settingIntroMode.getTag());
            appSettings.setRecapSkipMode((String) settingRecapMode.getTag());
            appSettings.setCreditsSkipMode((String) settingCreditsMode.getTag());
            appSettings.setSkipCountdownSeconds(((Integer) settingCountdown.getTag()).intValue());
            if (service != null) service.settingsChanged();
            EventLogger.info(this, "Settings", "Basic settings saved");
            toast("Settings saved");
        } catch (Throwable error) {
            EventLogger.error(this, "Settings", "Could not save settings", error);
            showRecoverableProblem("Could not save settings", error);
        }
    }

    private void saveAdvancedSettings(boolean showToast) {
        try {
            appSettings.setRemoteClientEnabled(advancedRemoteEnabled.isChecked());
            appSettings.setRemoteManifestUrl(advancedManifest.getText().toString());
            appSettings.setBrowserHome(advancedBrowserHome.getText().toString());
            appSettings.setProjectUrl(advancedProjectUrl.getText().toString());
            if (service != null) service.settingsChanged();
            EventLogger.info(this, "Settings", "Advanced settings saved");
            if (showToast) toast("Advanced settings saved");
        } catch (Throwable error) {
            EventLogger.error(this, "Settings", "Could not save advanced settings", error);
            showRecoverableProblem("Could not save advanced settings", error);
        }
    }

    private Button modeButton(final String prefix, String current, final boolean credits) {
        final Button button = button("", false);
        button.setTag(current);
        updateModeButton(button, prefix, current, credits);
        button.setOnClickListener(safeClick("changing Smart Skip mode", new Runnable() {
            @Override public void run() {
                String value = (String) button.getTag();
                String next;
                if (AppSettings.SKIP_BUTTON.equals(value)) next = credits ? AppSettings.SKIP_AUTO_NEXT : AppSettings.SKIP_AUTO;
                else if ((credits && AppSettings.SKIP_AUTO_NEXT.equals(value)) || (!credits && AppSettings.SKIP_AUTO.equals(value))) next = AppSettings.SKIP_OFF;
                else next = AppSettings.SKIP_BUTTON;
                button.setTag(next);
                updateModeButton(button, prefix, next, credits);
            }
        }));
        return button;
    }

    private void updateModeButton(Button button, String prefix, String value, boolean credits) {
        String label = AppSettings.SKIP_OFF.equals(value) ? "Off"
                : AppSettings.SKIP_BUTTON.equals(value) ? "Show a Skip button"
                : credits ? "Start next episode automatically" : "Skip automatically";
        button.setText(prefix + ": " + label);
    }

    private Button countdownButton(int current) {
        final Button button = button("Skip countdown: " + current + " seconds", false);
        button.setTag(Integer.valueOf(current));
        button.setOnClickListener(safeClick("changing the skip countdown", new Runnable() {
            @Override public void run() {
                int value = ((Integer) button.getTag()).intValue();
                int next = value == 3 ? 5 : value == 5 ? 8 : 3;
                button.setTag(Integer.valueOf(next));
                button.setText("Skip countdown: " + next + " seconds");
            }
        }));
        return button;
    }

    private void showMagnetDialog() {
        final EditText input = input("magnet:?xt=...");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(4);
        input.setMaxLines(8);
        new AlertDialog.Builder(this)
                .setTitle("Add magnet to TorBox")
                .setMessage("CarStream sends the magnet to your TorBox account. Use media you are authorized to access.")
                .setView(input)
                .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        String value = input.getText().toString().trim();
                        if (!value.startsWith("magnet:?")) { toast("Paste a complete magnet link"); return; }
                        ensureServiceThen(new Runnable() { @Override public void run() { service.addMagnet(value); } });
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void pasteMagnetFromClipboard() {
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null || !manager.hasPrimaryClip() || manager.getPrimaryClip() == null || manager.getPrimaryClip().getItemCount() == 0) {
            toast("Clipboard is empty"); return;
        }
        CharSequence text = manager.getPrimaryClip().getItemAt(0).coerceToText(this);
        String value = text == null ? "" : text.toString().trim();
        if (!value.startsWith("magnet:?")) { toast("The clipboard does not contain a magnet link"); return; }
        confirmMagnet(value);
    }

    private boolean interceptBrowserUrl(String url) {
        if (url == null) return false;
        if (url.toLowerCase(Locale.US).startsWith("magnet:?")) {
            confirmMagnet(url);
            return true;
        }
        return false;
    }

    private void confirmMagnet(final String magnet) {
        new AlertDialog.Builder(this)
                .setTitle("Add this magnet to TorBox?")
                .setMessage("CarStream will submit it to your account and refresh the library while TorBox prepares it.")
                .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        ensureServiceThen(new Runnable() { @Override public void run() { service.addMagnet(magnet); } });
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void browserGo(String raw) {
        if (miniBrowser == null) return;
        String value = raw == null ? "" : raw.trim();
        if (value.length() == 0) value = appSettings.getBrowserHome();
        if (value.startsWith("magnet:?")) { confirmMagnet(value); return; }
        if (!(value.startsWith("http://") || value.startsWith("https://"))) {
            value = "https://duckduckgo.com/?q=" + Uri.encode(value);
        }
        miniBrowserAddress.setText(value);
        miniBrowserStatus.setText("Loading...");
        miniBrowser.loadUrl(value);
    }

    private void destroyMiniBrowser() {
        if (miniBrowser != null) {
            try { miniBrowser.stopLoading(); miniBrowser.destroy(); }
            catch (Throwable ignored) { }
        }
        miniBrowser = null;
        miniBrowserAddress = null;
        miniBrowserStatus = null;
    }

    private void openSelectedExternally() {
        if (selectedMedia == null) { toast("Choose a video first"); return; }
        openExternal(selectedMedia);
    }

    private void openExternal(final MediaItem item) {
        ensureServiceThen(new Runnable() {
            @Override public void run() {
                service.requestLocalPlaybackUrl(item, new HostService.PlaybackUrlCallback() {
                    @Override public void onPlaybackUrl(final String url, final String error) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (error != null && error.length() > 0) { toast(error); return; }
                                try {
                                    Intent intent = new Intent(Intent.ACTION_VIEW);
                                    intent.setDataAndType(Uri.parse(url), item.mimeType);
                                    startActivity(Intent.createChooser(intent, "Open video with"));
                                } catch (Throwable failure) { showRecoverableProblem("Could not open a video player", failure); }
                            }
                        });
                    }
                });
            }
        });
    }

    private void showSkipMarkerEditor(final MediaItem item) {
        if (service == null) { toast("CarStream service is still connecting"); return; }
        final EpisodeIdentity identity = service.getEpisodeIdentity(item);
        final LinearLayout form = vertical();
        form.setPadding(dp(8), dp(4), dp(8), dp(2));
        final EditText recapStart = markerInput("Recap starts (m:ss)");
        final EditText recapEnd = markerInput("Recap ends (m:ss)");
        final EditText introStart = markerInput("Intro starts (m:ss)");
        final EditText introEnd = markerInput("Intro ends (m:ss)");
        final EditText creditsStart = markerInput("Credits start (m:ss)");
        final EditText creditsEnd = markerInput("Credits end, optional (m:ss)");
        SkipMarkerStore.ManualMatch manual = service.getManualSkipMarkers(item, identity);
        if (manual != null) fillMarkerInputs(manual.segments, recapStart, recapEnd, introStart, introEnd, creditsStart, creditsEnd);
        form.addView(text(item.fileName(), 13, Color.WHITE, true));
        form.addView(text(identity == null ? item.locationLabel() : identity.description(), 11, MUTED, false), topMargin(matchWrap(), 3));
        form.addView(recapStart, topMargin(matchWrap(), 8)); form.addView(recapEnd, topMargin(matchWrap(), 5));
        form.addView(introStart, topMargin(matchWrap(), 8)); form.addView(introEnd, topMargin(matchWrap(), 5));
        form.addView(creditsStart, topMargin(matchWrap(), 8)); form.addView(creditsEnd, topMargin(matchWrap(), 5));
        final String[] scopes = new String[] { SkipMarkerStore.SCOPE_EPISODE, SkipMarkerStore.SCOPE_FOLDER, SkipMarkerStore.SCOPE_SEASON, SkipMarkerStore.SCOPE_SHOW };
        final int[] scopeIndex = new int[] { 0 };
        final Button scopeButton = button("Apply to: this episode", false);
        scopeButton.setOnClickListener(safeClick("changing marker scope", new Runnable() {
            @Override public void run() {
                scopeIndex[0] = (scopeIndex[0] + 1) % scopes.length;
                String label = scopeIndex[0] == 0 ? "this episode" : scopeIndex[0] == 1 ? "this folder" : scopeIndex[0] == 2 ? "this season" : "this show";
                scopeButton.setText("Apply to: " + label);
            }
        }));
        form.addView(scopeButton, topMargin(matchWrap(), 9));
        ScrollView wrapper = new ScrollView(this); wrapper.addView(form, matchWrap());
        new AlertDialog.Builder(this)
                .setTitle("Smart Skip markers")
                .setMessage("Enter times as seconds, m:ss, or h:mm:ss. Leave a pair blank to remove it. Credits may omit the end time.")
                .setView(wrapper)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        try {
                            List<SkipSegment> segments = readMarkerInputs(recapStart, recapEnd, introStart, introEnd, creditsStart, creditsEnd);
                            service.saveManualSkipMarkers(scopes[scopeIndex[0]], item, identity, segments);
                            toast("Smart Skip markers saved");
                        } catch (Throwable error) { showRecoverableProblem("Could not save markers", error); }
                    }
                })
                .setNeutralButton("Find online", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        service.resolveSkipMarkers(item, true, new HostService.SkipMarkersCallback() {
                            @Override public void onSkipMarkers(final SkipMarkerResolver.Resolution resolution) {
                                runOnUiThread(new Runnable() {
                                    @Override public void run() { toast(resolutionSummary(resolution)); }
                                });
                            }
                        });
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private String resolutionSummary(SkipMarkerResolver.Resolution resolution) {
        if (resolution == null) return "No markers found";
        int count = resolution.segments == null ? 0 : resolution.segments.size();
        if (count > 0) {
            String source = resolution.source == null || resolution.source.trim().isEmpty() ? "markers" : resolution.source;
            return count + (count == 1 ? " marker" : " markers") + " found from " + source;
        }
        if (resolution.warning != null && !resolution.warning.trim().isEmpty()) return resolution.warning;
        return "No markers found";
    }

    private EditText markerInput(String hint) {
        EditText input = input(hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        return input;
    }

    private void fillMarkerInputs(List<SkipSegment> segments, EditText rs, EditText re, EditText is, EditText ie, EditText cs, EditText ce) {
        if (segments == null) return;
        for (SkipSegment segment : segments) {
            if (SkipSegment.RECAP.equals(segment.type)) { rs.setText(formatMarker(segment.startMillis)); re.setText(formatMarker(segment.endMillis)); }
            else if (SkipSegment.INTRO.equals(segment.type)) { is.setText(formatMarker(segment.startMillis)); ie.setText(formatMarker(segment.endMillis)); }
            else if (SkipSegment.CREDITS.equals(segment.type)) { cs.setText(formatMarker(segment.startMillis)); if (segment.endMillis > 0) ce.setText(formatMarker(segment.endMillis)); }
        }
    }

    private List<SkipSegment> readMarkerInputs(EditText rs, EditText re, EditText is, EditText ie, EditText cs, EditText ce) {
        ArrayList<SkipSegment> result = new ArrayList<SkipSegment>();
        addMarkerPair(result, SkipSegment.RECAP, rs, re, false);
        addMarkerPair(result, SkipSegment.INTRO, is, ie, false);
        addMarkerPair(result, SkipSegment.CREDITS, cs, ce, true);
        return result;
    }

    private void addMarkerPair(List<SkipSegment> result, String type, EditText start, EditText end, boolean endOptional) {
        String s = start.getText().toString().trim();
        String e = end.getText().toString().trim();
        if (s.length() == 0 && e.length() == 0) return;
        long startMs = parseTime(s);
        long endMs = e.length() == 0 && endOptional ? 0L : parseTime(e);
        if (startMs < 0 || (!endOptional && endMs <= startMs) || (endMs > 0 && endMs <= startMs)) {
            throw new IllegalArgumentException("Check the " + SkipSegment.displayName(type).toLowerCase(Locale.US) + " times");
        }
        result.add(new SkipSegment(type, startMs, endMs, "manual", SkipSegment.defaultLabel(type), 1.0));
    }

    private static long parseTime(String value) {
        if (value == null || value.trim().length() == 0) return -1L;
        String clean = value.trim();
        try {
            if (clean.indexOf(':') < 0) return Math.round(Double.parseDouble(clean) * 1000.0);
            String[] parts = clean.split(":");
            double seconds = 0.0;
            for (int i = 0; i < parts.length; i++) seconds = seconds * 60.0 + Double.parseDouble(parts[i]);
            return Math.round(seconds * 1000.0);
        } catch (Exception error) { return -1L; }
    }

    private static String formatMarker(long millis) { return formatTime(millis / 1000.0); }

    private void showWifiQr() {
        if (service == null || service.getNetworkName().length() == 0) { toast("Start CarStream first"); return; }
        String name = service.getNetworkName();
        String password = service.getNetworkPassword();
        String payload = password == null || password.length() == 0
                ? "WIFI:T:nopass;S:" + wifiQrEscape(name) + ";;"
                : "WIFI:T:WPA;S:" + wifiQrEscape(name) + ";P:" + wifiQrEscape(password) + ";H:false;;";
        WebView qr = new WebView(this);
        qr.setBackgroundColor(Color.WHITE);
        qr.getSettings().setJavaScriptEnabled(true);
        qr.getSettings().setAllowFileAccess(true);
        qr.loadUrl("file:///android_asset/qr/qr.html#" + Uri.encode(payload));
        LinearLayout wrapper = vertical();
        wrapper.setBackgroundColor(Color.WHITE);
        wrapper.setPadding(dp(8), dp(8), dp(8), dp(4));
        wrapper.addView(qr, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(310)));
        TextView details = text("Network: " + name + "\nPassword: " + (password == null || password.length() == 0 ? "None" : password), 12, Color.BLACK, false);
        details.setGravity(Gravity.CENTER);
        details.setTextIsSelectable(true);
        wrapper.addView(details, topMargin(matchWrap(), 5));
        new AlertDialog.Builder(this).setTitle("Scan to join CarStream Wi-Fi")
                .setMessage("This QR uses the exact network name and password Android supplied.")
                .setView(wrapper).setPositiveButton("Done", null).show();
    }

    private void showWatchQr() {
        if (service == null || service.getTabletUrl().length() == 0) { toast("Start CarStream first"); return; }
        String url = service.getTabletUrl() + "#code=" + service.getPairingCode();
        WebView qr = new WebView(this);
        qr.setBackgroundColor(Color.WHITE);
        qr.getSettings().setJavaScriptEnabled(true);
        qr.getSettings().setAllowFileAccess(true);
        qr.loadUrl("file:///android_asset/qr/qr.html#" + Uri.encode(url));
        LinearLayout wrapper = vertical();
        wrapper.setPadding(dp(8), dp(8), dp(8), dp(8));
        wrapper.addView(qr, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(310)));
        TextView address = text(service.getTabletUrl() + "\nCode: " + service.getPairingCode(), 14, Color.WHITE, false);
        address.setGravity(Gravity.CENTER);
        address.setTextIsSelectable(true);
        wrapper.addView(address, topMargin(matchWrap(), 8));
        new AlertDialog.Builder(this).setTitle("2. Open the shows")
                .setMessage("First join CarStream Wi-Fi. Then scan this code on the tablet and open it in Chrome.")
                .setView(wrapper).setPositiveButton("Done", null).show();
    }

    private static String wifiQrEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"");
    }

    private void showStremioQr() {
        if (service == null || service.getStremioSetupUrl().isEmpty()) { toast("Start CarStream first"); return; }
        final String url = service.getStremioSetupUrl();
        WebView qr = new WebView(this);
        qr.setBackgroundColor(Color.WHITE);
        qr.getSettings().setJavaScriptEnabled(true);
        qr.getSettings().setAllowFileAccess(true);
        qr.loadUrl("file:///android_asset/qr/qr.html#" + Uri.encode(url));
        LinearLayout wrapper = vertical();
        wrapper.setPadding(dp(8), dp(8), dp(8), dp(8));
        wrapper.addView(qr, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(310)));
        new AlertDialog.Builder(this).setTitle("Try Stremio on the tablet")
                .setMessage("Install and open Stremio on home Wi-Fi first. Then join CarStream Wi-Fi and scan this code in Chrome for the add-on address. This integration still needs a tablet test.")
                .setView(wrapper).setPositiveButton("Done", null)
                .setNeutralButton("Copy setup link", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { copy("Stremio setup", url); }
                }).show();
    }

    private void shareSetup() {
        if (service == null || service.getTabletUrl().length() == 0) { toast("Start CarStream first"); return; }
        String value = "CarStream setup\n\nWi-Fi: " + service.getNetworkName()
                + "\nPassword: " + service.getNetworkPassword()
                + "\nOpen: " + service.getTabletUrl()
                + "\nPairing code: " + service.getPairingCode()
                + "\n\nStay connected even when Android says this Wi-Fi has no Internet.";
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, "CarStream setup");
        share.putExtra(Intent.EXTRA_TEXT, value);
        startActivity(Intent.createChooser(share, "Share CarStream setup"));
    }

    private void shareDiagnostics() {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, "CarStream diagnostics");
        share.putExtra(Intent.EXTRA_TEXT, EventLogger.buildReport(this, liveSummary()));
        startActivity(Intent.createChooser(share, "Share CarStream diagnostics"));
    }

    private String liveSummary() {
        StringBuilder value = new StringBuilder();
        value.append("App: CarStream ").append(BuildInfo.VERSION_NAME).append('\n')
                .append("Page: ").append(pageName(currentPage)).append('\n')
                .append("Permissions: ").append(permissionSummary()).append('\n')
                .append("Service: ").append(service == null ? (binding ? "connecting" : "not connected") : "connected").append('\n');
        if (service != null) {
            value.append("Private network: ").append(service.isHosting() ? service.getStatus() : "stopped").append('\n')
                    .append("Route: ").append(service.getRouteSummary()).append('\n')
                    .append("TorBox: ").append(service.getLibraryStatus()).append('\n')
                    .append("Library files: ").append(service.getLibrary().size()).append('\n')
                    .append("Screens known: ").append(service.getClients().size()).append('\n')
                    .append("Streams: ").append(service.getStreamSummary()).append('\n')
                    .append("Browser bundle: ").append(service.getRemoteBundleStatus()).append('\n');
            if (service.getLastError().length() > 0) value.append("Current error: ").append(service.getLastError()).append('\n');
        }
        return value.toString().trim();
    }

    private List<MediaItem> currentLibrary() { return service == null ? Collections.<MediaItem>emptyList() : service.getLibrary(); }

    private List<ClientSession> onlineClients() {
        List<ClientSession> result = new ArrayList<ClientSession>();
        if (service != null) for (ClientSession client : service.getClients()) if (client.isOnline()) result.add(client);
        Collections.sort(result, new Comparator<ClientSession>() {
            @Override public int compare(ClientSession a, ClientSession b) { return a.name.compareToIgnoreCase(b.name); }
        });
        return result;
    }

    private MediaItem findMedia(String id) {
        if (id == null || service == null) return null;
        for (MediaItem item : service.getLibrary()) if (id.equals(item.id)) return item;
        return null;
    }

    private void clearLibrarySearch() { if (librarySearch != null && librarySearch.getText().length() > 0) librarySearch.setText(""); }

    private void copy(String label, String value) {
        if (value == null || value.length() == 0) { toast("Nothing to copy yet"); return; }
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) manager.setPrimaryClip(ClipData.newPlainText(label, value));
        toast(label + " copied");
    }

    private void safeRun(String action, Runnable work) {
        try { work.run(); }
        catch (Throwable error) {
            EventLogger.error(this, "UI", "Problem while " + action, error);
            showRecoverableProblem("Problem while " + action, error);
        }
    }

    private View.OnClickListener safeClick(final String action, final Runnable work) {
        return new View.OnClickListener() {
            @Override public void onClick(View view) { safeRun(action, work); }
        };
    }

    private void showRecoverableProblem(String title, Throwable error) {
        String detail = safeMessage(error);
        new AlertDialog.Builder(this).setTitle(title).setMessage(detail)
                .setPositiveButton("Diagnostics", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { showPage(PAGE_DIAGNOSTICS, true); }
                }).setNegativeButton("Close", null).show();
    }

    private void showPageFailure(final int page, Throwable error) {
        LinearLayout root = pageBody();
        root.addView(text("CarStream could not open " + pageName(page), 23, BAD, true));
        root.addView(text(safeMessage(error), 14, Color.WHITE, false), topMargin(matchWrap(), 10));
        root.addView(fullButton("Return home", true, new Runnable() { @Override public void run() { pageHistory.clear(); showPage(PAGE_HOME, false); } }), topMargin(matchWrap(), 16));
        root.addView(fullButton("Open diagnostics", false, new Runnable() { @Override public void run() { showPage(PAGE_DIAGNOSTICS, true); } }), topMargin(matchWrap(), 8));
        setContentView(root);
    }

    private void showFatalFallback(Throwable error) {
        LinearLayout root = pageBody();
        root.addView(text("CarStream could not finish opening", 23, BAD, true));
        TextView detail = text(safeMessage(error), 13, Color.WHITE, false);
        detail.setTextIsSelectable(true);
        root.addView(detail, topMargin(matchWrap(), 10));
        root.addView(fullButton("Copy crash report", true, new Runnable() {
            @Override public void run() { copy("CarStream crash", EventLogger.buildReport(MainActivity.this, "Startup failed")); }
        }), topMargin(matchWrap(), 16));
        setContentView(root);
    }

    private ScrollView pageScroll(String title, String subtitle) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = pageBody();
        body.addView(pageHeader(title, subtitle), matchWrap());
        scroll.addView(body, matchWrap());
        return scroll;
    }

    private View pageHeader(String title, String subtitle) {
        LinearLayout card = card();
        LinearLayout row = horizontal();
        Button back = tinyButton("Back", null, new Runnable() { @Override public void run() { onBackPressed(); } });
        row.addView(back, wrap());
        LinearLayout labels = vertical();
        labels.addView(text(title, 23, Color.WHITE, true));
        if (subtitle != null && subtitle.length() > 0) labels.addView(text(subtitle, 12, MUTED, false), topMargin(matchWrap(), 2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(11);
        row.addView(labels, lp);
        card.addView(row, matchWrap());
        return card;
    }

    private LinearLayout pageBody() {
        LinearLayout root = vertical();
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(14), dp(16), dp(18));
        return root;
    }

    private LinearLayout card() {
        LinearLayout value = vertical();
        value.setPadding(dp(14), dp(13), dp(14), dp(13));
        value.setBackground(round(CARD, 14));
        return value;
    }

    private LinearLayout navCard(String title, String subtitle, Runnable action) {
        LinearLayout card = card();
        card.setBackground(round(CARD_ALT, 14));
        card.addView(text(title, 18, Color.WHITE, true));
        card.addView(text(subtitle, 12, MUTED, false), topMargin(matchWrap(), 3));
        card.setOnClickListener(safeClick("opening " + title, action));
        card.setMinimumHeight(dp(70));
        if (Build.VERSION.SDK_INT >= 26) card.setTooltipText(subtitle);
        return card;
    }

    private LinearLayout messageCard(String title, String subtitle) {
        LinearLayout card = card();
        card.addView(text(title, 16, Color.WHITE, true));
        card.addView(text(subtitle, 12, MUTED, false), topMargin(matchWrap(), 4));
        return card;
    }

    private LinearLayout helpCard(String title, String detail) { return messageCard(title, detail); }

    private LinearLayout sectionTitle(String title, String explanation) {
        LinearLayout wrapper = vertical();
        LinearLayout row = horizontal();
        row.addView(text(title, 17, Color.WHITE, true), weight());
        if (explanation != null && explanation.length() > 0) {
            row.addView(tinyButton("?", explanation, new Runnable() {
                @Override public void run() { new AlertDialog.Builder(MainActivity.this).setTitle(title).setMessage(explanation).setPositiveButton("Got it", null).show(); }
            }), wrap());
        }
        wrapper.addView(row, matchWrap());
        if (explanation != null && explanation.length() > 0) wrapper.addView(text(explanation, 11, MUTED, false), topMargin(matchWrap(), 3));
        return wrapper;
    }

    private TextView sectionHeading(String value) { return text(value.toUpperCase(Locale.US), 12, Color.rgb(145, 170, 235), true); }

    private LinearLayout connectionRow(String label, TextView value, Runnable copyAction) {
        LinearLayout row = vertical();
        row.setPadding(dp(11), dp(9), dp(11), dp(9));
        row.setBackground(round(CARD_ALT, 10));
        row.addView(text(label.toUpperCase(Locale.US), 10, MUTED, true));
        row.addView(value, topMargin(matchWrap(), 2));
        row.setOnClickListener(safeClick("copying " + label, copyAction));
        return row;
    }

    private TextView connectionValue() {
        TextView view = text("", 15, Color.WHITE, true);
        view.setTextIsSelectable(true);
        view.setMaxLines(4);
        return view;
    }

    private EditText input(String hint) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setHintTextColor(Color.rgb(135, 147, 166));
        view.setTextColor(Color.WHITE);
        view.setTextSize(14);
        view.setSingleLine(true);
        view.setPadding(dp(11), dp(9), dp(11), dp(9));
        view.setBackground(round(CARD_ALT, 10));
        return view;
    }

    private CheckBox checkBox(String label, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextColor(Color.WHITE);
        box.setTextSize(13);
        box.setChecked(checked);
        return box;
    }

    private Button fullButton(String label, boolean primary, Runnable action) {
        Button button = button(label, primary);
        button.setMinHeight(dp(52));
        button.setOnClickListener(safeClick(label, action));
        return button;
    }

    private Button smallButton(String label, String tooltip, Runnable action) {
        Button button = button(label, false);
        button.setTextSize(12);
        button.setMinHeight(dp(44));
        if (tooltip != null) {
            button.setContentDescription(tooltip);
            if (Build.VERSION.SDK_INT >= 26) button.setTooltipText(tooltip);
        }
        button.setOnClickListener(safeClick(label, action));
        return button;
    }

    private Button tinyButton(String label, String tooltip, Runnable action) {
        Button button = smallButton(label, tooltip, action);
        button.setTextSize(10);
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setMinHeight(dp(40));
        return button;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(13);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(round(primary ? ACCENT : CARD_ALT, 12));
        return button;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value == null ? "" : value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setLineSpacing(0f, 1.08f);
        return view;
    }

    private LinearLayout horizontal() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.HORIZONTAL);
        value.setGravity(Gravity.CENTER_VERTICAL);
        return value;
    }

    private LinearLayout vertical() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        return value;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable value = new GradientDrawable();
        value.setColor(color);
        value.setCornerRadius(dp(radius));
        return value;
    }

    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); }
    private LinearLayout.LayoutParams weightLeft() { LinearLayout.LayoutParams p = weight(); p.leftMargin = dp(6); return p; }
    private LinearLayout.LayoutParams topMargin(LinearLayout.LayoutParams p, int top) { p.topMargin = dp(top); return p; }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static String pageName(int page) {
        switch (page) {
            case PAGE_SCREENS: return "Screens";
            case PAGE_LIBRARY: return "Library";
            case PAGE_SETTINGS: return "Settings";
            case PAGE_ADVANCED: return "Advanced";
            case PAGE_BROWSER: return "Mini browser";
            case PAGE_DIAGNOSTICS: return "Diagnostics";
            case PAGE_HELP: return "Help";
            default: return "Home";
        }
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "Unknown error";
        String value = error.getMessage();
        return value == null || value.trim().length() == 0 ? error.getClass().getSimpleName() : value.trim();
    }

    private static String formatTime(double seconds) {
        if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds < 0) seconds = 0;
        int total = (int) Math.floor(seconds);
        int hours = total / 3600;
        int minutes = (total % 3600) / 60;
        int secs = total % 60;
        return hours > 0 ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
                : String.format(Locale.US, "%d:%02d", minutes, secs);
    }

    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private void toastCount(int count, String action) { toast(count + " screen" + (count == 1 ? "" : "s") + " " + action); }

    private final class LibraryEntryAdapter extends BaseAdapter {
        @Override public int getCount() { return libraryEntries.size(); }
        @Override public Object getItem(int position) { return libraryEntries.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            RowHolder holder;
            if (convertView == null) {
                LinearLayout row = vertical();
                row.setPadding(dp(12), dp(9), dp(12), dp(9));
                TextView title = text("", 13, Color.WHITE, true);
                title.setMaxLines(4);
                TextView detail = text("", 10, MUTED, false);
                detail.setMaxLines(3);
                row.addView(title, matchWrap());
                row.addView(detail, topMargin(matchWrap(), 3));
                holder = new RowHolder(title, detail);
                row.setTag(holder);
                convertView = row;
            } else holder = (RowHolder) convertView.getTag();
            LibraryNavigator.Entry entry = libraryEntries.get(position);
            holder.title.setText((entry.folder ? "Folder  " : "Video  ") + entry.name);
            holder.detail.setText(entry.detail);
            holder.detail.setTextColor(entry.item != null && !entry.item.ready ? WARN : (entry.folder ? MUTED : GOOD));
            convertView.setBackground(round(entry.folder ? CARD : CARD_ALT, 11));
            return convertView;
        }
    }

    private static final class RowHolder {
        final TextView title;
        final TextView detail;
        RowHolder(TextView title, TextView detail) { this.title = title; this.detail = detail; }
    }
}
