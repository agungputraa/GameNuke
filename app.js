(() => {
  'use strict';

  const DEFAULT_VERSION = {
    versionName: '2.7.0-prem',
    apkSizeMb: '24.2',
    publishedAt: '2026-09-11',
    directlinkAdUrl: 'https://dulyhagglermounting.com/2082665'
  };
  const state = { version: { ...DEFAULT_VERSION }, modalReturnFocus: null, translateRequested: false, downloadTransitioning: false };
  const qs = (selector, scope = document) => scope.querySelector(selector);
  const qsa = (selector, scope = document) => Array.from(scope.querySelectorAll(selector));

  function setText(selector, value) { qsa(selector).forEach((node) => { node.textContent = value; }); }
  function formatDate(value) {
    if (!value) return '09 Sep 2026';
    try { return new Intl.DateTimeFormat('en-GB', { day: '2-digit', month: 'short', year: 'numeric' }).format(new Date(`${value}T00:00:00`)); }
    catch (_) { return value; }
  }
  function renderVersion() {
    setText('[data-version-prefix]', `v${state.version.versionName || DEFAULT_VERSION.versionName}`);
    setText('[data-size]', `${state.version.apkSizeMb || DEFAULT_VERSION.apkSizeMb} MB`);
    setText('[data-published]', formatDate(state.version.publishedAt));
  }
  async function loadVersion() {
    try {
      const response = await fetch('version.json', { cache: 'no-cache' });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      state.version = { ...DEFAULT_VERSION, ...(await response.json()) };
    } catch (_) { state.version = { ...DEFAULT_VERSION }; }
    renderVersion();
  }

  function initHeader() {
    const header = qs('#site-header');
    if (!header) return;
    let frame = 0;
    const update = () => { frame = 0; header.classList.toggle('scrolled', window.scrollY > 20); };
    const request = () => { if (!frame) frame = requestAnimationFrame(update); };
    update();
    window.addEventListener('scroll', request, { passive: true });
  }

  function initMobileMenu() {
    const toggle = qs('#mobile-menu-toggle');
    const drawer = qs('#mobile-menu');
    if (!toggle || !drawer) return;
    const panel = qs('.drawer-panel', drawer);
    const focusable = () => qsa('a[href],button:not([disabled])', panel || drawer).filter((el) => el.offsetParent !== null);
    const close = ({ returnFocus = true } = {}) => {
      if (drawer.dataset.open !== 'true') return;
      delete drawer.dataset.open; drawer.setAttribute('aria-hidden', 'true'); toggle.setAttribute('aria-expanded', 'false'); toggle.setAttribute('aria-label', 'Buka menu navigasi'); document.body.classList.remove('menu-open');
      if (returnFocus) toggle.focus({ preventScroll: true });
    };
    const open = () => {
      drawer.dataset.open = 'true'; drawer.setAttribute('aria-hidden', 'false'); toggle.setAttribute('aria-expanded', 'true'); toggle.setAttribute('aria-label', 'Tutup menu navigasi'); document.body.classList.add('menu-open');
      requestAnimationFrame(() => focusable()[0]?.focus({ preventScroll: true }));
    };
    toggle.addEventListener('click', () => toggle.getAttribute('aria-expanded') === 'true' ? close() : open());
    qsa('[data-menu-close]', drawer).forEach((node) => node.addEventListener('click', () => close()));
    qsa('.mobile-nav a', drawer).forEach((link) => link.addEventListener('click', () => close({ returnFocus: false })));
    drawer.addEventListener('keydown', (event) => {
      if (event.key === 'Escape') { event.preventDefault(); close(); return; }
      if (event.key !== 'Tab' || drawer.dataset.open !== 'true') return;
      const list = focusable(); if (!list.length) return; const first = list[0]; const last = list[list.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    });
    const desktop = matchMedia('(min-width:1181px)');
    desktop.addEventListener?.('change', (e) => { if (e.matches) close({ returnFocus: false }); });
  }

  function initReveal() {
    qsa('[data-reveal]').forEach((el) => { el.style.setProperty('--reveal-delay', `${Number(el.dataset.delay || 0)}ms`); });
    if (!('IntersectionObserver' in window) || matchMedia('(prefers-reduced-motion:reduce)').matches) { qsa('[data-reveal]').forEach((el) => el.classList.add('is-visible')); return; }
    const observer = new IntersectionObserver((entries) => entries.forEach((entry) => { if (entry.isIntersecting) { entry.target.classList.add('is-visible'); observer.unobserve(entry.target); } }), { threshold: 0.12, rootMargin: '0px 0px -6% 0px' });
    qsa('[data-reveal]').forEach((el) => observer.observe(el));
  }


  function initActiveNavigation() {
    if (!('IntersectionObserver' in window)) return;
    const links = qsa('.desktop-nav a[href^="#"]'); const targets = links.map((link) => qs(link.getAttribute('href'))).filter(Boolean); const map = new Map(links.map((link) => [link.getAttribute('href').slice(1), link]));
    const observer = new IntersectionObserver((entries) => { entries.forEach((entry) => { if (!entry.isIntersecting) return; links.forEach((link) => link.classList.remove('is-active')); map.get(entry.target.id)?.classList.add('is-active'); }); }, { rootMargin: '-35% 0px -58% 0px', threshold: 0 });
    targets.forEach((target) => observer.observe(target));
  }

  function initLanguageMenu() {
    const toggle = qs('[data-language-toggle]'); const menu = qs('[data-language-menu]'); if (!toggle || !menu) return;
    const close = () => { menu.hidden = true; toggle.setAttribute('aria-expanded', 'false'); };
    const open = () => { menu.hidden = false; toggle.setAttribute('aria-expanded', 'true'); requestAnimationFrame(() => qs('button', menu)?.focus()); };
    toggle.addEventListener('click', (event) => { event.stopPropagation(); menu.hidden ? open() : close(); }); menu.addEventListener('click', (event) => event.stopPropagation()); document.addEventListener('click', close);
    document.addEventListener('keydown', (event) => { if (event.key === 'Escape' && !menu.hidden) { close(); toggle.focus(); } });
    setText('[data-current-language]', getCurrentLanguage().toUpperCase()); qsa('[data-language]').forEach((button) => button.addEventListener('click', () => changeLanguage(button.dataset.language)));
  }
  function getCurrentLanguage() { try { const cookie = document.cookie.match(/googtrans=\/id\/([a-zA-Z-]+)/); if (cookie?.[1]) return cookie[1].toLowerCase() === 'zh-cn' ? 'zh' : cookie[1].slice(0,2); return localStorage.getItem('gn_user_lang') || 'id'; } catch (_) { return 'id'; } }
  function writeTranslateCookie(language) { const translated = language === 'zh' ? 'zh-CN' : language; const value = `/id/${translated}`; document.cookie = `googtrans=${value}; path=/; max-age=31536000; SameSite=Lax`; if (location.hostname.includes('.')) document.cookie = `googtrans=${value}; path=/; domain=${location.hostname}; max-age=31536000; SameSite=Lax`; }
  function clearTranslateCookie() { const expiry = 'Thu, 01 Jan 1970 00:00:00 GMT'; document.cookie = `googtrans=; path=/; expires=${expiry}`; if (location.hostname.includes('.')) document.cookie = `googtrans=; path=/; domain=${location.hostname}; expires=${expiry}`; }
  function changeLanguage(language) { try { localStorage.setItem('gn_user_lang', language); } catch (_) {} language === 'id' ? clearTranslateCookie() : writeTranslateCookie(language); location.reload(); }
  window.googleTranslateElementInit = () => { try { new google.translate.TranslateElement({ pageLanguage: 'id', autoDisplay: false }, 'google_translate_element'); } catch (_) {} };
  function loadGoogleTranslate() { if (state.translateRequested || getCurrentLanguage() === 'id') return; state.translateRequested = true; const script = document.createElement('script'); script.src = 'https://translate.google.com/translate_a/element.js?cb=googleTranslateElementInit'; script.async = true; script.defer = true; document.head.appendChild(script); }
  function scheduleTranslationIfNeeded() { if (getCurrentLanguage() === 'id') return; 'requestIdleCallback' in window ? requestIdleCallback(loadGoogleTranslate, { timeout: 1800 }) : setTimeout(loadGoogleTranslate, 900); }

  function initModals() {
    qsa('[data-modal-open]').forEach((trigger) => trigger.addEventListener('click', () => openModal(trigger.dataset.modalOpen, trigger)));
    qsa('[data-modal-close]').forEach((trigger) => trigger.addEventListener('click', () => closeModal(trigger.closest('.modal-backdrop'))));
    qsa('.modal-backdrop').forEach((backdrop) => { backdrop.addEventListener('mousedown', (event) => { if (event.target === backdrop) closeModal(backdrop); }); backdrop.addEventListener('keydown', trapModalFocus); });
    document.addEventListener('keydown', (event) => { if (event.key === 'Escape') { const open = qs('.modal-backdrop:not([hidden])'); if (open) closeModal(open); } });
  }
  function openModal(name, trigger) { const backdrop = qs(`#modal-${name}`); if (!backdrop) return; state.modalReturnFocus = trigger || document.activeElement; backdrop.hidden = false; document.body.classList.add('modal-open'); requestAnimationFrame(() => qs('[data-modal-close],a[href],button:not([disabled])', backdrop)?.focus()); }
  function closeModal(backdrop) { if (!backdrop || backdrop.hidden) return; backdrop.hidden = true; document.body.classList.remove('modal-open'); if (state.modalReturnFocus instanceof HTMLElement) state.modalReturnFocus.focus({ preventScroll: true }); }
  function trapModalFocus(event) { if (event.key !== 'Tab') return; const focus = qsa('a[href],button:not([disabled])', event.currentTarget).filter((el) => el.offsetParent !== null); if (!focus.length) return; const first = focus[0], last = focus[focus.length - 1]; if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); } else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); } }

  function initDownloads() { qsa('[data-action="download"]').forEach((button) => button.addEventListener('click', triggerDownloadFlow)); }
  function triggerDownloadFlow(event) {
    event?.preventDefault?.();
    if (state.downloadTransitioning) return;
    state.downloadTransitioning = true;

    const config = window.GAMENUKE_CONFIG || {};
    const reviewMode = config.adsenseReviewMode === true;
    const sponsorDelay = Number(config.sponsorDelayMs) || 2000;
    const directLink = config.indexDirectlinkUrl || state.version.directlinkAdUrl || DEFAULT_VERSION.directlinkAdUrl;

    const auth = `gn_sess_${Date.now()}_${Math.random().toString(36).slice(2,9)}`;
    try {
      sessionStorage.setItem('gn_download_session', auth);
      sessionStorage.setItem('gn_session_timestamp', Date.now().toString());
    } catch (_) {}

    const downloadUrl = `download.html?auth=${encodeURIComponent(auth)}&src=portal`;
    let downloadTab = null;
    try { downloadTab = window.open(downloadUrl, '_blank'); } catch (_) {}

    // If the browser blocks the intentional new tab, keep the real download destination working.
    if (!downloadTab) {
      state.downloadTransitioning = false;
      window.location.assign(downloadUrl);
      return;
    }

    // Requested sponsor flow: the new tab remains on download.html, while the original tab
    // navigates to the INDEX sponsor destination after two seconds. Disabled in review mode.
    if (!reviewMode && directLink) {
      window.setTimeout(() => { window.location.assign(directLink); }, sponsorDelay);
    } else {
      window.setTimeout(() => { state.downloadTransitioning = false; }, 700);
    }
  }

  function initMobileActionbar() {
    const bar = qs('.mobile-actionbar');
    const heroButton = qs('.hero-actions [data-action="download"]');
    if (!bar || !heroButton) return;
    const mq = matchMedia('(max-width:820px)');
    let threshold = 560;
    let frame = 0;
    let inlineCtaVisible = false;
    const measure = () => { threshold = heroButton.getBoundingClientRect().bottom + scrollY + 150; };
    const update = () => {
      frame = 0;
      const footerNear = document.documentElement.scrollHeight - (scrollY + innerHeight) < 260;
      bar.classList.toggle('is-visible', mq.matches && scrollY > threshold && !footerNear && !inlineCtaVisible);
    };
    const request = () => { if (!frame) frame = requestAnimationFrame(update); };
    const inlineButtons = qsa('.edition-card [data-action="download"], .final-cta [data-action="download"]');
    if ('IntersectionObserver' in window && inlineButtons.length) {
      const visible = new Set();
      const observer = new IntersectionObserver((entries) => {
        entries.forEach((entry) => entry.isIntersecting ? visible.add(entry.target) : visible.delete(entry.target));
        inlineCtaVisible = visible.size > 0;
        request();
      }, { threshold: 0.35 });
      inlineButtons.forEach((button) => observer.observe(button));
    }
    measure();
    update();
    addEventListener('scroll', request, { passive:true });
    addEventListener('resize', () => { measure(); request(); }, { passive:true });
    mq.addEventListener?.('change', () => { measure(); update(); });
  }

  async function init() {
    const config = window.GAMENUKE_CONFIG || {};
    if (config.adsenseReviewMode === true) qsa('[data-sponsor-disclosure]').forEach((node) => { node.hidden = true; });
    initHeader(); initMobileMenu(); initReveal(); initActiveNavigation(); initLanguageMenu(); initModals(); initDownloads(); initMobileActionbar(); scheduleTranslationIfNeeded(); await loadVersion();
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init, { once:true }); else init();
})();
