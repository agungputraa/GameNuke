/* Optional decoration. No work on phones, low-resource devices, or hidden tabs. */
(() => {
  'use strict';
  const motion = matchMedia('(prefers-reduced-motion: reduce)');
  const desktop = matchMedia('(min-width: 901px)');
  const connection = navigator.connection;
  if (connection?.saveData || /(^|-)2g$/.test(connection?.effectiveType || '') ||
      (navigator.deviceMemory && navigator.deviceMemory <= 4) ||
      (navigator.hardwareConcurrency && navigator.hardwareConcurrency <= 4)) return;

  class Field {
    constructor(canvas) {
      this.canvas = canvas;
      this.ctx = canvas.getContext('2d', { alpha: true });
      this.near = false;
      this.timer = 0;
      this.frame = 0;
      if (!this.ctx) return;
      this.resize();
      this.sync = this.sync.bind(this);
      this.paint = this.paint.bind(this);
      if ('IntersectionObserver' in window) {
        this.observer = new IntersectionObserver(([entry]) => {
          this.near = entry.isIntersecting;
          this.sync();
        }, { rootMargin: '80px' });
        this.observer.observe(canvas);
      } else {
        this.near = true;
        this.sync();
      }
      let resizeTimer;
      addEventListener('resize', () => {
        clearTimeout(resizeTimer);
        resizeTimer = setTimeout(() => { this.resize(); this.sync(); }, 180);
      }, { passive: true });
      document.addEventListener('visibilitychange', this.sync);
      addEventListener('pagehide', () => this.stop());
      addEventListener('pageshow', this.sync);
      motion.addEventListener?.('change', this.sync);
      desktop.addEventListener?.('change', this.sync);
    }
    allowed() { return this.near && !document.hidden && !motion.matches && desktop.matches; }
    stop() {
      clearTimeout(this.timer);
      cancelAnimationFrame(this.frame);
      this.timer = this.frame = 0;
    }
    sync() {
      this.stop();
      if (this.allowed()) this.frame = requestAnimationFrame(this.paint);
      else this.ctx.clearRect(0, 0, this.width, this.height);
    }
    resize() {
      const rect = this.canvas.getBoundingClientRect();
      this.width = Math.max(1, rect.width);
      this.height = Math.max(1, rect.height);
      const dpr = Math.min(devicePixelRatio || 1, 1.25);
      this.canvas.width = Math.round(this.width * dpr);
      this.canvas.height = Math.round(this.height * dpr);
      this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      const count = Math.min(26, Math.max(10, Math.round(this.width * this.height / 42000)));
      this.points = Array.from({ length: count }, () => ({
        x: Math.random() * this.width, y: Math.random() * this.height,
        dx: (Math.random() - .5) * .25, dy: (Math.random() - .5) * .25
      }));
    }
    paint() {
      this.frame = 0;
      if (!this.allowed()) { this.stop(); return; }
      const ctx = this.ctx;
      ctx.clearRect(0, 0, this.width, this.height);
      ctx.fillStyle = 'rgba(118,244,187,.3)';
      ctx.lineWidth = .7;
      for (const point of this.points) {
        point.x = (point.x + point.dx + this.width) % this.width;
        point.y = (point.y + point.dy + this.height) % this.height;
        ctx.beginPath(); ctx.arc(point.x, point.y, 1, 0, Math.PI * 2); ctx.fill();
      }
      for (let i = 0; i < this.points.length; i++) {
        for (let j = i + 1; j < this.points.length; j++) {
          const a = this.points[i], b = this.points[j];
          const distance = Math.hypot(a.x - b.x, a.y - b.y);
          if (distance >= 110) continue;
          ctx.strokeStyle = `rgba(118,244,187,${.06 * (1 - distance / 110)})`;
          ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke();
        }
      }
      // Schedule one animation frame per 50 ms rather than waking at screen refresh rate.
      this.timer = setTimeout(() => {
        this.timer = 0;
        if (this.allowed()) this.frame = requestAnimationFrame(this.paint);
      }, 50);
    }
  }
  function boot() {
    if (motion.matches || !desktop.matches) return;
    document.querySelectorAll('#particle-canvas, #cta-particles').forEach(canvas => new Field(canvas));
  }
  if ('requestIdleCallback' in window) requestIdleCallback(boot, { timeout: 1600 });
  else setTimeout(boot, 700);
})();
