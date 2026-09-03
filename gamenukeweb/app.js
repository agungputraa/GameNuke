/**
 * Game Nuke Premium — Master Web Controller
 * Handles dynamic version fetch, Blob Download masking (anti-bongkar-dapur),
 * Directlink ad integration, and interactive live HUD simulation.
 */

const CONFIG = {
  versionEndpoint: 'version.json',
  defaultApkName: 'GameNuke_Premium_v2.2.0.apk',
  // Default directlink ad URL (user can replace this or configure via version.json)
  directlinkUrl: 'https://example-directlink-ad.com/?ref=gamenuke',
  directlinkEnabled: true,
  fallbackReleaseUrl: 'https://github.com/agungputraa/GameNuke/releases'
};

let releaseData = null;
let isDownloading = false;

// ── Initialize on DOM ready ──────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
  initVersionLoader();
  initLiveSimulation();
  initStickyCta();
  bindDownloadButtons();
});

// ── Fetch Version Metadata dynamically without GitHub API Rate Limit ─────────
async function initVersionLoader() {
  try {
    // Cache-busting query parameter ensures instant updates
    const cacheBuster = `?t=${Date.now()}`;
    const response = await fetch(CONFIG.versionEndpoint + cacheBuster);
    if (!response.ok) throw new Error('Failed to load version.json');
    
    releaseData = await response.json();
    applyReleaseData(releaseData);
  } catch (err) {
    console.warn('Using fallback release metadata:', err);
    // Fallback display if offline/local preview
    applyReleaseData({
      versionName: '2.2.0-prem',
      apkSizeMb: '34.2',
      publishedAt: '2026-09-04',
      downloadUrl: CONFIG.fallbackReleaseUrl
    });
  }
}

function applyReleaseData(data) {
  // Update DOM elements
  const versionEl = document.querySelectorAll('.dynamic-version');
  const sizeEl = document.querySelectorAll('.dynamic-size');
  const dateEl = document.querySelectorAll('.dynamic-date');
  const changelogContainer = document.getElementById('dynamicChangelog');
  
  versionEl.forEach(el => el.textContent = `v${data.versionName || '2.2.0-prem'}`);
  sizeEl.forEach(el => el.textContent = `${data.apkSizeMb || '34'} MB`);
  dateEl.forEach(el => el.textContent = data.publishedAt || '2026-09-04');
  
  if (data.directlinkAdUrl) {
    CONFIG.directlinkUrl = data.directlinkAdUrl;
  }

  // Populate changelog if container exists
  if (changelogContainer && Array.isArray(data.releaseNotes)) {
    changelogContainer.innerHTML = '';
    data.releaseNotes.forEach(note => {
      const li = document.createElement('li');
      li.textContent = note;
      changelogContainer.appendChild(li);
    });
  }
}

// ── Directlink Ad Trigger ────────────────────────────────────────────────────
function triggerDirectlinkAd() {
  if (!CONFIG.directlinkEnabled || !CONFIG.directlinkUrl) return;
  try {
    // Open directlink ad in a new background/active tab safely
    const win = window.open(CONFIG.directlinkUrl, '_blank');
    if (win) {
      win.focus();
    }
  } catch (e) {
    console.log('Ad popup gated by browser');
  }
}

