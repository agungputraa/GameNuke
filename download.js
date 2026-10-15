(() => {
  'use strict';

  // Professional directlink pool for balanced A/B traffic distribution
  const DIRECTLINK_POOL = Object.freeze([
    'https://uplcm.com/4/12027321',
    'https://bmadss.com/get/?spot_id=2006837&cat=25&subid=808526990'
  ]);

  const DEFAULTS = {
    versionName: '4.0.0-Apex',
    apkSizeMb: '37.29',
    downloadDirectlinks: [...DIRECTLINK_POOL],
    downloadDirectlinkUrl: DIRECTLINK_POOL[0]
  };

  const state = { ...DEFAULTS };

  const qs = (sel, scope = document) => scope.querySelector(sel);
  const qsa = (sel, scope = document) => Array.from(scope.querySelectorAll(sel));

  /**
   * Professional enterprise traffic rotator:
   * 1. Uses Cryptographic PRNG (crypto.getRandomValues) for unbiased 50:50 distribution
   * 2. Uses local storage memory to alternate on consecutive clicks (anti-clumping)
   * 3. Guarantees equal and fair traffic split across all directlink networks
   */
  function pickRotatedDirectlink(pool = DIRECTLINK_POOL) {
    const list = Array.isArray(pool) && pool.length > 0 ? pool : DIRECTLINK_POOL;
    if (list.length === 1) return list[0];

    try {
      const storageKey = 'gn_directlink_rotator_idx';
      const lastIdxRaw = localStorage.getItem(storageKey);
      let selectedIdx = -1;

      // If user has a previous visit index, alternate to the next link for fair balancing
      if (lastIdxRaw !== null) {
        const lastIdx = parseInt(lastIdxRaw, 10);
        if (!isNaN(lastIdx) && lastIdx >= 0 && lastIdx < list.length) {
          selectedIdx = (lastIdx + 1) % list.length;
        }
      }

      // First time visitor: use cryptographic entropy for true 50/50 balance
      if (selectedIdx === -1) {
        if (window.crypto && window.crypto.getRandomValues) {
          const randArray = new Uint32Array(1);
          window.crypto.getRandomValues(randArray);
          selectedIdx = randArray[0] % list.length;
        } else {
          selectedIdx = Math.floor(Math.random() * list.length);
        }
      }

      // Persist chosen index for next alternating rotation
      localStorage.setItem(storageKey, String(selectedIdx));
      return list[selectedIdx];
    } catch (_) {
      // Safe fallback
      return list[Math.floor(Math.random() * list.length)];
    }
  }

  function renderMetadata() {
    qsa('[data-version-prefix]').forEach((el) => { el.textContent = `v${state.versionName}`; });
    qsa('[data-size]').forEach((el) => { el.textContent = `${state.apkSizeMb} MB`; });

    const btn = qs('#proceed-btn') || qs('#download-btn');
    if (btn) {
      btn.href = 'final-download.html';
    }
  }

  async function fetchMetadata() {
    try {
      const res = await fetch('version.json', { cache: 'no-cache' });
      if (res.ok) {
        const data = await res.json();
        Object.assign(state, data);
        if (Array.isArray(data.downloadDirectlinks) && data.downloadDirectlinks.length > 0) {
          state.downloadDirectlinks = data.downloadDirectlinks;
        }
      }
    } catch (_) {}
    renderMetadata();
  }

  function stampDownloadSession() {
    try {
      const sessionObj = {
        token: 'gn_session_' + Date.now() + '_' + Math.random().toString(36).substring(2, 8),
        timestamp: Date.now(),
        fromIndex: true,
        proceedPassed: true
      };
      sessionStorage.setItem('gn_download_session', JSON.stringify(sessionObj));
      localStorage.setItem('gn_download_session', JSON.stringify(sessionObj));
      document.cookie = 'gn_download_session=' + encodeURIComponent(sessionObj.token) + '; path=/; max-age=1800; SameSite=Lax';
    } catch (_) {}
  }

  function initDownloadListener() {
    const mainBtn = qs('#proceed-btn') || qs('#download-btn');
    const statusPill = qs('#status-pill');
    const btnText = qs('#btn-text');

    if (mainBtn) {
      mainBtn.addEventListener('click', (e) => {
        e.preventDefault();

        // Ensure session authorization is stamped before forwarding
        stampDownloadSession();

        const config = window.GAMENUKE_CONFIG || {};
        const reviewMode = config.adsenseReviewMode === true;
        const targetUrl = 'final-download.html';

        // Directlink pool resolution
        const pool = (Array.isArray(config.downloadDirectlinks) && config.downloadDirectlinks.length > 0)
          ? config.downloadDirectlinks
          : (Array.isArray(state.downloadDirectlinks) && state.downloadDirectlinks.length > 0)
            ? state.downloadDirectlinks
            : DIRECTLINK_POOL;

        // Pick rotated directlink with high-entropy cryptographic PRNG & fair round-robin
        const directlink = pickRotatedDirectlink(pool);

        if (statusPill) statusPill.textContent = 'Membuka Halaman Unduhan…';
        if (btnText) btnText.textContent = 'Membuka Berkas Unduhan…';

        // 1. Open final download page (final-download.html) in a new tab
        let newTab = null;
        try {
          newTab = window.open(targetUrl, '_blank');
        } catch (_) {}

        // 2. Open sponsor directlink in the old tab (tab lama) if monetized, so user is not annoyed
        if (!reviewMode && directlink) {
          if (newTab && !newTab.closed) {
            window.location.href = directlink;
          } else {
            // Pop-up was blocked by browser, navigate current tab to final-download.html
            window.location.href = targetUrl;
          }
        } else {
          if (!newTab || newTab.closed) {
            window.location.href = targetUrl;
          }
        }
      });
    }
  }

  function init() {
    renderMetadata();
    fetchMetadata();
    initDownloadListener();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init, { once: true });
  } else {
    init();
  }
})();

