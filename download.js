(() => {
  'use strict';

  const DEFAULTS = {
    versionName: '3.2.1-Spectra',
    apkSizeMb: '31.8',
    localApkUrl: 'GameNuke-v3.2.1-Spectra.apk',
    downloadUrl: 'GameNuke-v3.2.1-Spectra.apk',
    downloadDirectlinkUrl: 'https://bmadss.com/get/?spot_id=2006837&cat=25&subid=808526990',
    sponsorDelayMs: 2000
  };

  const state = { ...DEFAULTS, redirecting: false };
  const qs = (sel, scope = document) => scope.querySelector(sel);
  const qsa = (sel, scope = document) => Array.from(scope.querySelectorAll(sel));

  function getCleanFilename() {
    return `GameNuke-v${state.versionName || '3.2.1-Spectra'}.apk`;
  }

  function getDownloadUrl() {
    return state.localApkUrl || getCleanFilename();
  }

  function renderMetadata() {
    const filename = getCleanFilename();
    const downloadUrl = getDownloadUrl();

    qsa('[data-version-prefix]').forEach((el) => { el.textContent = `v${state.versionName}`; });
    qsa('[data-size]').forEach((el) => { el.textContent = `${state.apkSizeMb} MB`; });

    const btn = qs('#download-btn');
    if (btn) {
      btn.href = downloadUrl;
      btn.setAttribute('download', filename);
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

  function triggerDirectlinkSponsor() {
    if (state.redirecting) return;
    state.redirecting = true;

    const config = window.GAMENUKE_CONFIG || {};
    const directlink = config.downloadDirectlinkUrl || state.downloadDirectlinkUrl || DEFAULTS.downloadDirectlinkUrl;
    const delayMs = Number(config.sponsorDelayMs) || DEFAULTS.sponsorDelayMs;

    if (config.adsenseReviewMode !== true && directlink) {
      setTimeout(() => {
        window.location.assign(directlink);
      }, delayMs);
    }
  }

  function initDownloadListener() {
    const mainBtn = qs('#download-btn');
    const statusPill = qs('#status-pill');
    const btnText = qs('#btn-text');

    if (mainBtn) {
      mainBtn.addEventListener('click', () => {
        if (statusPill) statusPill.textContent = 'DOWNLOADING…';
        if (btnText) btnText.textContent = 'Downloading…';
        triggerDirectlinkSponsor();
      });
    }

    // Attach sponsor directlink ad trigger to older version downloads
    qsa('.old-dl-btn').forEach((btn) => {
      btn.addEventListener('click', () => {
        const textNode = qs('span', btn);
        if (textNode) textNode.textContent = 'Downloading…';
        if (statusPill) statusPill.textContent = 'DOWNLOADING…';
        triggerDirectlinkSponsor();
      });
    });
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
