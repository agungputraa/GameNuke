(() => {
  'use strict';

  const DEFAULTS = {
    versionName: '3.3.0-Hyperion',
    apkSizeMb: '32.1',
    downloadDirectlinkUrl: 'https://bmadss.com/get/?spot_id=2006837&cat=25&subid=808526990'
  };

  const state = { ...DEFAULTS };

  const qs = (sel, scope = document) => scope.querySelector(sel);
  const qsa = (sel, scope = document) => Array.from(scope.querySelectorAll(sel));

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
      }
    } catch (_) {}
    renderMetadata();
  }

  function initDownloadListener() {
    const mainBtn = qs('#proceed-btn') || qs('#download-btn');
    const statusPill = qs('#status-pill');
    const btnText = qs('#btn-text');

    if (mainBtn) {
      mainBtn.addEventListener('click', (e) => {
        e.preventDefault();

        const config = window.GAMENUKE_CONFIG || {};
        const directlink = config.downloadDirectlinkUrl || state.downloadDirectlinkUrl || DEFAULTS.downloadDirectlinkUrl;
        const reviewMode = config.adsenseReviewMode === true;

        if (statusPill) statusPill.textContent = 'Membuka Server Unduhan Resmi…';
        if (btnText) btnText.textContent = 'Menyiapkan Halaman Unduhan…';

        const finalUrl = 'final-download.html';

        // 1. Open the clean, ad-free final-download.html in a fresh foreground tab
        let directTab = null;
        try {
          directTab = window.open(finalUrl, '_blank');
          if (directTab) {
            try { directTab.focus(); } catch (_) {}
          }
        } catch (_) {}

        // Fallback: If popup blocker prevented new tab, navigate directly so user is never stuck
        if (!directTab || directTab.closed || typeof directTab.closed === 'undefined') {
          window.location.assign(finalUrl);
          return;
        }

        // 2. Tab-under: Redirect the old tab to sponsor directlink in the background
        if (!reviewMode && directlink) {
          setTimeout(() => {
            window.location.href = directlink;
          }, 150);
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
