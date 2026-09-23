(() => {
  'use strict';

  const DEFAULTS = {
    versionName: '3.5.0-Eternity',
    apkSizeMb: '33.7',
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
        const targetUrl = 'final-download.html';

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
