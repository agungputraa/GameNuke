/**
 * Game Nuke Premium Edition — Enterprise Web Logic
 * Powered by Alpine.js, Tailwind CSS CDN & Edge CDN (Cloudflare)
 */

document.addEventListener('alpine:init', () => {
  // ── Main Game Nuke App Store & Controller ──────────────────────────────────
  Alpine.data('gameNukeApp', () => ({
    mobileMenuOpen: false,
    downloadState: 'idle', // 'idle' | 'downloading' | 'completed'
    downloadProgress: 0,
    meta: {
      versionName: '2.2.0-prem',
      apkSizeMb: '24.3',
      publishedAt: '2026-09-04',
      downloadUrl: 'https://github.com/agungputraa/GameNuke/releases/download/v2.2.0-prem/GameNuke-Premium-v2.2.0.apk',
      sha256: 'AEFF9DF9F675CF5F20DA0F92939C24AC4CC993BA743411092DE3525F1915EC9B',
      directlinkAdUrl: 'https://example-directlink-ad.com/?ref=gamenuke'
    },
    liveTelemetry: {
      ping: 1,
      fps: 120,
      macroLatency: 0.1
    },

    async init() {
      await this.fetchVersionMetadata();
      this.startTelemetryLoop();
    },

    async fetchVersionMetadata() {
      try {
        const cacheBuster = `?t=${Date.now()}`;
        const res = await fetch(`version.json${cacheBuster}`);
        if (res.ok) {
          const data = await res.json();
          this.meta = {
            ...this.meta,
            ...data
          };
        }
      } catch (err) {
        console.warn('Using default version metadata (local or offline preview):', err);
      }
    },

    startTelemetryLoop() {
      setInterval(() => {
        // High-precision live simulation: locked 120 FPS with 1% micro-jitter
        this.liveTelemetry.fps = Math.random() > 0.96 ? 119 : 120;
        // 1ms ping with rare 2ms spike simulation
        this.liveTelemetry.ping = Math.random() > 0.98 ? 2 : 1;
        // Macro touch injection 0.1ms
        this.liveTelemetry.macroLatency = (0.1 + (Math.random() * 0.05)).toFixed(2);
      }, 1400);
    },

    scrollToDownload() {
      const el = document.getElementById('download-zone');
      if (el) {
        el.scrollIntoView({ behavior: 'smooth', block: 'center' });
      }
    },

    copySha256() {
      if (navigator.clipboard) {
        navigator.clipboard.writeText(this.meta.sha256);
        alert(`SHA256 Hash Disalin:\n${this.meta.sha256}`);
      }
    },

    handleAdClick() {
      if (this.meta.directlinkAdUrl) {
        window.open(this.meta.directlinkAdUrl, '_blank');
      }
    },

    async startDownloadProcess() {
      if (this.downloadState === 'downloading') return;
      this.downloadState = 'downloading';
      this.downloadProgress = 10;

      // 1. Trigger Monetization Directlink in background tab
      this.handleAdClick();

      // 2. Animate masked streaming download
      const apkName = `GameNuke-Premium-v${this.meta.versionName}.apk`;
      const targetUrl = this.meta.downloadUrl;

      try {
        // Attempt CORS blob streaming if permitted
        const progressTimer = setInterval(() => {
          if (this.downloadProgress < 90) {
            this.downloadProgress += Math.floor(Math.random() * 15) + 8;
          }
        }, 180);

        const response = await fetch(targetUrl, { mode: 'cors' }).catch(() => null);

        if (response && response.ok) {
          const blob = await response.blob();
          clearInterval(progressTimer);
          this.downloadProgress = 100;

          // Masked in-memory blob download URL
          const blobUrl = window.URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.style.display = 'none';
          a.href = blobUrl;
          a.download = apkName;
          document.body.appendChild(a);
          a.click();

          setTimeout(() => {
            window.URL.revokeObjectURL(blobUrl);
            a.remove();
            this.downloadState = 'completed';
            setTimeout(() => { this.downloadState = 'idle'; }, 4000);
          }, 1200);

        } else {
          // Fallback direct download link (standard browser stream)
          clearInterval(progressTimer);
          this.downloadProgress = 100;

          const a = document.createElement('a');
          a.href = targetUrl;
          a.download = apkName;
          a.target = '_blank';
          document.body.appendChild(a);
          a.click();
          a.remove();

          setTimeout(() => {
            this.downloadState = 'completed';
            setTimeout(() => { this.downloadState = 'idle'; }, 3000);
          }, 1000);
        }

      } catch (e) {
        console.warn('Fallback standard download:', e);
        this.downloadProgress = 100;
        window.location.href = targetUrl;
        setTimeout(() => { this.downloadState = 'idle'; }, 2000);
      }
    }
  }));

  // ── Interactive Macro Speed Lab ───────────────────────────────────────────
  Alpine.data('macroClickerLab', () => ({
    currentMode: 'shizuku', // 'human' | 'accessibility' | 'shizuku'
    totalTaps: 0,
    latencyDisplay: '0.1ms',
    cpsDisplay: '0 CPS',
    ripples: [],
    tapTimes: [],

    setMode(mode) {
      this.currentMode = mode;
      if (mode === 'human') this.latencyDisplay = '85.4ms';
      else if (mode === 'accessibility') this.latencyDisplay = '34.8ms';
      else this.latencyDisplay = '0.1ms';
    },

    triggerTap(e) {
      const now = performance.now();
      this.tapTimes.push(now);
      this.tapTimes = this.tapTimes.filter(t => now - t <= 1000);

      // Multi-tap combo simulation depending on mode
      const comboCount = this.currentMode === 'shizuku' ? 5 : (this.currentMode === 'accessibility' ? 3 : 1);
      this.totalTaps += comboCount;

      // Calculate simulated CPS
      if (this.currentMode === 'shizuku') {
        this.cpsDisplay = `${Math.min(99, this.tapTimes.length * 12)} CPS`;
        this.latencyDisplay = `${(0.08 + Math.random() * 0.04).toFixed(2)}ms`;
      } else if (this.currentMode === 'accessibility') {
        this.cpsDisplay = `${Math.min(30, this.tapTimes.length * 4)} CPS`;
        this.latencyDisplay = `${(32.0 + Math.random() * 6).toFixed(1)}ms`;
      } else {
        this.cpsDisplay = `${Math.min(12, this.tapTimes.length)} CPS`;
        this.latencyDisplay = `${(75.0 + Math.random() * 25).toFixed(1)}ms`;
      }

      // Add visual ripple
      const rect = (e?.currentTarget || document.body).getBoundingClientRect();
      const x = (e?.clientX || (rect.left + rect.width / 2)) - rect.left - 16;
      const y = (e?.clientY || (rect.top + rect.height / 2)) - rect.top - 16;

      const rippleId = Date.now() + Math.random();
      this.ripples.push({ id: rippleId, x, y });
      setTimeout(() => {
        this.ripples = this.ripples.filter(r => r.id !== rippleId);
      }, 600);
    }
  }));

  // ── Interactive VPN Ping Lab ──────────────────────────────────────────────
  Alpine.data('vpnPingLab', () => ({
    isBoosterActive: true,
    normalPing: 78,
    nukePing: 1,
    pingInterval: null,

    init() {
      this.startPingSimulation();
    },

    startPingSimulation() {
      this.pingInterval = setInterval(() => {
        // Normal network ping fluctuates widely
        this.normalPing = Math.floor(65 + Math.random() * 35 + (Math.random() > 0.85 ? 40 : 0));
        // Game Nuke Loopback responder is locked at 1ms with sub-micro jitter
        this.nukePing = Math.random() > 0.97 ? 2 : 1;
      }, 1000);
    },

    toggleBooster() {
      this.isBoosterActive = !this.isBoosterActive;
    }
  }));
});
