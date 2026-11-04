const cv = document.getElementById('c'); const g = cv.getContext('2d');
function size() { cv.width = innerWidth * devicePixelRatio; cv.height = innerHeight * devicePixelRatio; } size(); addEventListener('resize', size);
let target = 0, a = 0, angle = 0, level = 0, sm = 0, hl = null, hlUntil = 0, hlAlpha = 0, t = 0;
let style = { glowOn: true, glowWidth: 2, glowIntensity: 80, glowSpeed: 2 };
window.api.on('state', (s) => { if (s.style) style = s.style; target = s.mode === 'idle' || s.mode === 'info' || !s.mode || style.glowOn === false ? 0 : 1; });
window.api.on('level', (v) => { level = v; });
window.api.on('highlight', (d) => { hl = d.items; hlUntil = performance.now() + (d.seconds || 10) * 1000; });
function frame(now) {
  requestAnimationFrame(frame); const W = cv.width, H = cv.height, d = devicePixelRatio; g.clearRect(0, 0, W, H); t += 0.09;
  angle = (angle + 0.012 * ({ 1: 0.5, 2: 1, 3: 1.8 }[style.glowSpeed] || 1)) % (Math.PI * 2); sm += (level - sm) * 0.25; a += (target - a) * 0.1;
  const ac = window.__accent || { accent: '#8899ff', a2: '#aa88ff', a3: '#88aaff' };
  if (a > 0.01) {
    const grd = g.createConicGradient(angle, W / 2, H / 2); grd.addColorStop(0, ac.accent); grd.addColorStop(0.33, ac.a2); grd.addColorStop(0.66, ac.a3); grd.addColorStop(1, ac.accent);
    const wm = { 1: 0.6, 2: 1, 3: 1.5 }[style.glowWidth] || 1, inten = (style.glowIntensity == null ? 80 : style.glowIntensity) / 80; const layers = [34, 22, 12, 5, 2.2].map((x) => x * wm), alphas = [0.1, 0.18, 0.32, 0.6, 1].map((x) => x * inten), pulse = 0.75 + 0.25 * sm + 0.1 * Math.sin(angle * 8);
    g.strokeStyle = grd; for (let i = 0; i < layers.length; i++) { g.lineWidth = layers[i] * d * (0.85 + 0.3 * sm); g.globalAlpha = Math.min(1, alphas[i] * a * pulse); g.beginPath(); g.roundRect(0, 0, W, H, 46 * d); g.stroke(); } g.globalAlpha = 1;
  }
  hlAlpha += ((hl && performance.now() < hlUntil ? 1 : 0) - hlAlpha) * 0.15; if (hlAlpha < 0.02) { if (!hl || performance.now() >= hlUntil) hl = null; return; }
  g.save(); g.globalAlpha = hlAlpha; g.fillStyle = 'rgba(0,0,0,.5)'; g.fillRect(0, 0, W, H); g.globalCompositeOperation = 'destination-out'; const pad = 6 * d;
  for (const it of hl) { g.beginPath(); g.roundRect(it.x * d - pad, it.y * d - pad, it.w * d + 2 * pad, it.h * d + 2 * pad, 14 * d); g.fill(); } g.restore(); g.save(); g.globalAlpha = hlAlpha;
  const pulse = 0.5 + 0.5 * Math.sin(t);
  for (const it of hl) {
    const x = it.x * d - pad, y = it.y * d - pad, w = it.w * d + 2 * pad, h = it.h * d + 2 * pad; g.lineWidth = 9 * d; g.strokeStyle = ac.a2; g.globalAlpha = hlAlpha * (0.25 + 0.25 * pulse); g.beginPath(); g.roundRect(x, y, w, h, 14 * d); g.stroke();
    g.globalAlpha = hlAlpha; g.lineWidth = 3 * d; g.strokeStyle = ac.accent; g.beginPath(); g.roundRect(x, y, w, h, 14 * d); g.stroke();
    if (it.label) { g.font = `${15 * d}px sans-serif`; const tw = Math.min(W * 0.6, g.measureText(it.label).width + 28 * d), th = 34 * d; let bx = Math.max(12 * d, Math.min(W - tw - 12 * d, x + w / 2 - tw / 2)), by = y + h + 10 * d; if (by + th > H - 20 * d) by = y - th - 10 * d; g.fillStyle = ac.accent; g.beginPath(); g.roundRect(bx, by, tw, th, 17 * d); g.fill(); g.fillStyle = '#000'; g.textBaseline = 'middle'; g.fillText(it.label, bx + 14 * d, by + th / 2, tw - 20 * d); }
  }
  g.restore();
}
requestAnimationFrame(frame);
