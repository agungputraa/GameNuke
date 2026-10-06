(() => {
  'use strict';

  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const saveData = Boolean(navigator.connection && navigator.connection.saveData);
  if (reducedMotion || saveData) return;

  class ParticleField {
    constructor(canvas, options = {}) {
      this.canvas = canvas;
      this.ctx = canvas.getContext('2d', { alpha: true });
      this.options = Object.assign({
        density: 22000,
        maxParticles: 50,
        mobileMax: 22,
        linkDistance: 108,
        pointerDistance: 145,
        speed: 0.13,
        opacity: 0.24,
        lineOpacity: 0.06,
        fps: 30,
        pointer: true
      }, options);
      this.particles = [];
      this.pointer = { x: -9999, y: -9999, active: false };
      this.width = 0;
      this.height = 0;
      this.dpr = 1;
      this.last = 0;
      this.frameInterval = 1000 / this.options.fps;
      this.visible = true;
      this.running = false;
      this.resizeTimer = 0;
      this.resize = this.resize.bind(this);
      this.animate = this.animate.bind(this);
      this.handlePointer = this.handlePointer.bind(this);
      this.init();
    }

    init() {
      if (!this.ctx) return;
      this.resize();
      window.addEventListener('resize', () => {
        clearTimeout(this.resizeTimer);
        this.resizeTimer = window.setTimeout(this.resize, 120);
      }, { passive: true });
      if (this.options.pointer && innerWidth >= 1000 && matchMedia('(pointer:fine)').matches) {
        this.canvas.closest('section')?.addEventListener('pointermove', this.handlePointer, { passive: true });
        this.canvas.closest('section')?.addEventListener('pointerleave', () => { this.pointer.active = false; }, { passive: true });
      }
      document.addEventListener('visibilitychange', () => {
        this.visible = !document.hidden && this.inViewport !== false;
        if (this.visible && !this.running) requestAnimationFrame(this.animate);
      });
      if ('IntersectionObserver' in window) {
        this.inViewport = true;
        this.visibilityObserver = new IntersectionObserver((entries) => {
          this.inViewport = entries[0]?.isIntersecting ?? true;
          this.visible = this.inViewport && !document.hidden;
          if (this.visible && !this.running) requestAnimationFrame(this.animate);
        }, { rootMargin: '160px' });
        this.visibilityObserver.observe(this.canvas);
      }
      this.running = true;
      requestAnimationFrame(this.animate);
    }

    resize() {
      const rect = this.canvas.getBoundingClientRect();
      this.width = Math.max(1, rect.width);
      this.height = Math.max(1, rect.height);
      this.dpr = Math.min(window.devicePixelRatio || 1, this.width < 700 ? 1.15 : 1.4);
      this.canvas.width = Math.round(this.width * this.dpr);
      this.canvas.height = Math.round(this.height * this.dpr);
      this.ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
      const isMobile = this.width < 700;
      const desired = Math.min(
        isMobile ? this.options.mobileMax : this.options.maxParticles,
        Math.max(18, Math.round((this.width * this.height) / this.options.density))
      );
      this.particles = Array.from({ length: desired }, () => this.makeParticle());
    }

    makeParticle() {
      const angle = Math.random() * Math.PI * 2;
      const speed = this.options.speed * (0.45 + Math.random() * 0.8);
      return {
        x: Math.random() * this.width,
        y: Math.random() * this.height,
        vx: Math.cos(angle) * speed,
        vy: Math.sin(angle) * speed,
        r: 0.55 + Math.random() * 1.05,
        alpha: this.options.opacity * (0.5 + Math.random() * 0.6)
      };
    }

    handlePointer(event) {
      const rect = this.canvas.getBoundingClientRect();
      this.pointer.x = event.clientX - rect.left;
      this.pointer.y = event.clientY - rect.top;
      this.pointer.active = true;
    }

    update(p) {
      p.x += p.vx;
      p.y += p.vy;
      if (p.x < -10) p.x = this.width + 10;
      if (p.x > this.width + 10) p.x = -10;
      if (p.y < -10) p.y = this.height + 10;
      if (p.y > this.height + 10) p.y = -10;
      if (this.pointer.active) {
        const dx = p.x - this.pointer.x;
        const dy = p.y - this.pointer.y;
        const d2 = dx * dx + dy * dy;
        const max = this.options.pointerDistance;
        if (d2 < max * max && d2 > 1) {
          const d = Math.sqrt(d2);
          const force = (1 - d / max) * 0.022;
          p.x += (dx / d) * force * 16;
          p.y += (dy / d) * force * 16;
        }
      }
    }

    draw() {
      const ctx = this.ctx;
      ctx.clearRect(0, 0, this.width, this.height);
      for (let i = 0; i < this.particles.length; i++) {
        const a = this.particles[i];
        this.update(a);
        ctx.beginPath();
        ctx.arc(a.x, a.y, a.r, 0, Math.PI * 2);
        ctx.fillStyle = `rgba(99,255,181,${a.alpha})`;
        ctx.fill();
        for (let j = i + 1; j < this.particles.length; j++) {
          const b = this.particles[j];
          const dx = a.x - b.x;
          const dy = a.y - b.y;
          const d2 = dx * dx + dy * dy;
          const max = this.options.linkDistance;
          if (d2 < max * max) {
            const alpha = this.options.lineOpacity * (1 - Math.sqrt(d2) / max);
            ctx.beginPath();
            ctx.moveTo(a.x, a.y);
            ctx.lineTo(b.x, b.y);
            ctx.strokeStyle = `rgba(99,255,181,${alpha})`;
            ctx.lineWidth = 0.7;
            ctx.stroke();
          }
        }
      }
    }

    animate(now) {
      if (!this.visible) { this.running = false; return; }
      this.running = true;
      if (now - this.last >= this.frameInterval) {
        this.last = now;
        this.draw();
      }
      requestAnimationFrame(this.animate);
    }
  }

  function bootParticles() {
    const hero = document.getElementById('particle-canvas');
    if (hero) new ParticleField(hero, { fps: innerWidth < 700 ? 24 : 30 });

    const cta = document.getElementById('cta-particles');
    if (!cta) return;
    const createCta = () => new ParticleField(cta, { maxParticles: 24, mobileMax: 12, density: 28000, linkDistance: 92, pointer: false, fps: 22, opacity: 0.16, lineOpacity: 0.045, speed: 0.09 });
    if (!('IntersectionObserver' in window)) { createCta(); return; }
    const observer = new IntersectionObserver((entries) => {
      if (!entries.some((entry) => entry.isIntersecting)) return;
      observer.disconnect();
      createCta();
    }, { rootMargin: '500px' });
    observer.observe(cta);
  }

  if ('requestIdleCallback' in window) requestIdleCallback(bootParticles, { timeout: 900 });
  else window.setTimeout(bootParticles, 420);
})();
