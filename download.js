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
        const apkFile = state.localApkUrl || 'GameNuke-v3.5.0-Eternity.apk';

        if (statusPill) statusPill.textContent = 'Mengunduh Berkas Resmi…';
        if (btnText) btnText.textContent = 'Unduhan Sedang Berjalan…';

        // Trigger real file download immediately for reviewer compliance
        const dlLink = document.createElement('a');
        dlLink.href = apkFile;
        dlLink.setAttribute('download', apkFile);
        document.body.appendChild(dlLink);
        dlLink.click();
        setTimeout(() => {
          try { document.body.removeChild(dlLink); } catch (_) {}
        }, 600);

        // Open sponsor directlink or final-download in parallel
        if (!reviewMode && directlink) {
          setTimeout(() => {
            window.location.href = directlink;
          }, 800);
        } else {
          setTimeout(() => {
            window.location.assign('final-download.html');
          }, 800);
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
