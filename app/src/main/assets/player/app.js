(function () {
  "use strict";

  window.__carstreamClientLoaded = true;

  function byId(id) { return document.getElementById(id); }

  function storageGet(key) {
    try { return window.localStorage.getItem(key) || ""; }
    catch (ignored) { return ""; }
  }

  function storageSet(key, value) {
    try { window.localStorage.setItem(key, value); }
    catch (ignored) { }
  }

  function storageRemove(key) {
    try { window.localStorage.removeItem(key); }
    catch (ignored) { }
  }

  function newClientId() {
    try {
      if (window.crypto && typeof window.crypto.randomUUID === "function") return window.crypto.randomUUID();
    } catch (ignored) { }
    return "tablet-" + Date.now() + "-" + Math.random().toString(16).substring(2);
  }

  function finiteNumber(value) { return typeof value === "number" && isFinite(value); }

  var pairingCode = storageGet("carstreamPairingCode");
  var scannedCode = /^#code=([0-9]{4})$/.exec(window.location.hash);
  if (scannedCode) {
    pairingCode = scannedCode[1];
    storageSet("carstreamPairingCode", pairingCode);
    try { window.history.replaceState(null, "", window.location.pathname + window.location.search); } catch (ignored) { }
  }
  var clientId = storageGet("carstreamClientId") || newClientId();
  storageSet("carstreamClientId", clientId);

  var video = byId("video");
  var videoStage = byId("videoStage");
  var videoOverlay = byId("videoOverlay");
  var seekSlider = byId("seekSlider");
  var clientName = storageGet("carstreamClientName");
  var library = [];
  var libraryFolderTorrentId = null;
  var libraryFolderPath = "";
  var libraryFolderSourceName = "";
  var folderHistory = [];
  var currentMedia = null;
  var controlMode = "FREE";
  var commandVersion = 0;
  var buffering = false;
  var needsGesture = false;
  var polling = false;
  var pollTimer = null;
  var started = false;
  var lastConnectionError = "";
  var overlayTimer = null;
  var userSeeking = false;
  var autoNext = storageGet("carstreamAutoNext") !== "off";
  var lastResumeSaveAt = 0;
  var toastTimer = null;
  var appFullscreenTipSeen = storageGet("carstreamBigViewTipSeen") === "yes";

  // Browser-compatible playback uses TorBox browser HLS when the account supports it.
  // The phone relays the HLS privately to Chrome without shipping a native media engine.
  var hlsPlayer = null;
  var playbackSessionId = "";
  var playbackOffset = 0;
  var pendingCompatibilitySeek = 0;
  var sourceDuration = 0;
  var playbackGeneration = 0;
  var playbackPollTimer = null;
  var playbackMetadataTimer = null;
  var playbackMode = "none";
  var preparingPlayback = false;
  var desiredAutoplay = false;
  var hlsRecoveryAttempts = 0;
  var browserDiagnostic = "";
  var blackScreenTimer = null;

  // Skip markers can come from the file's chapters, a community timestamp lookup,
  // or markers saved manually on the phone. The browser never contacts those
  // services directly. It asks only the CarStream host.
  var skipSegments = [];
  var skipSettings = { intro: "BUTTON", recap: "BUTTON", credits: "BUTTON", countdownSeconds: 5 };
  var markerNextMedia = null;
  var activeSkipSegment = null;
  var skipCountdownTimer = null;
  var skipCountdownRemaining = 0;
  var skipCountdownKey = "";
  var skipDismissedKey = "";
  var skipHandled = {};
  var skipRequestGeneration = 0;

  function initialize() {
    if (typeof window.fetch !== "function" || typeof window.Promise !== "function") {
      showFatal("This browser is too old for CarStream. Open the page in a current version of Chrome, Edge, or Safari.");
      return;
    }

    setConnection("waiting", "Enter the four-digit code shown on the phone.");
    byId("nameInput").value = clientName || "";
    byId("codeInput").value = pairingCode || "";
    updateAutoNextButton();
    updatePlayerText();

    if (clientName && /^[0-9]{4}$/.test(pairingCode)) beginClient(clientName);
    else {
      byId("setup").classList.remove("hidden");
      try { (clientName ? byId("codeInput") : byId("nameInput")).focus(); } catch (ignored) { }
    }
  }

  function beginClient(name) {
    if (started) return;
    started = true;
    clientName = name;
    storageSet("carstreamClientName", clientName);
    showApp();
    setConnection("connecting", "Registering this screen with the phone...");
    loadLibrary();
    schedulePoll(0);
  }

  function startFromButton() {
    var input = byId("nameInput");
    var codeInput = byId("codeInput");
    var value = input && input.value ? input.value.trim() : "";
    var codeValue = codeInput && codeInput.value ? codeInput.value.replace(/[^0-9]/g, "") : "";
    if (!value) value = "Screen " + clientId.slice(-4);
    if (!/^[0-9]{4}$/.test(codeValue)) {
      setConnection("offline", "Enter the four-digit code shown on the phone.");
      if (codeInput) codeInput.focus();
      return;
    }
    pairingCode = codeValue;
    storageSet("carstreamPairingCode", pairingCode);
    requestAppFullscreen(false);
    var button = byId("setupButton");
    if (button) { button.disabled = true; button.textContent = "Connecting..."; }
    beginClient(value);
  }

  byId("setupButton").addEventListener("click", startFromButton);
  byId("nameInput").addEventListener("keydown", function (event) {
    if (event.key === "Enter" || event.keyCode === 13) byId("codeInput").focus();
  });
  byId("codeInput").addEventListener("input", function () {
    this.value = this.value.replace(/[^0-9]/g, "").substring(0, 4);
  });
  byId("codeInput").addEventListener("keydown", function (event) {
    if (event.key === "Enter" || event.keyCode === 13) startFromButton();
  });

  byId("renameButton").addEventListener("click", function () {
    var next = window.prompt("Screen name", clientName);
    if (!next || !next.trim()) return;
    clientName = next.trim();
    storageSet("carstreamClientName", clientName);
    byId("tabletName").textContent = "• " + clientName;
    schedulePoll(0);
  });

  byId("appFullscreenButton").addEventListener("click", function () {
    toggleAppFullscreen();
  });

  byId("playerOptions").addEventListener("toggle", function () { showOverlay(this.open); });
  byId("watchLibraryButton").addEventListener("click", showEpisodeList);
  byId("returnVideoButton").addEventListener("click", function () {
    if (!currentMedia) return;
    showWatchView();
    desiredAutoplay = true;
    if (!preparingPlayback) attemptPlay();
  });
  byId("startOverButton").addEventListener("click", function () {
    if (currentMedia && controlMode !== "LOCKED") setMedia(currentMedia, 0, true);
  });
  byId("playFolderButton").addEventListener("click", function () {
    if (controlMode !== "FREE") return;
    var items = folderSnapshot(libraryFolderTorrentId, libraryFolderPath).direct;
    for (var i = 0; i < items.length; i++) {
      if (library[items[i]].ready) { playChosen(library[items[i]]); return; }
    }
  });

  function showWatchView() {
    document.body.classList.add("watching");
    byId("returnVideoButton").classList.add("hidden");
    byId("playerOptions").open = false;
  }

  function showEpisodeList() {
    if (controlMode !== "FREE") return;
    desiredAutoplay = false;
    video.pause();
    if (currentMedia) {
      libraryFolderTorrentId = currentMedia.torrentId;
      libraryFolderSourceName = sourceName(currentMedia);
      libraryFolderPath = itemFolder(currentMedia);
      folderHistory = [];
      byId("search").value = "";
    }
    document.body.classList.remove("watching");
    byId("returnVideoButton").classList.toggle("hidden", !currentMedia);
    if (isFullscreen()) {
      try { var exiting = document.exitFullscreen(); if (exiting && exiting.catch) exiting.catch(function () {}); } catch (ignored) { }
    }
    renderLibrary();
  }

  function playChosen(item) {
    requestAppFullscreen(false);
    var saved = loadResume(item.id);
    var startAt = saved.position >= 15 && (!saved.duration || saved.position < saved.duration * 0.93) ? saved.position : 0;
    setMedia(item, startAt, true);
    if (startAt) showToast("Continuing from " + formatTime(startAt) + ". More → Start over restarts it.");
  }

  byId("search").addEventListener("input", renderLibrary);
  byId("folderRootButton").addEventListener("click", goLibraryRoot);
  byId("folderUpButton").addEventListener("click", goLibraryUp);
  byId("skipFoldersButton").addEventListener("click", function () {
    var skipped = skipWrapperFolders();
    renderLibrary();
    if (skipped > 0) showToast("Skipped " + skipped + " wrapper folder" + (skipped === 1 ? "" : "s") + ".");
    else showToast("This is already the first useful folder level.");
  });

  byId("overlayPlayButton").addEventListener("click", function (event) {
    event.stopPropagation();
    if (video.paused) attemptPlay(); else video.pause();
    showOverlay();
  });
  byId("overlayBackButton").addEventListener("click", function (event) {
    event.stopPropagation(); seekRelative(-10); showOverlay();
  });
  byId("overlayForward10Button").addEventListener("click", function (event) {
    event.stopPropagation(); seekRelative(10); showOverlay();
  });
  byId("overlayForward30Button").addEventListener("click", function (event) {
    event.stopPropagation(); seekRelative(30); showOverlay();
  });
  byId("overlayNextButton").addEventListener("click", function (event) {
    event.stopPropagation(); playNextEpisode(false); showOverlay();
  });
  byId("autoNextButton").addEventListener("click", function (event) {
    event.stopPropagation();
    autoNext = !autoNext;
    storageSet("carstreamAutoNext", autoNext ? "on" : "off");
    updateAutoNextButton();
    showToast(autoNext ? "Automatic next episode is on." : "Automatic next episode is off.");
    showOverlay();
  });
  byId("overlayFullscreenButton").addEventListener("click", function (event) {
    event.stopPropagation(); toggleFullscreen();
  });
  byId("gestureButton").addEventListener("click", function (event) {
    event.stopPropagation(); attemptPlay();
  });
  byId("skipSegmentButton").addEventListener("click", function (event) {
    event.stopPropagation();
    if (activeSkipSegment) performSkip(activeSkipSegment);
  });
  byId("keepWatchingButton").addEventListener("click", function (event) {
    event.stopPropagation();
    if (activeSkipSegment) {
      skipDismissedKey = skipSegmentKey(activeSkipSegment);
      clearSkipCountdown();
      hideSkipPrompt();
      showToast("Keeping " + skipDisplayName(activeSkipSegment.type).toLowerCase() + ".");
    }
  });
  byId("retryButton").addEventListener("click", function () {
    setConnection("connecting", "Retrying..."); loadLibrary(); schedulePoll(0);
  });
  byId("rawFallbackButton").addEventListener("click", function () {
    if (currentMedia) startOriginalPlayback(currentMedia, absolutePosition(), true);
  });

  seekSlider.addEventListener("input", function (event) {
    event.stopPropagation();
    userSeeking = true;
    var duration = absoluteDuration();
    if (duration > 0) {
      var preview = duration * Number(seekSlider.value) / 1000;
      byId("overlayTimeText").textContent = formatTime(preview) + " / " + formatTime(duration);
    }
    showOverlay(true);
  });
  seekSlider.addEventListener("change", function (event) {
    event.stopPropagation();
    var duration = absoluteDuration();
    if (duration > 0) seekTo(duration * Number(seekSlider.value) / 1000);
    userSeeking = false;
    showOverlay();
  });

  videoStage.addEventListener("click", function (event) {
    if (event.target === video || event.target === videoStage || event.target === videoOverlay) toggleOverlay();
  });
  videoStage.addEventListener("mousemove", function () { showOverlay(); });
  videoStage.addEventListener("touchstart", function () { showOverlay(); }, { passive: true });
  videoStage.addEventListener("dblclick", function (event) {
    if (event.target !== video && event.target !== videoStage) return;
    var rect = videoStage.getBoundingClientRect();
    var position = event.clientX - rect.left;
    if (position < rect.width * 0.42) seekRelative(-10);
    else if (position > rect.width * 0.58) seekRelative(10);
    else toggleFullscreen();
    showOverlay();
  });

  document.addEventListener("fullscreenchange", fullscreenChanged);
  document.addEventListener("webkitfullscreenchange", fullscreenChanged);
  document.addEventListener("keydown", function (event) {
    if (!started || isTypingTarget(event.target)) return;
    var key = String(event.key || "").toLowerCase();
    if (key === " " || key === "k") { event.preventDefault(); if (video.paused) attemptPlay(); else video.pause(); }
    else if (key === "arrowleft") { event.preventDefault(); seekRelative(-10); }
    else if (key === "arrowright") { event.preventDefault(); seekRelative(10); }
    else if (key === "j") { event.preventDefault(); seekRelative(-10); }
    else if (key === "l") { event.preventDefault(); seekRelative(10); }
    else if (key === "n") { event.preventDefault(); playNextEpisode(false); }
    else if (key === "f") { event.preventDefault(); toggleFullscreen(); }
    else if (key === "s" && activeSkipSegment) { event.preventDefault(); performSkip(activeSkipSegment); }
    showOverlay();
  });

  function isTypingTarget(target) {
    if (!target || !target.tagName) return false;
    var tag = String(target.tagName).toLowerCase();
    return tag === "input" || tag === "textarea" || tag === "select";
  }

  function showApp() {
    byId("setup").classList.add("hidden");
    byId("fatal").classList.add("hidden");
    byId("viewerShell").classList.remove("hidden");
    document.body.classList.add("viewer-active");
    byId("tabletName").textContent = "• " + clientName;
    applyControlMode(controlMode);
    updateEmptyPlayerState();
    showOverlay(true);
    if (!appFullscreenTipSeen && !isAppFullscreen()) {
      appFullscreenTipSeen = true;
      storageSet("carstreamBigViewTipSeen", "yes");
      window.setTimeout(function () { showToast("Tip: tap Big view to hide Chrome's bars and make the buttons much larger."); }, 900);
    }
  }

  function showFatal(message) {
    byId("setup").classList.add("hidden");
    byId("viewerShell").classList.add("hidden");
    byId("fatal").textContent = message;
    byId("fatal").classList.remove("hidden");
    setConnection("offline", message);
  }

  function authHeaders(json) {
    var headers = { "X-CarStream-Code": pairingCode };
    if (json) headers["Content-Type"] = "application/json";
    return headers;
  }

  function loadLibrary() {
    byId("libraryMessage").textContent = "Loading library...";
    fetch("/api/library", { cache: "no-store", headers: authHeaders() })
      .then(function (response) {
        if (response.status === 401) throw new Error("The pairing code does not match the phone");
        if (!response.ok) throw new Error("HTTP " + response.status);
        return response.json();
      })
      .then(function (result) {
        library = result && Array.isArray(result.items) ? result.items : [];
        byId("libraryMessage").textContent = library.length ? "" : "No playable TorBox videos are listed. Refresh the library on the phone.";
        validateLibraryFolder();
        renderLibrary();
        updateNextButton();
      })
      .catch(function (error) {
        byId("libraryMessage").textContent = "Could not load library: " + errorMessage(error);
      });
  }

  function clearChildren(element) { while (element.firstChild) element.removeChild(element.firstChild); }

  function renderLibrary() {
    var container = byId("library");
    clearChildren(container);
    validateLibraryFolder();

    var insideFolder = libraryFolderTorrentId !== null;
    byId("folderRootButton").disabled = !insideFolder;
    byId("folderUpButton").disabled = !insideFolder;
    byId("skipFoldersButton").disabled = !insideFolder;
    byId("folderRootButton").classList.toggle("hidden", !insideFolder);
    byId("folderUpButton").classList.toggle("hidden", !insideFolder);
    byId("skipFoldersButton").classList.toggle("hidden", !insideFolder);

    var needle = (byId("search").value || "").trim().toLowerCase();
    var direct = insideFolder ? folderSnapshot(libraryFolderTorrentId, libraryFolderPath).direct : [];
    var playable = direct.some(function (index) { return library[index].ready; });
    byId("playFolderButton").classList.toggle("hidden", !!needle || !playable);
    byId("playFolderButton").disabled = controlMode !== "FREE";
    byId("libraryTitle").textContent = needle ? "Search results" : insideFolder ? "Pick an episode" : "Pick a show";
    var location = insideFolder
      ? friendlyFolderLocation(libraryFolderSourceName, libraryFolderPath)
      : "All shows and movies";
    byId("folderPath").textContent = needle ? "Search all folders • " + location : location;

    if (!library.length) {
      byId("libraryMessage").textContent = "No playable TorBox videos are listed. Refresh the library on the phone.";
      return;
    }
    if (needle) renderLibrarySearch(container, needle);
    else if (!insideFolder) renderLibraryRoot(container);
    else renderLibraryFolder(container);
  }

  function renderLibraryRoot(container) {
    container.className = "library folder-view library-root";

    var continuing = [];
    for (var c = 0; c < library.length; c++) {
      var saved = loadResume(library[c].id);
      if (saved.position >= 15 && (!saved.duration || saved.position < saved.duration * 0.93)) {
        continuing.push({ index: c, updated: saved.updated || 0 });
      }
    }
    continuing.sort(function (left, right) { return right.updated - left.updated; });
    if (continuing.length) {
      container.appendChild(createSectionTitle("Continue watching", "Tap to resume"));
      for (var ci = 0; ci < Math.min(1, continuing.length); ci++) {
        var continueCard = createMediaCard(library[continuing[ci].index], continuing[ci].index, true);
        continueCard.classList.add("featured-card");
        container.appendChild(continueCard);
      }
      container.appendChild(createSectionTitle("All shows and movies", "Open a folder"));
    }

    var groups = {};
    var order = [];
    for (var i = 0; i < library.length; i++) {
      var item = library[i];
      var name = sourceName(item);
      var key = "g:" + String(item.torrentId) + "\n" + name;
      if (!groups[key]) {
        groups[key] = { torrentId: String(item.torrentId), name: name, count: 0, ready: 0, size: 0 };
        order.push(groups[key]);
      }
      groups[key].count++;
      if (item.ready) groups[key].ready++;
      groups[key].size += Number(item.size) || 0;
    }
    order.sort(function (left, right) {
      var a = /elena[ ._-]+of[ ._-]+avalor/i.test(left.name) ? 0 : 1;
      var b = /elena[ ._-]+of[ ._-]+avalor/i.test(right.name) ? 0 : 1;
      return a - b || naturalCompare(left.name, right.name);
    });

    for (var j = 0; j < order.length; j++) {
      (function (group) {
        container.appendChild(createFolderCard("TORBOX DOWNLOAD", friendlyFolderName(group.name),
          group.count + " video" + (group.count === 1 ? "" : "s") + (group.ready < group.count ? " • " + group.ready + " ready" : ""),
          function () { navigateToFolder(group.torrentId, group.name, "", true); }, group.name));
      })(order[j]);
    }
    byId("libraryMessage").textContent = order.length + " show or movie folder" + (order.length === 1 ? "" : "s")
      + " • " + library.length + " playable file" + (library.length === 1 ? "" : "s");
  }

  function renderLibraryFolder(container) {
    var snapshot = folderSnapshot(libraryFolderTorrentId, libraryFolderPath);
    container.className = "library " + (snapshot.folders.length ? "folder-view" : "episode-view");
    for (var f = 0; f < snapshot.folders.length; f++) {
      (function (folder) {
        container.appendChild(createFolderCard("FOLDER", friendlyFolderName(folder.name),
          folder.count + " episode" + (folder.count === 1 ? "" : "s"),
          function () { navigateToFolder(libraryFolderTorrentId, libraryFolderSourceName, joinFolder(libraryFolderPath, folder.name), true); }));
      })(snapshot.folders[f]);
    }
    for (var d = 0; d < snapshot.direct.length; d++) {
      var index = snapshot.direct[d];
      container.appendChild(createMediaCard(library[index], index, false));
    }

    if (!snapshot.folders.length && !snapshot.direct.length) {
      byId("libraryMessage").textContent = "This folder does not contain playable video files.";
      return;
    }
    var message = snapshot.folders.length + " folder" + (snapshot.folders.length === 1 ? "" : "s")
      + " • " + snapshot.direct.length + " file" + (snapshot.direct.length === 1 ? "" : "s") + " here";
    if (snapshot.descendantCount > snapshot.direct.length) message += " • " + snapshot.descendantCount + " videos below";
    byId("libraryMessage").textContent = message;
  }

  function renderLibrarySearch(container, needle) {
    container.className = "library search-view";
    var matches = [];
    for (var i = 0; i < library.length; i++) {
      var item = library[i];
      var searchable = String(item.title || "") + " " + String(item.path || "")
        + " " + sourceName(item) + " " + relativePath(item) + " " + itemLocation(item);
      if (searchable.toLowerCase().indexOf(needle) >= 0) matches.push(i);
    }
    matches.sort(function (left, right) {
      var a = itemLocation(library[left]) + "\n" + String(library[left].title || "");
      var b = itemLocation(library[right]) + "\n" + String(library[right].title || "");
      return naturalCompare(a, b);
    });

    var maximum = 120;
    var shown = Math.min(matches.length, maximum);
    for (var j = 0; j < shown; j++) container.appendChild(createMediaCard(library[matches[j]], matches[j], true));
    if (!matches.length) {
      byId("libraryMessage").textContent = "No files or folders match that search.";
      return;
    }
    var message = "Showing " + shown + " of " + matches.length + " matching file" + (matches.length === 1 ? "" : "s") + " across all folders";
    if (matches.length > maximum) message += ". Narrow the search to see more.";
    byId("libraryMessage").textContent = message;
  }

  function createSectionTitle(titleText, detailText) {
    var title = document.createElement("div");
    title.className = "library-section-title";
    var strong = document.createElement("strong");
    strong.textContent = titleText || "Library";
    var detail = document.createElement("span");
    detail.textContent = detailText || "";
    title.appendChild(strong);
    title.appendChild(detail);
    return title;
  }

  function createFolderCard(kind, name, metaText, action, fullName) {
    var button = document.createElement("button");
    button.className = "folder-card";
    button.disabled = controlMode !== "FREE";
    button.title = fullName || name || "Open folder";

    var icon = document.createElement("span");
    icon.className = "folder-icon";
    icon.textContent = "▰";
    icon.setAttribute("aria-hidden", "true");

    var label = document.createElement("span");
    label.className = "folder-kind";
    label.textContent = kind === "TORBOX DOWNLOAD" ? "SHOW OR MOVIE" : "FOLDER";

    var title = document.createElement("strong");
    title.className = "media-title";
    title.textContent = name || "Folder";

    var meta = document.createElement("span");
    meta.className = "media-meta";
    meta.textContent = metaText || "Open folder";

    var chevron = document.createElement("span");
    chevron.className = "folder-chevron";
    chevron.textContent = "›";
    chevron.setAttribute("aria-hidden", "true");

    button.appendChild(icon);
    button.appendChild(label);
    button.appendChild(title);
    button.appendChild(meta);
    button.appendChild(chevron);
    button.addEventListener("click", function (event) {
      requestAppFullscreen(false);
      action(event);
    });
    return button;
  }

  function createMediaCard(item, index, showLocation) {
    var button = document.createElement("button");
    button.className = "media-card";
    if (currentMedia && String(currentMedia.id) === String(item.id)) button.classList.add("current-card");
    button.disabled = controlMode !== "FREE" || !item.ready;
    button.setAttribute("data-media-index", String(index));
    button.title = cleanMediaName(item && (item.title || item.path) || "Video");

    var identity = episodeCardIdentity(item);
    var badge = document.createElement("span");
    badge.className = "episode-badge";
    badge.textContent = identity.badge;

    var title = document.createElement("strong");
    title.className = "media-title";
    title.textContent = identity.title;

    var meta = document.createElement("span");
    meta.className = "media-meta";
    var value = item.ready ? "▶ Play" : "Getting ready";
    var resume = loadResume(item.id);
    if (resume.position >= 15 && (!resume.duration || resume.position < resume.duration * 0.93)) {
      value = "Continue " + formatTime(resume.position);
      button.classList.add("resume-card");
    }
    if (showLocation) value += " • " + itemLocation(item);
    meta.textContent = value;

    button.appendChild(badge);
    button.appendChild(title);
    button.appendChild(meta);
    if (resume.position >= 15 && resume.duration > 0 && resume.position < resume.duration * 0.93) {
      var bar = document.createElement("span");
      bar.className = "resume-bar";
      var fill = document.createElement("span");
      fill.style.width = String(Math.max(2, Math.min(98, Math.round(resume.position * 100 / resume.duration)))) + "%";
      bar.appendChild(fill);
      button.appendChild(bar);
    }
    button.addEventListener("click", function () {
      requestAppFullscreen(false);
      var selected = parseInt(this.getAttribute("data-media-index"), 10);
      if (isNaN(selected) || !library[selected]) return;
      playChosen(library[selected]);
    });
    return button;
  }

  function episodeCardIdentity(item) {
    var raw = cleanMediaName(item && (item.title || item.path) || "Untitled video");
    var season = 0;
    var episode = 0;
    var token = null;
    var patterns = [
      /(?:^|[^a-z0-9])s(\d{1,3})[ ._-]*e(\d{1,4})(?:[^a-z0-9]|$)/i,
      /(?:^|[^0-9])(\d{1,3})x(\d{1,4})(?:[^0-9]|$)/i,
      /season[ ._-]*(\d{1,3})[ ._-]*(?:episode|ep|e)[ ._-]*(\d{1,4})/i
    ];
    for (var i = 0; i < patterns.length; i++) {
      var match = patterns[i].exec(raw);
      if (match) { season = Number(match[1]) || 0; episode = Number(match[2]) || 0; token = match; break; }
    }
    if (!token) {
      var episodeOnly = /(?:^|[^a-z0-9])(?:episode|ep|e)[ ._-]*(\d{1,4})(?:[^a-z0-9]|$)/i.exec(raw);
      if (episodeOnly) { episode = Number(episodeOnly[1]) || 0; token = episodeOnly; }
    }

    var badge = "MOVIE";
    if (season > 0 && episode > 0) badge = "S" + pad2(season) + "\nE" + pad2(episode);
    else if (episode > 0) badge = "EP\n" + pad2(episode);

    var title = raw;
    if (token) {
      var after = raw.substring(token.index + token[0].length).replace(/^[ ._\-–—:]+/, "");
      if (after.length >= 2) title = after;
      else title = season > 0 ? "Episode " + episode : "Episode " + episode;
    }
    title = stripReleaseTags(title);
    if (!title || title.length < 2) title = raw || "Untitled video";
    return { badge: badge, title: title };
  }

  function friendlyMediaTitle(item) { return episodeCardIdentity(item).title; }

  function friendlyFolderName(value) {
    var raw = String(value || "Folder").replace(/[._]+/g, " ").replace(/\s+/g, " ").trim();
    var cleaned = stripReleaseTags(raw)
      .replace(/\b[a-f0-9]{24,}\b/ig, " ")
      .replace(/\b(?:completed|complete|downloaded)\b/ig, " ")
      .replace(/\s+/g, " ")
      .replace(/^[ ._\-–—:]+|[ ._\-–—:]+$/g, "")
      .trim();
    return cleaned.length >= 2 ? cleaned : raw;
  }

  function cleanMediaName(value) {
    var name = String(value || "Untitled video");
    var slash = Math.max(name.lastIndexOf("/"), name.lastIndexOf("\\"));
    if (slash >= 0) name = name.substring(slash + 1);
    name = name.replace(/\.(mkv|mp4|m4v|webm|mov|avi|ts|m2ts)$/i, "");
    name = name.replace(/[._]+/g, " ").replace(/\s+/g, " ").trim();
    return name || "Untitled video";
  }

  function stripReleaseTags(value) {
    var text = String(value || "");
    text = text.replace(/[\[\(](?:[^\]\)]*(?:2160p|1080p|720p|480p|web[- .]?dl|webrip|blu[- .]?ray|brrip|hdtv|x264|x265|h\.?264|h\.?265|hevc|aac|dts|ddp?5|proper|repack)[^\]\)]*)[\]\)]/ig, " ");
    text = text.replace(/\b(?:2160p|1080p|720p|480p|web[- .]?dl|webrip|blu[- .]?ray|brrip|hdtv|x264|x265|h\.?264|h\.?265|hevc|aac|dts|ddp?5(?:\.1)?|atmos|proper|repack|remux|10bit)\b/ig, " ");
    return text.replace(/[._]+/g, " ").replace(/\s+/g, " ").replace(/^[ ._\-–—:]+|[ ._\-–—:]+$/g, "").trim();
  }

  function folderSnapshot(torrentId, folderPath) {
    var folders = {};
    var folderNames = [];
    var direct = [];
    var descendantCount = 0;
    var prefix = normalizePath(folderPath);
    var prefixWithSlash = prefix ? prefix + "/" : "";

    for (var i = 0; i < library.length; i++) {
      var item = library[i];
      if (String(item.torrentId) !== String(torrentId)) continue;
      var relative = relativePath(item);
      var remainder;
      if (!prefix) remainder = relative;
      else if (relative.indexOf(prefixWithSlash) === 0) remainder = relative.substring(prefixWithSlash.length);
      else continue;
      if (!remainder) continue;
      descendantCount++;
      var slash = remainder.indexOf("/");
      if (slash >= 0) {
        var child = remainder.substring(0, slash).trim();
        if (!child) continue;
        if (!folders[child]) {
          folders[child] = { name: child, count: 0, ready: 0, size: 0 };
          folderNames.push(child);
        }
        folders[child].count++;
        if (item.ready) folders[child].ready++;
        folders[child].size += Number(item.size) || 0;
      } else direct.push(i);
    }

    folderNames.sort(naturalCompare);
    direct.sort(function (left, right) { return naturalCompare(String(library[left].title || ""), String(library[right].title || "")); });
    var folderList = [];
    for (var f = 0; f < folderNames.length; f++) folderList.push(folders[folderNames[f]]);
    return { folders: folderList, direct: direct, descendantCount: descendantCount };
  }

  function skipWrapperFolders() {
    if (libraryFolderTorrentId === null) return 0;
    var skipped = 0;
    var guard = 0;
    while (guard++ < 24) {
      var snapshot = folderSnapshot(libraryFolderTorrentId, libraryFolderPath);
      if (snapshot.direct.length > 0 || snapshot.folders.length !== 1) break;
      libraryFolderPath = joinFolder(libraryFolderPath, snapshot.folders[0].name);
      skipped++;
    }
    return skipped;
  }

  function navigateToFolder(torrentId, source, path, autoSkip) {
    folderHistory.push({ torrentId: libraryFolderTorrentId, path: libraryFolderPath, source: libraryFolderSourceName });
    libraryFolderTorrentId = torrentId;
    libraryFolderSourceName = source || "";
    libraryFolderPath = normalizePath(path);
    byId("search").value = "";
    var skipped = autoSkip ? skipWrapperFolders() : 0;
    renderLibrary();
  }

  function goLibraryRoot() {
    folderHistory = [];
    libraryFolderTorrentId = null;
    libraryFolderPath = "";
    libraryFolderSourceName = "";
    byId("search").value = "";
    renderLibrary();
  }

  function goLibraryUp() {
    if (libraryFolderTorrentId === null) return;
    byId("search").value = "";
    if (folderHistory.length) {
      var previous = folderHistory.pop();
      libraryFolderTorrentId = previous.torrentId;
      libraryFolderPath = previous.path;
      libraryFolderSourceName = previous.source;
    } else if (libraryFolderPath) {
      libraryFolderPath = parentFolder(libraryFolderPath);
    } else {
      libraryFolderTorrentId = null;
      libraryFolderSourceName = "";
    }
    renderLibrary();
  }

  function validateLibraryFolder() {
    if (libraryFolderTorrentId === null) return;
    var found = false;
    for (var i = 0; i < library.length; i++) {
      if (String(library[i].torrentId) === String(libraryFolderTorrentId)) {
        found = true;
        libraryFolderSourceName = sourceName(library[i]);
        break;
      }
    }
    if (!found) {
      libraryFolderTorrentId = null;
      libraryFolderPath = "";
      libraryFolderSourceName = "";
      folderHistory = [];
      return;
    }
    while (libraryFolderPath && !folderHasItems(libraryFolderTorrentId, libraryFolderPath)) libraryFolderPath = parentFolder(libraryFolderPath);
  }

  function folderHasItems(torrentId, folder) {
    var clean = normalizePath(folder);
    var prefix = clean ? clean + "/" : "";
    for (var i = 0; i < library.length; i++) {
      if (String(library[i].torrentId) !== String(torrentId)) continue;
      var relative = relativePath(library[i]);
      if (!clean || relative.indexOf(prefix) === 0) return true;
    }
    return false;
  }

  function sourceName(item) {
    var value = String(item && item.sourceName || "").trim();
    if (!value) value = "TorBox download " + String(item && item.torrentId != null ? item.torrentId : "");
    return value.replace(/[\\/]/g, " ∕ ").trim();
  }

  function relativePath(item) {
    var provided = normalizePath(item && item.relativePath || "");
    if (provided) return provided;
    var value = normalizePath(item && item.path || item && item.title || "");
    var slash = value.indexOf("/");
    if (slash > 0) {
      var first = value.substring(0, slash).toLowerCase();
      var rawSource = normalizePath(item && item.sourceName || "");
      var sourceLeaf = rawSource.substring(rawSource.lastIndexOf("/") + 1).toLowerCase();
      if (sourceLeaf && first === sourceLeaf) value = value.substring(slash + 1);
    }
    return value;
  }

  function itemFolder(item) {
    var relative = relativePath(item);
    var slash = relative.lastIndexOf("/");
    return slash < 0 ? "" : relative.substring(0, slash);
  }

  function itemLocation(item) {
    var folder = itemFolder(item);
    return friendlyFolderLocation(sourceName(item), folder);
  }

  function friendlyFolderLocation(source, path) {
    var sourceLabel = friendlyFolderName(source || "Library");
    var rawParts = normalizePath(path).split("/");
    var parts = [];
    for (var i = 0; i < rawParts.length; i++) {
      var part = rawParts[i].trim();
      if (!part || isWrapperFolderName(part)) continue;
      var cleaned = friendlyFolderName(part);
      if (!cleaned) continue;
      if (cleaned.toLowerCase() === sourceLabel.toLowerCase()) continue;
      parts.push(cleaned);
    }
    if (parts.length > 3) parts = parts.slice(parts.length - 3);
    return parts.length ? parts.join(" › ") : sourceLabel;
  }

  function isWrapperFolderName(value) {
    var clean = String(value || "").trim();
    if (!clean) return true;
    if (/^[a-f0-9_-]{20,}$/i.test(clean)) return true;
    return /^(?:complete|completed|download|downloaded|files?|media|video|videos|content)$/i.test(clean);
  }

  function normalizePath(value) {
    var result = String(value || "").trim().replace(/\\/g, "/");
    return result.replace(/^\/+|\/+$/g, "").replace(/\/{2,}/g, "/");
  }

  function joinFolder(parent, child) {
    var left = normalizePath(parent);
    var right = normalizePath(child);
    return left ? (right ? left + "/" + right : left) : right;
  }

  function parentFolder(path) {
    var clean = normalizePath(path);
    var slash = clean.lastIndexOf("/");
    return slash < 0 ? "" : clean.substring(0, slash);
  }

  function naturalCompare(left, right) {
    var a = String(left || "").toLowerCase().match(/(\d+|\D+)/g) || [""];
    var b = String(right || "").toLowerCase().match(/(\d+|\D+)/g) || [""];
    var length = Math.min(a.length, b.length);
    for (var i = 0; i < length; i++) {
      if (a[i] === b[i]) continue;
      var an = /^\d+$/.test(a[i]) ? parseInt(a[i], 10) : NaN;
      var bn = /^\d+$/.test(b[i]) ? parseInt(b[i], 10) : NaN;
      if (!isNaN(an) && !isNaN(bn) && an !== bn) return an - bn;
      return a[i] < b[i] ? -1 : 1;
    }
    return a.length - b.length;
  }

  function nextInFolder(item) {
    if (!item) return null;
    var folder = itemFolder(item);
    var siblings = [];
    for (var i = 0; i < library.length; i++) {
      var candidate = library[i];
      if (candidate.ready && String(candidate.torrentId) === String(item.torrentId) && itemFolder(candidate) === folder) siblings.push(candidate);
    }
    siblings.sort(function (a, b) { return naturalCompare(relativePath(a), relativePath(b)); });
    for (var j = 0; j < siblings.length; j++) {
      if (String(siblings[j].id) === String(item.id)) return j + 1 < siblings.length ? siblings[j + 1] : null;
    }
    return null;
  }

  function playNextEpisode(fromAuto) {
    var next = nextInFolder(currentMedia);
    if (!next) {
      if (!fromAuto) showToast("That was the last ready video in this folder.");
      updateNextButton();
      return false;
    }
    setMedia(next, 0, true);
    showToast("Next: " + friendlyMediaTitle(next));
    return true;
  }

  function updateNextButton() {
    var next = nextInFolder(currentMedia);
    byId("overlayNextButton").disabled = !next || controlMode === "LOCKED";
    byId("overlayNextButton").title = next ? "Play next: " + friendlyMediaTitle(next) : "No next ready video in this folder";
    if (!currentMedia && playbackMode === "none") setPlayerStatus("", "Ready", "Pick a video below.");
  }

  function schedulePoll(delay) {
    if (pollTimer) window.clearTimeout(pollTimer);
    pollTimer = window.setTimeout(pollState, delay);
  }

  function pollState() {
    if (polling || !clientName) { schedulePoll(1000); return; }
    polling = true;
    fetch("/api/state", {
      method: "POST",
      cache: "no-store",
      headers: authHeaders(true),
      body: JSON.stringify(telemetry())
    })
      .then(function (response) {
        if (response.status === 401) throw new Error("The pairing code does not match the phone");
        if (!response.ok) throw new Error("HTTP " + response.status);
        return response.json();
      })
      .then(function (result) {
        lastConnectionError = "";
        setConnection("online", "Connected to the CarStream server on the phone.");
        if (result.controlMode) applyControlMode(result.controlMode);
        if (Number(result.commandVersion) > commandVersion) {
          commandVersion = Number(result.commandVersion);
          if (result.command) handleCommand(result.command);
        }
      })
      .catch(function (error) {
        lastConnectionError = errorMessage(error);
        setConnection("offline", "Could not connect: " + lastConnectionError);
        if (lastConnectionError.indexOf("pairing code") >= 0) {
          started = false;
          document.body.classList.remove("viewer-active");
          byId("setup").classList.remove("hidden");
          byId("viewerShell").classList.add("hidden");
          byId("setupButton").disabled = false;
          byId("setupButton").textContent = "Connect";
          byId("codeInput").focus();
        }
      })
      .then(function () {
        polling = false;
        schedulePoll(lastConnectionError ? 1800 : 900);
      });
  }

  function telemetry() {
    return {
      clientId: clientId,
      name: clientName,
      code: pairingCode,
      commandVersion: commandVersion,
      mediaId: currentMedia ? currentMedia.id : "",
      title: currentMedia ? currentMedia.title : "Nothing playing",
      position: absolutePosition(),
      duration: absoluteDuration(),
      paused: video.paused,
      buffering: buffering,
      needsGesture: needsGesture,
      playbackMode: playbackMode,
      browserError: browserDiagnostic,
      videoWidth: Number(video.videoWidth) || 0,
      videoHeight: Number(video.videoHeight) || 0,
      readyState: Number(video.readyState) || 0,
      networkState: Number(video.networkState) || 0
    };
  }

  function handleCommand(command) {
    if (!command) return;
    switch (command.action) {
      case "play": attemptPlay(); break;
      case "pause": video.pause(); break;
      case "stop": stopPlayback(); break;
      case "seekRelative": seekRelative(Number(command.seconds) || 0); break;
      case "seekTo": seekTo(Number(command.seconds) || 0); break;
      case "setMedia": setMedia(command.media, Number(command.startAt) || 0, command.autoplay !== false); break;
      case "controlMode": applyControlMode(command.mode || "FREE"); break;
    }
  }

  function setMedia(item, startAt, autoplay) {
    if (!item || !item.id) return;
    saveResumeNow();
    currentMedia = item;
    showWatchView();
    updateEmptyPlayerState();
    needsGesture = false;
    byId("gestureButton").classList.add("hidden");
    byId("nowTitle").textContent = friendlyMediaTitle(item);
    byId("overlayTitle").textContent = friendlyMediaTitle(item);
    resetSkipState();
    loadSkipMarkers(item);
    beginCompatibilityPlayback(item, Math.max(0, Number(startAt) || 0), autoplay !== false);
    updateNextButton();
    renderLibrary();
    showOverlay(true);
    schedulePoll(0);
  }

  function beginCompatibilityPlayback(item, startAt, autoplay) {
    if (!item || !item.id) return;
    var generation = ++playbackGeneration;
    desiredAutoplay = autoplay !== false;
    preparingPlayback = true;
    playbackMode = "compatibility";
    playbackOffset = Math.max(0, Number(startAt) || 0);
    pendingCompatibilitySeek = 0;
    sourceDuration = 0;
    hlsRecoveryAttempts = 0;
    browserDiagnostic = "";
    clearBlackScreenTimer();
    releasePlaybackSession();
    destroyHlsPlayer();
    clearPlaybackElement();
    setPlayerStatus("preparing", "Preparing", "Getting your episode ready...");
    byId("rawFallbackButton").classList.add("hidden");

    var url = "/api/playback?mediaId=" + encodeURIComponent(item.id)
      + "&startAt=" + encodeURIComponent(String(playbackOffset))
      + "&clientId=" + encodeURIComponent(clientId);
    fetch(url, { cache: "no-store", headers: authHeaders() })
      .then(function (response) {
        if (response.status === 401) throw new Error("The pairing code does not match the phone");
        if (!response.ok) throw new Error("HTTP " + response.status);
        return response.json();
      })
      .then(function (result) { handlePlaybackPreparation(result, generation, item); })
      .catch(function (error) {
        if (generation !== playbackGeneration) return;
        compatibilityFailure("Could not prepare this video: " + errorMessage(error));
      });
  }

  function handlePlaybackPreparation(result, generation, item) {
    if (generation !== playbackGeneration || !currentMedia || String(currentMedia.id) !== String(item.id)) return;
    result = result || {};
    if (result.sessionId) playbackSessionId = String(result.sessionId);
    if (finiteNumber(Number(result.offsetSeconds))) playbackOffset = Math.max(0, Number(result.offsetSeconds));
    if (finiteNumber(Number(result.seekSeconds))) pendingCompatibilitySeek = Math.max(0, Number(result.seekSeconds));
    if (finiteNumber(Number(result.durationSeconds)) && Number(result.durationSeconds) > 0) sourceDuration = Number(result.durationSeconds);
    var state = String(result.state || "FAILED").toUpperCase();
    var mode = String(result.mode || "hls").toLowerCase();
    var detail = result.message || result.error || "Preparing video...";
    if (state === "READY" && mode === "direct") {
      browserDiagnostic = detail;
      showToast(detail);
      startOriginalPlayback(item, pendingCompatibilitySeek || playbackOffset, desiredAutoplay, detail);
      return;
    }
    if (state === "READY" && result.playbackUrl) {
      var quality = result.quality ? " • " + String(result.quality) : "";
      playbackOffset = 0;
      setPlayerStatus("preparing", "Loading", (detail || "TorBox browser stream is ready") + quality);
      attachCompatibilityStream(String(result.playbackUrl), generation);
      return;
    }
    if (state === "PREPARING") {
      setPlayerStatus("preparing", "Preparing", detail);
      if (playbackPollTimer) window.clearTimeout(playbackPollTimer);
      playbackPollTimer = window.setTimeout(function () {
        if (generation === playbackGeneration && currentMedia) beginPlaybackPoll(currentMedia, generation);
      }, 900);
      return;
    }
    if (state === "BUSY") {
      compatibilityFailure(detail || "Two other videos are already being converted for Chrome.");
      return;
    }
    compatibilityFailure(detail || "The phone could not convert this video for Chrome.");
  }

  function refreshPlaybackMetadata(item, generation, attempt) {
    if (generation !== playbackGeneration || !item || playbackMode !== "compatibility") return;
    var url = "/api/playback?mediaId=" + encodeURIComponent(item.id)
      + "&startAt=" + encodeURIComponent(String(playbackOffset))
      + "&clientId=" + encodeURIComponent(clientId);
    fetch(url, { cache: "no-store", headers: authHeaders() })
      .then(function (response) { return response.ok ? response.json() : null; })
      .then(function (result) {
        if (generation !== playbackGeneration || !result) return;
        var duration = Number(result.durationSeconds);
        if (finiteNumber(duration) && duration > 0) {
          sourceDuration = duration;
          updatePlayerText();
          return;
        }
        if (attempt < 25) playbackMetadataTimer = window.setTimeout(function () {
          refreshPlaybackMetadata(item, generation, attempt + 1);
        }, 1500);
      })
      .catch(function () {
        if (generation === playbackGeneration && attempt < 12) playbackMetadataTimer = window.setTimeout(function () {
          refreshPlaybackMetadata(item, generation, attempt + 1);
        }, 2000);
      });
  }

  function beginPlaybackPoll(item, generation) {
    if (generation !== playbackGeneration || !item) return;
    var url = "/api/playback?mediaId=" + encodeURIComponent(item.id)
      + "&startAt=" + encodeURIComponent(String(playbackOffset))
      + "&clientId=" + encodeURIComponent(clientId);
    fetch(url, { cache: "no-store", headers: authHeaders() })
      .then(function (response) {
        if (!response.ok) throw new Error("HTTP " + response.status);
        return response.json();
      })
      .then(function (result) { handlePlaybackPreparation(result, generation, item); })
      .catch(function (error) {
        if (generation !== playbackGeneration) return;
        compatibilityFailure("Video preparation stopped: " + errorMessage(error));
      });
  }

  function attachCompatibilityStream(url, generation) {
    if (generation !== playbackGeneration) return;
    preparingPlayback = false;
    playbackMode = "compatibility";
    setPlayerStatus("preparing", "Loading", "Loading the Chrome-compatible video...");
    byId("rawFallbackButton").classList.remove("hidden");
    destroyHlsPlayer();
    clearPlaybackElement();

    if (window.Hls && typeof window.Hls.isSupported === "function" && window.Hls.isSupported()) {
      try {
        hlsPlayer = new window.Hls({
          enableWorker: true,
          lowLatencyMode: false,
          backBufferLength: 90,
          maxBufferLength: 45,
          maxMaxBufferLength: 90,
          liveSyncDurationCount: 3,
          liveMaxLatencyDurationCount: 10,
          manifestLoadingTimeOut: 20000,
          fragLoadingTimeOut: 30000,
          xhrSetup: function (xhr) {
            try { xhr.setRequestHeader("X-CarStream-Code", pairingCode); } catch (ignored) { }
          }
        });
        hlsPlayer.on(window.Hls.Events.MEDIA_ATTACHED, function () {
          if (generation === playbackGeneration && hlsPlayer) hlsPlayer.loadSource(url);
        });
        hlsPlayer.on(window.Hls.Events.MANIFEST_PARSED, function () {
          if (generation !== playbackGeneration) return;
          applyPendingCompatibilitySeek();
          setPlayerStatus("playing", "Ready", "TorBox browser stream ready.");
          if (desiredAutoplay) attemptPlay();
        });
        hlsPlayer.on(window.Hls.Events.LEVEL_LOADED, function (event, data) {
          if (generation !== playbackGeneration || !data || !data.details) return;
          var total = Number(data.details.totalduration);
          if (data.details.live === false && finiteNumber(total) && total > 0) {
            sourceDuration = Math.max(sourceDuration, playbackOffset + total);
          }
          updatePlayerText();
        });
        hlsPlayer.on(window.Hls.Events.ERROR, function (event, data) { handleHlsError(data, generation); });
        hlsPlayer.attachMedia(video);
        return;
      } catch (error) {
        compatibilityFailure("The Chrome playback helper could not start: " + errorMessage(error));
        return;
      }
    }

    if (video.canPlayType && video.canPlayType("application/vnd.apple.mpegurl")) {
      video.src = url;
      video.load();
      var onReady = function () {
        video.removeEventListener("loadedmetadata", onReady);
        if (generation !== playbackGeneration) return;
        applyPendingCompatibilitySeek();
        setPlayerStatus("playing", "Ready", "TorBox browser stream ready.");
        if (desiredAutoplay) attemptPlay();
      };
      video.addEventListener("loadedmetadata", onReady);
      return;
    }

    var scriptProblem = window.__carstreamHlsLoadError ? ": " + window.__carstreamHlsLoadError : "";
    compatibilityFailure("This browser could not load the HLS playback helper" + scriptProblem);
  }

  function applyPendingCompatibilitySeek() {
    var target = Math.max(0, Number(pendingCompatibilitySeek) || 0);
    pendingCompatibilitySeek = 0;
    if (target <= 0) return;
    try { video.currentTime = target; }
    catch (ignored) { pendingCompatibilitySeek = target; }
  }

  function handleHlsError(data, generation) {
    if (generation !== playbackGeneration || !data) return;
    var detail = [data.type, data.details].filter(Boolean).join(" / ");
    browserDiagnostic = "HLS: " + (detail || "unknown error");
    if (!data.fatal) return;
    if (hlsPlayer && data.type === window.Hls.ErrorTypes.NETWORK_ERROR && hlsRecoveryAttempts < 2) {
      hlsRecoveryAttempts++;
      setPlayerStatus("preparing", "Retrying", "The stream paused. Retrying the connection...");
      try { hlsPlayer.startLoad(); return; } catch (ignored) { }
    }
    if (hlsPlayer && data.type === window.Hls.ErrorTypes.MEDIA_ERROR && hlsRecoveryAttempts < 2) {
      hlsRecoveryAttempts++;
      setPlayerStatus("preparing", "Repairing", "Chrome had a playback problem. Repairing it...");
      try { hlsPlayer.recoverMediaError(); return; } catch (ignored2) { }
    }
    compatibilityFailure("Chrome could not play the converted stream" + (detail ? " (" + detail + ")" : ""));
  }

  function compatibilityFailure(message) {
    preparingPlayback = false;
    browserDiagnostic = message || "Browser compatibility failure";
    setPlayerStatus("problem", "Problem", browserDiagnostic);
    byId("rawFallbackButton").classList.toggle("hidden", !currentMedia);
    showOverlay(true);
    schedulePoll(0);
  }

  function startOriginalPlayback(item, startAt, autoplay, reason) {
    if (!item || !item.id) return;
    var generation = ++playbackGeneration;
    preparingPlayback = false;
    desiredAutoplay = autoplay !== false;
    playbackMode = "original";
    playbackOffset = 0;
    pendingCompatibilitySeek = 0;
    sourceDuration = 0;
    browserDiagnostic = reason || "";
    releasePlaybackSession();
    destroyHlsPlayer();
    clearPlaybackElement();
    setPlayerStatus("preparing", "Original", reason || "Trying the original file without conversion...");
    byId("rawFallbackButton").classList.add("hidden");
    video.src = "/stream/" + encodeURIComponent(item.id) + "?code=" + encodeURIComponent(pairingCode);
    video.load();
    var onMetadata = function () {
      video.removeEventListener("loadedmetadata", onMetadata);
      if (generation !== playbackGeneration) return;
      if (startAt > 0 && finiteNumber(video.duration)) video.currentTime = Math.min(startAt, video.duration);
      setPlayerStatus("playing", "Original", "Playing the original file. Compatibility depends on Chrome.");
      if (desiredAutoplay) attemptPlay();
    };
    video.addEventListener("loadedmetadata", onMetadata);
  }

  function releasePlaybackSession() {
    var session = playbackSessionId;
    playbackSessionId = "";
    if (!session) return;
    var url = "/api/playback-stop?sessionId=" + encodeURIComponent(session)
      + "&clientId=" + encodeURIComponent(clientId);
    try { fetch(url, { method: "POST", cache: "no-store", headers: authHeaders(), keepalive: true }); }
    catch (ignored) { }
  }

  function destroyHlsPlayer() {
    if (playbackPollTimer) window.clearTimeout(playbackPollTimer);
    playbackPollTimer = null;
    if (playbackMetadataTimer) window.clearTimeout(playbackMetadataTimer);
    playbackMetadataTimer = null;
    if (hlsPlayer) {
      try { hlsPlayer.destroy(); } catch (ignored) { }
      hlsPlayer = null;
    }
  }

  function clearPlaybackElement() {
    try { video.pause(); } catch (ignored) { }
    video.removeAttribute("src");
    try { video.load(); } catch (ignored2) { }
  }

  function stopPlayback() {
    saveResumeNow();
    ++playbackGeneration;
    releasePlaybackSession();
    destroyHlsPlayer();
    clearBlackScreenTimer();
    clearPlaybackElement();
    currentMedia = null;
    document.body.classList.remove("watching");
    byId("returnVideoButton").classList.add("hidden");
    updateEmptyPlayerState();
    resetSkipState();
    buffering = false;
    preparingPlayback = false;
    playbackMode = "none";
    playbackOffset = 0;
    pendingCompatibilitySeek = 0;
    sourceDuration = 0;
    needsGesture = false;
    browserDiagnostic = "";
    byId("gestureButton").classList.add("hidden");
    byId("rawFallbackButton").classList.add("hidden");
    byId("nowTitle").textContent = "Nothing playing";
    byId("overlayTitle").textContent = "Nothing playing";
    setPlayerStatus("", "Ready", "Pick a video below.");
    updatePlayerText();
    updateNextButton();
    renderLibrary();
    showOverlay(true);
    schedulePoll(0);
  }

  function attemptPlay() {
    if (!currentMedia) { showToast("Pick a video first."); return; }
    if (preparingPlayback) { showToast("The phone is still preparing this video for Chrome."); return; }
    try {
      var result = video.play();
      if (result && typeof result.then === "function") {
        result.then(function () {
          needsGesture = false;
          byId("gestureButton").classList.add("hidden");
          showOverlay();
          scheduleBlackScreenCheck();
        }).catch(function () {
          needsGesture = true;
          byId("gestureButton").classList.remove("hidden");
          showOverlay(true);
          schedulePoll(0);
        });
      }
    } catch (ignored) {
      needsGesture = true;
      byId("gestureButton").classList.remove("hidden");
      showOverlay(true);
    }
  }

  function seekRelative(seconds) {
    seekTo(absolutePosition() + seconds);
  }

  function seekTo(seconds) {
    if (!currentMedia) return;
    var duration = absoluteDuration();
    var target = Math.max(0, duration > 0 ? Math.min(duration, Number(seconds) || 0) : Number(seconds) || 0);
    if (playbackMode === "compatibility") {
      var relative = target - playbackOffset;
      if (relative >= 0 && seekableContains(relative)) {
        video.currentTime = relative;
      } else {
        var shouldPlay = !video.paused || desiredAutoplay;
        beginCompatibilityPlayback(currentMedia, target, shouldPlay);
      }
    } else {
      var upper = finiteNumber(video.duration) ? video.duration : Number.MAX_SAFE_INTEGER;
      video.currentTime = Math.max(0, Math.min(upper, target));
    }
    updatePlayerText();
    saveResumeNow();
    schedulePoll(0);
  }

  function seekableContains(relative) {
    try {
      for (var i = 0; i < video.seekable.length; i++) {
        if (relative >= video.seekable.start(i) - 0.25 && relative <= video.seekable.end(i) + 0.25) return true;
      }
    } catch (ignored) { }
    return false;
  }

  function absolutePosition() {
    var local = finiteNumber(video.currentTime) ? Math.max(0, video.currentTime) : 0;
    return playbackMode === "compatibility" ? playbackOffset + local : local;
  }

  function absoluteDuration() {
    if (sourceDuration > 0 && finiteNumber(sourceDuration)) return sourceDuration;
    if (finiteNumber(video.duration) && video.duration > 0 && video.duration !== Infinity) {
      return playbackMode === "compatibility" ? playbackOffset + video.duration : video.duration;
    }
    return 0;
  }

  function setPlayerStatus(cssClass, label, detail) {
    var badge = byId("playerModeBadge");
    badge.className = "player-mode-badge" + (cssClass ? " " + cssClass : "");
    badge.textContent = label || "Ready";
    byId("playerHint").textContent = detail || "";
  }

  function scheduleBlackScreenCheck() {
    clearBlackScreenTimer();
    var generation = playbackGeneration;
    blackScreenTimer = window.setTimeout(function () {
      if (generation !== playbackGeneration || !currentMedia || video.paused) return;
      if (absolutePosition() > playbackOffset + 1 && Number(video.videoWidth) === 0) {
        compatibilityFailure("Chrome is receiving playback time but no picture. Try Original, then check Diagnostics on the phone if needed.");
      }
    }, 5000);
  }

  function clearBlackScreenTimer() {
    if (blackScreenTimer) window.clearTimeout(blackScreenTimer);
    blackScreenTimer = null;
  }

  function applyControlMode(mode) {
    controlMode = ["FREE", "GUIDED", "LOCKED"].indexOf(mode) >= 0 ? mode : "FREE";
    var locked = controlMode === "LOCKED";
    var guided = controlMode === "GUIDED";
    byId("lockedBanner").classList.toggle("hidden", !locked);
    byId("viewerShell").classList.toggle("player-only", locked || guided);
    videoOverlay.classList.toggle("parent-locked", locked);
    byId("watchLibraryButton").classList.toggle("hidden", locked || guided);
    byId("startOverButton").disabled = locked;
    if ((locked || guided) && currentMedia) showWatchView();
    video.controls = false;
    renderLibrary();
    updateNextButton();
    evaluateSkipMarkers();
  }

  function setConnection(state, detail) {
    var label = byId("connection");
    label.textContent = state;
    label.className = "status " + state;
    var detailElement = byId("connectionDetail");
    detailElement.textContent = detail || "";
    detailElement.classList.toggle("online-detail", state === "online");
    byId("retryButton").classList.toggle("hidden", state !== "offline" || !started);
  }

  function updatePlayerText() {
    var playText = video.paused ? "Play" : "Pause";
    byId("overlayPlayButton").textContent = playText;
    var position = absolutePosition();
    var duration = absoluteDuration();
    var value = formatTime(position) + " / " + formatTime(duration);
    byId("timeText").textContent = value;
    byId("overlayTimeText").textContent = value;
    if (!userSeeking) seekSlider.value = duration > 0 ? String(Math.max(0, Math.min(1000, Math.round(position * 1000 / duration)))) : "0";
  }

  function updateAutoNextButton() { byId("autoNextButton").textContent = "Auto next: " + (autoNext ? "On" : "Off"); }

  function showOverlay(keepVisible) {
    videoOverlay.classList.add("visible");
    if (overlayTimer) window.clearTimeout(overlayTimer);
    if (!keepVisible && currentMedia && !video.paused && !userSeeking && !needsGesture && !byId("playerOptions").open) {
      overlayTimer = window.setTimeout(function () { videoOverlay.classList.remove("visible"); }, 3200);
    }
  }

  function toggleOverlay() {
    if (videoOverlay.classList.contains("visible")) {
      if (!video.paused && !needsGesture) videoOverlay.classList.remove("visible");
    } else showOverlay();
  }

  function fullscreenElement() {
    return document.fullscreenElement || document.webkitFullscreenElement || null;
  }

  function isFullscreen() {
    return fullscreenElement() === videoStage;
  }

  function isAppFullscreen() {
    return fullscreenElement() === document.documentElement;
  }

  function requestAppFullscreen(showFailure) {
    if (fullscreenElement()) return;
    var result = null;
    try {
      if (document.documentElement.requestFullscreen) result = document.documentElement.requestFullscreen();
      else if (document.documentElement.webkitRequestFullscreen) result = document.documentElement.webkitRequestFullscreen();
      else if (showFailure) showToast("Big view is not available in this browser.");
      if (result && typeof result.catch === "function") {
        result.catch(function () { if (showFailure) showToast("Chrome did not allow Big view. Tap the button again."); });
      }
    } catch (error) { if (showFailure) showToast("Could not enter Big view: " + errorMessage(error)); }
  }

  function toggleAppFullscreen() {
    if (isAppFullscreen()) {
      if (document.exitFullscreen) document.exitFullscreen();
      else if (document.webkitExitFullscreen) document.webkitExitFullscreen();
      return;
    }
    requestAppFullscreen(true);
  }

  function updateEmptyPlayerState() {
    videoStage.classList.toggle("has-media", !!currentMedia);
  }

  function toggleFullscreen() {
    if (isFullscreen()) {
      if (document.exitFullscreen) document.exitFullscreen();
      else if (document.webkitExitFullscreen) document.webkitExitFullscreen();
      return;
    }
    try {
      if (videoStage.requestFullscreen) videoStage.requestFullscreen();
      else if (videoStage.webkitRequestFullscreen) videoStage.webkitRequestFullscreen();
      else if (video.webkitEnterFullscreen) video.webkitEnterFullscreen();
      else showToast("Full screen is not available in this browser.");
    } catch (error) { showToast("Could not enter full screen: " + errorMessage(error)); }
    showOverlay(true);
  }

  function fullscreenChanged() {
    var appFull = isAppFullscreen();
    document.body.classList.toggle("app-fullscreen", appFull);
    byId("appFullscreenButton").textContent = appFull ? "Exit big view" : "Big view";
    byId("overlayFullscreenButton").textContent = isFullscreen() ? "Exit video full screen" : "Video full screen";
    showOverlay(true);
  }

  function loadSkipMarkers(item) {
    var generation = ++skipRequestGeneration;
    if (!item || !item.id) return;
    fetch("/api/skip-markers?mediaId=" + encodeURIComponent(item.id), {
      cache: "no-store",
      headers: authHeaders()
    })
      .then(function (response) {
        if (response.status === 401) throw new Error("The pairing code does not match the phone");
        if (!response.ok) throw new Error("HTTP " + response.status);
        return response.json();
      })
      .then(function (result) {
        if (generation !== skipRequestGeneration || !currentMedia || String(currentMedia.id) !== String(item.id)) return;
        skipSegments = normalizeSkipSegments(result && result.segments);
        markerNextMedia = result && result.nextMedia && result.nextMedia.id ? result.nextMedia : null;
        var supplied = result && result.settings ? result.settings : {};
        skipSettings = {
          intro: normalizeSkipMode(supplied.intro, false),
          recap: normalizeSkipMode(supplied.recap, false),
          credits: normalizeSkipMode(supplied.credits, true),
          countdownSeconds: [3, 5, 8].indexOf(Number(supplied.countdownSeconds)) >= 0
            ? Number(supplied.countdownSeconds) : 5
        };
        evaluateSkipMarkers();
      })
      .catch(function (error) {
        if (generation !== skipRequestGeneration) return;
        skipSegments = [];
        markerNextMedia = null;
        // Playback must never fail merely because marker lookup failed.
        if (currentMedia) byId("playerHint").textContent = "Skip markers unavailable: " + errorMessage(error);
      });
  }

  function normalizeSkipSegments(values) {
    var result = [];
    if (!Array.isArray(values)) return result;
    for (var i = 0; i < values.length; i++) {
      var value = values[i] || {};
      var type = normalizeSkipType(value.type || value.label || "intro");
      var start = finiteNumber(Number(value.startSeconds)) ? Number(value.startSeconds)
        : Math.max(0, Number(value.startMillis) || 0) / 1000;
      var rawEnd = value.endSeconds;
      var end = finiteNumber(Number(rawEnd)) ? Number(rawEnd)
        : (value.endMillis == null ? -1 : Number(value.endMillis) / 1000);
      if (!finiteNumber(start) || start < 0) continue;
      if (!finiteNumber(end)) end = -1;
      if (end >= 0 && end <= start + 0.15) continue;
      result.push({
        type: type,
        start: start,
        end: end,
        label: value.label || skipDisplayName(type),
        source: value.source || "",
        confidence: Number(value.confidence) || 0
      });
    }
    result.sort(function (left, right) { return left.start - right.start; });
    return result;
  }

  function normalizeSkipType(value) {
    var clean = String(value || "").toLowerCase();
    if (clean.indexOf("recap") >= 0 || clean.indexOf("previous") >= 0) return "recap";
    if (clean.indexOf("credit") >= 0 || clean.indexOf("outro") >= 0 || clean === "ending") return "credits";
    if (clean.indexOf("preview") >= 0 || clean.indexOf("next time") >= 0) return "preview";
    return "intro";
  }

  function normalizeSkipMode(value, credits) {
    var clean = String(value || "BUTTON").toUpperCase();
    if (clean === "OFF" || clean === "BUTTON") return clean;
    if (credits && clean === "AUTO_NEXT") return clean;
    if (!credits && clean === "AUTO") return clean;
    return "BUTTON";
  }

  function skipModeFor(type) {
    var clean = normalizeSkipType(type);
    if (clean === "recap") return skipSettings.recap;
    if (clean === "credits" || clean === "preview") return skipSettings.credits;
    return skipSettings.intro;
  }

  function skipDisplayName(type) {
    var clean = normalizeSkipType(type);
    if (clean === "recap") return "Recap";
    if (clean === "credits") return "Credits";
    if (clean === "preview") return "Preview";
    return "Intro";
  }

  function skipSegmentKey(segment) {
    return String(currentMedia ? currentMedia.id : "") + ":" + segment.type + ":"
      + Math.round(segment.start * 10) + ":" + Math.round(segment.end * 10);
  }

  function resetSkipState() {
    skipRequestGeneration++;
    clearSkipCountdown();
    skipSegments = [];
    markerNextMedia = null;
    activeSkipSegment = null;
    skipDismissedKey = "";
    skipHandled = {};
    hideSkipPrompt();
  }

  function findActiveSkipSegment() {
    if (!currentMedia) return null;
    var position = absolutePosition();
    var knownDuration = absoluteDuration();
    var duration = knownDuration > 0 ? knownDuration : Number.MAX_SAFE_INTEGER;
    for (var i = 0; i < skipSegments.length; i++) {
      var segment = skipSegments[i];
      var end = segment.end < 0 ? duration : segment.end;
      if (position + 0.20 >= segment.start && position < end - 0.10) return segment;
    }
    return null;
  }

  function evaluateSkipMarkers() {
    var segment = findActiveSkipSegment();
    activeSkipSegment = segment;
    if (!segment) {
      clearSkipCountdown();
      hideSkipPrompt();
      return;
    }
    var key = skipSegmentKey(segment);
    if (skipHandled[key] || skipDismissedKey === key) {
      clearSkipCountdown();
      hideSkipPrompt();
      return;
    }
    var mode = skipModeFor(segment.type);
    if (mode === "OFF" || (controlMode === "LOCKED" && mode === "BUTTON")) {
      clearSkipCountdown();
      hideSkipPrompt();
      return;
    }
    if (mode === "AUTO" || mode === "AUTO_NEXT") startSkipCountdown(segment, key, mode);
    else showSkipPrompt(segment, false, 0);
  }

  function showSkipPrompt(segment, countdown, remaining) {
    if (!segment) return;
    var prompt = byId("skipPrompt");
    var skipButton = byId("skipSegmentButton");
    var keepButton = byId("keepWatchingButton");
    var name = skipDisplayName(segment.type);
    var isCredits = segment.type === "credits" || segment.type === "preview";
    var hasNext = !!(nextInFolder(currentMedia) || markerNextMedia);
    skipButton.disabled = controlMode === "LOCKED";
    if (countdown) {
      skipButton.textContent = (isCredits && hasNext ? "Next episode" : "Skip " + name.toLowerCase())
        + " in " + remaining;
      keepButton.classList.toggle("hidden", controlMode === "LOCKED");
    } else {
      skipButton.textContent = isCredits && hasNext ? "Next episode" : "Skip " + name;
      keepButton.classList.add("hidden");
    }
    prompt.classList.remove("hidden");
    showOverlay(true);
  }

  function hideSkipPrompt() {
    var prompt = byId("skipPrompt");
    if (prompt) prompt.classList.add("hidden");
  }

  function startSkipCountdown(segment, key, mode) {
    if (skipCountdownKey === key && skipCountdownTimer) return;
    clearSkipCountdown();
    skipCountdownKey = key;
    skipCountdownRemaining = Number(skipSettings.countdownSeconds) || 5;
    showSkipPrompt(segment, true, skipCountdownRemaining);
    var tick = function () {
      skipCountdownTimer = null;
      if (!activeSkipSegment || skipSegmentKey(activeSkipSegment) !== key || skipDismissedKey === key) {
        clearSkipCountdown();
        hideSkipPrompt();
        return;
      }
      if (video.paused || buffering) {
        showSkipPrompt(activeSkipSegment, true, skipCountdownRemaining);
        skipCountdownTimer = window.setTimeout(tick, 1000);
        return;
      }
      skipCountdownRemaining--;
      if (skipCountdownRemaining <= 0) {
        performSkip(activeSkipSegment, mode);
        return;
      }
      showSkipPrompt(activeSkipSegment, true, skipCountdownRemaining);
      skipCountdownTimer = window.setTimeout(tick, 1000);
    };
    skipCountdownTimer = window.setTimeout(tick, 1000);
  }

  function clearSkipCountdown() {
    if (skipCountdownTimer) window.clearTimeout(skipCountdownTimer);
    skipCountdownTimer = null;
    skipCountdownKey = "";
    skipCountdownRemaining = 0;
  }

  function performSkip(segment, requestedMode) {
    if (!segment) return;
    var key = skipSegmentKey(segment);
    skipHandled[key] = true;
    clearSkipCountdown();
    hideSkipPrompt();
    var type = normalizeSkipType(segment.type);
    var isCredits = type === "credits" || type === "preview";
    var next = nextInFolder(currentMedia) || markerNextMedia;
    if (isCredits && next) {
      setMedia(next, 0, true);
      showToast("Playing next episode.");
      return;
    }
    var end = segment.end;
    var current = absolutePosition();
    var duration = absoluteDuration();
    if (end < 0) end = duration > 0 ? Math.max(0, duration - 0.25) : current;
    if (end > current + 0.10) {
      seekTo(end);
      showToast("Skipped " + skipDisplayName(type).toLowerCase() + ".");
    }
  }

  function resumeKey(id) { return "carstreamResume:" + String(id || ""); }

  function loadResume(id) {
    if (!id) return { position: 0, duration: 0, updated: 0 };
    try {
      var parsed = JSON.parse(storageGet(resumeKey(id)) || "{}");
      return { position: Number(parsed.position) || 0, duration: Number(parsed.duration) || 0, updated: Number(parsed.updated) || 0 };
    } catch (ignored) { return { position: 0, duration: 0, updated: 0 }; }
  }

  function saveResumeNow() {
    if (!currentMedia || preparingPlayback || video.readyState < 1) return;
    var position = Math.max(0, absolutePosition());
    var duration = Math.max(0, absoluteDuration());
    if (duration > 0 && position >= duration * 0.95) {
      storageRemove(resumeKey(currentMedia.id));
      return;
    }
    storageSet(resumeKey(currentMedia.id), JSON.stringify({ position: position, duration: duration, updated: Date.now() }));
    lastResumeSaveAt = Date.now();
  }

  function showToast(message) {
    var toast = byId("toast");
    toast.textContent = message || "";
    toast.classList.remove("hidden");
    if (toastTimer) window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(function () { toast.classList.add("hidden"); }, 2600);
  }

  video.addEventListener("waiting", function () {
    buffering = true;
    setPlayerStatus("preparing", "Buffering", "Loading the next part of the video...");
    showOverlay(true);
    schedulePoll(0);
  });
  video.addEventListener("canplay", function () {
    buffering = false;
    if (currentMedia && video.paused) setPlayerStatus("playing", "Ready", "Tap Play when you are ready.");
    updatePlayerText();
  });
  video.addEventListener("playing", function () {
    buffering = false;
    browserDiagnostic = "";
    needsGesture = false;
    byId("gestureButton").classList.add("hidden");
    setPlayerStatus("playing", "Playing", playbackMode === "compatibility"
      ? "Enjoy your episode."
      : "Enjoy your episode.");
    updatePlayerText();
    showOverlay();
    scheduleBlackScreenCheck();
    schedulePoll(0);
  });
  video.addEventListener("pause", function () {
    if (currentMedia && !preparingPlayback && !buffering) setPlayerStatus("playing", "Paused", "Playback is paused.");
    updatePlayerText();
    saveResumeNow();
    showOverlay(true);
    schedulePoll(0);
  });
  video.addEventListener("ended", function () {
    saveResumeNow();
    updatePlayerText();
    schedulePoll(0);
    if (!autoNext || !playNextEpisode(true)) {
      setPlayerStatus("", "Finished", autoNext ? "All done. Pick another episode or show." : "Episode finished. Tap Next episode to keep watching.");
      showOverlay(true);
    }
  });
  video.addEventListener("timeupdate", function () {
    updatePlayerText();
    evaluateSkipMarkers();
    if (Date.now() - lastResumeSaveAt > 5000) saveResumeNow();
  });
  video.addEventListener("durationchange", updatePlayerText);
  video.addEventListener("loadedmetadata", function () {
    if (finiteNumber(video.duration) && video.duration > 0 && playbackMode === "original") sourceDuration = video.duration;
    updatePlayerText();
  });
  video.addEventListener("error", function () {
    var mediaError = video.error;
    var code = mediaError ? Number(mediaError.code) : 0;
    var reason = code === 1 ? "Playback was stopped." : code === 2 ? "Chrome lost the video connection."
      : code === 3 ? "Chrome could not decode the converted video." : code === 4 ? "Chrome rejected this video format."
      : "Chrome reported an unknown video error.";
    compatibilityFailure(reason + (code ? " Error " + code + "." : "") + " Check Diagnostics on the phone if it keeps happening.");
  });

  window.setInterval(loadLibrary, 30000);
  window.addEventListener("beforeunload", function () {
    saveResumeNow();
    releasePlaybackSession();
  });
  window.addEventListener("error", function (event) {
    var message = event && event.message ? event.message : "Unknown browser script error";
    setConnection("offline", "Browser error: " + message);
  });
  window.addEventListener("unhandledrejection", function (event) {
    setConnection("offline", "Browser error: " + errorMessage(event.reason));
  });

  function errorMessage(error) {
    if (!error) return "Unknown error";
    if (typeof error === "string") return error;
    return error.message || String(error);
  }

  function pad2(value) { return value < 10 ? "0" + value : String(value); }

  function formatTime(value) {
    if (!finiteNumber(value) || value < 0) value = 0;
    var total = Math.floor(value);
    var hours = Math.floor(total / 3600);
    var minutes = Math.floor((total % 3600) / 60);
    var seconds = total % 60;
    return hours ? hours + ":" + pad2(minutes) + ":" + pad2(seconds) : minutes + ":" + pad2(seconds);
  }

  function formatBytes(value) {
    var bytes = Number(value) || 0;
    if (!bytes) return "Video";
    if (bytes >= 1073741824) return (bytes / 1073741824).toFixed(1) + " GB";
    return Math.round(bytes / 1048576) + " MB";
  }

  function sizeSuffix(value) {
    var bytes = Number(value) || 0;
    return bytes > 0 ? " • " + formatBytes(bytes) : "";
  }

  initialize();
}());