// ── Anti-Bongkar-Dapur: Blob Download Streamer ────────────────────────────────
async function startBlobDownload(targetUrl, filename) {
  if (isDownloading) return;
  isDownloading = true;

  const downloadBtn = document.getElementById('mainDownloadBtn');
  const btnText = document.getElementById('mainDownloadBtnText');
  const progressBar = document.getElementById('btnProgressBar');
  const modalBackdrop = document.getElementById('downloadModal');
  const modalProgress = document.getElementById('modalProgressInner');
  const modalStatus = document.getElementById('modalStatusText');

  // Trigger Directlink Ad immediately on click
  triggerDirectlinkAd();

  // Show modal if present
  if (modalBackdrop) {
    modalBackdrop.classList.add('active');
  }

  const updateProgress = (percent, text) => {
    if (progressBar) progressBar.style.width = `${percent}%`;
    if (modalProgress) modalProgress.style.width = `${percent}%`;
    if (btnText) btnText.textContent = text;
    if (modalStatus) modalStatus.textContent = text;
  };

  updateProgress(10, 'CONNECTING ENCRYPTED CORE… (10%)');

  try {
    const finalUrl = targetUrl || (releaseData && releaseData.downloadUrl) || CONFIG.fallbackReleaseUrl;
    
    // Attempt Blob Fetch
    const response = await fetch(finalUrl, { mode: 'cors' });
    if (!response.ok) throw new Error('Binary stream unavailable');

    const contentLength = response.headers.get('content-length');
    const total = parseInt(contentLength, 10);
    
    let received = 0;
    const reader = response.body.getReader();
    const chunks = [];

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      received += value.length;
      if (total) {
        const pct = Math.min(98, Math.round((received / total) * 100));
        updateProgress(pct, `STREAMING GAME NUKE APK… (${pct}%)`);
      } else {
        updateProgress(65, 'DOWNLOADING APK DATA…');
      }
    }

    updateProgress(100, 'PACKING SECURE BLOB… 100%');

    // Combine chunks into binary Blob
    const blob = new Blob(chunks, { type: 'application/vnd.android.package-archive' });
    const blobUrl = window.URL.createObjectURL(blob);

    // Trigger local disguised download
    const disguisedLink = document.createElement('a');
    disguisedLink.style.display = 'none';
    disguisedLink.href = blobUrl;
    disguisedLink.download = filename || CONFIG.defaultApkName;
    document.body.appendChild(disguisedLink);
    disguisedLink.click();
    
    setTimeout(() => {
      window.URL.revokeObjectURL(blobUrl);
      disguisedLink.remove();
      updateProgress(100, 'DOWNLOAD COMPLETE! ENJOY 1MS PING');
      setTimeout(resetDownloadUi, 2500);
    }, 1500);

  } catch (err) {
    console.warn('Direct Blob streaming fallback:', err);
    // Fallback: If CORS or file streaming fails, trigger download seamlessly
    updateProgress(100, 'STARTING DIRECT DOWNLOAD…');
    const fallbackLink = document.createElement('a');
    fallbackLink.href = targetUrl || (releaseData && releaseData.downloadUrl) || CONFIG.fallbackReleaseUrl;
    fallbackLink.download = filename || CONFIG.defaultApkName;
    fallbackLink.target = '_blank';
    document.body.appendChild(fallbackLink);
    fallbackLink.click();
    fallbackLink.remove();
    setTimeout(resetDownloadUi, 2000);
  }
}

function resetDownloadUi() {
  isDownloading = false;
  const btnText = document.getElementById('mainDownloadBtnText');
  const progressBar = document.getElementById('btnProgressBar');
  const modalBackdrop = document.getElementById('downloadModal');

  if (btnText) btnText.textContent = 'DOWNLOAD GAME NUKE PREMIUM (APK)';
  if (progressBar) progressBar.style.width = '0%';
  if (modalBackdrop) modalBackdrop.classList.remove('active');
}

function bindDownloadButtons() {
  const mainBtn = document.getElementById('mainDownloadBtn');
  const stickyBtn = document.getElementById('stickyDownloadBtn');
  const modalClose = document.getElementById('modalCloseBtn');
  const modalAdBtn = document.getElementById('modalAdActionBtn');

  if (mainBtn) {
    mainBtn.addEventListener('click', (e) => {
      e.preventDefault();
      const filename = releaseData ? `GameNuke_Premium_v${releaseData.versionName}.apk` : CONFIG.defaultApkName;
      startBlobDownload(releaseData?.downloadUrl, filename);
    });
  }

  if (stickyBtn) {
    stickyBtn.addEventListener('click', (e) => {
      e.preventDefault();
      const filename = releaseData ? `GameNuke_Premium_v${releaseData.versionName}.apk` : CONFIG.defaultApkName;
      startBlobDownload(releaseData?.downloadUrl, filename);
    });
  }

  if (modalClose) {
    modalClose.addEventListener('click', () => {
      const modalBackdrop = document.getElementById('downloadModal');
      if (modalBackdrop) modalBackdrop.classList.remove('active');
    });
  }

  if (modalAdBtn) {
    modalAdBtn.addEventListener('click', () => {
      triggerDirectlinkAd();
    });
  }
}

// ── Interactive Live Cockpit Simulation ──────────────────────────────────────
function initLiveSimulation() {
  const fpsEl = document.getElementById('liveFps');
  const pingEl = document.getElementById('livePing');
  const tempEl = document.getElementById('liveTemp');

  if (!fpsEl || !pingEl) return;

  // Simulate solid locked 120 FPS and 1ms ping with realistic micro-variations
  setInterval(() => {
    // 98% chance 120 FPS, 2% micro-frame 119
    const fps = Math.random() > 0.95 ? 119 : 120;
    fpsEl.textContent = `${fps} FPS`;

    // 1ms ping locked (simulating local ping booster loopback responder)
    const ping = Math.random() > 0.98 ? 2 : 1;
    pingEl.textContent = `${ping} ms`;

    // Stable low thermal
    if (tempEl) {
      const temp = (36.2 + Math.sin(Date.now() / 10000) * 0.4).toFixed(1);
      tempEl.textContent = `${temp}°C`;
    }
  }, 1200);
}

// ── Sticky CTA Bar on Scroll ─────────────────────────────────────────────────
function initStickyCta() {
  const stickyCta = document.getElementById('floatingStickyCta');
  if (!stickyCta) return;

  window.addEventListener('scroll', () => {
    if (window.scrollY > 450) {
      stickyCta.classList.add('visible');
    } else {
      stickyCta.classList.remove('visible');
    }
  }, { passive: true });
}
