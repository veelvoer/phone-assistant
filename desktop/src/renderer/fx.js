const cv = document.getElementById('c'); const g = cv.getContext('2d');
function size() { cv.width = innerWidth * devicePixelRatio; cv.height = innerHeight * devicePixelRatio; } size(); addEventListener('resize', size);
let target = 0, a = 0, angle = 0, level = 0, sm = 0, hl = null, hlUntil = 0, hlAlpha = 0, t = 0;
let style = { glowOn: true, glowWidth: 2, glowIntensity: 80, glowSpeed: 2, glowMode: 'full', glowSoft: 50, glowRadius: 46, accent: '', accent2: '' };
window.api.on('state', (s) => { if (s.style) style = s.style; target = s.mode === 'idle' || s.mode === 'info' || !s.mode || style.glowOn === false ? 0 : 1; });
window.api.on('level', (v) => { level = v; });
window.api.on('highlight', (d) => { hl = d.items; hlUntil = performance.now() + (d.seconds || 10) * 1000; });

function glowLayers(W, H, d, ac, inten, wm, soft, pulse) {
  const grd = g.createConicGradient(angle, W / 2, H / 2);
  const cols = style.accent && style.accent2 ? [style.accent, style.accent2, style.accent, style.accent2, style.accent] : [ac.accent, ac.a2, ac.a3, ac.a2, ac.accent];
  cols.forEach((c, i) => grd.addColorStop(i / (cols.length - 1), c));
  const layers = [34 * soft, 22 * soft, 12, 5, 2.2].map((x) => x * wm), alphas = [0.1, 0.18, 0.32, 0.6, 1].map((x) => x * inten), R = (style.glowRadius == null ? 46 : style.glowRadius) * d;
  g.strokeStyle = grd; for (let i = 0; i < layers.length; i++) { g.lineWidth = layers[i] * d * (0.85 + 0.3 * sm); g.globalAlpha = Math.min(1, alphas[i] * a * pulse); g.beginPath(); g.roundRect(0, 0, W, H, R); g.stroke(); }
}
function frame() {
  requestAnimationFrame(frame); const W = cv.width, H = cv.height, d = devicePixelRatio; g.clearRect(0, 0, W, H); t += 0.09;
  angle = (angle + 0.012 * ({ 1: 0.5, 2: 1, 3: 1.8 }[style.glowSpeed] || 1)) % (Math.PI * 2); sm += (level - sm) * 0.25; a += (target - a) * 0.1;
  const ac = window.__accent || { accent: '#8899ff', a2: '#aa88ff', a3: '#88aaff' };
  if (a > 0.01) {
    const wm = { 1: 0.6, 2: 1, 3: 1.5 }[style.glowWidth] || 1, inten = (style.glowIntensity == null ? 80 : style.glowIntensity) / 80, soft = 0.5 + (style.glowSoft == null ? 50 : style.glowSoft) / 100, pulse = 0.75 + 0.25 * sm + 0.1 * Math.sin(angle * 8);
    const clips = { bottom: [[0, H * 0.55, W, H * 0.45]], top: [[0, 0, W, H * 0.45]], sides: [[0, 0, W * 0.16, H], [W * 0.84, 0, W * 0.16, H]] }[style.glowMode];
    if (clips) for (const c of clips) { g.save(); g.beginPath(); g.rect(...c); g.clip(); glowLayers(W, H, d, ac, inten, wm, soft, pulse); g.restore(); } else glowLayers(W, H, d, ac, inten, wm, soft, pulse);
    g.globalAlpha = 1;
  }
  hlAlpha += ((hl && performance.now() < hlUntil ? 1 : 0) - hlAlpha) * 0.15; if (hlAlpha < 0.02) { if (!hl || performance.now() >= hlUntil) hl = null; return; }
  g.save(); g.globalAlpha = hlAlpha; g.fillStyle = 'rgba(0,0,0,.5)'; g.fillRect(0, 0, W, H); g.globalCompositeOperation = 'destination-out'; const pad = 6 * d;
  for (const it of hl) { g.beginPath(); g.roundRect(it.x * d - pad, it.y * d - pad, it.w * d + 2 * pad, it.h * d + 2 * pad, 14 * d); g.fill(); } g.restore(); g.save(); g.globalAlpha = hlAlpha;
  const pulse2 = 0.5 + 0.5 * Math.sin(t);
  for (const it of hl) {
    const x = it.x * d - pad, y = it.y * d - pad, w = it.w * d + 2 * pad, h = it.h * d + 2 * pad; g.lineWidth = 9 * d; g.strokeStyle = ac.a2; g.globalAlpha = hlAlpha * (0.25 + 0.25 * pulse2); g.beginPath(); g.roundRect(x, y, w, h, 14 * d); g.stroke();
    g.globalAlpha = hlAlpha; g.lineWidth = 3 * d; g.strokeStyle = ac.accent; g.beginPath(); g.roundRect(x, y, w, h, 14 * d); g.stroke();
    if (it.label) { g.font = `${15 * d}px sans-serif`; const tw = Math.min(W * 0.6, g.measureText(it.label).width + 28 * d), th = 34 * d; let bx = Math.max(12 * d, Math.min(W - tw - 12 * d, x + w / 2 - tw / 2)), by = y + h + 10 * d; if (by + th > H - 20 * d) by = y - th - 10 * d; g.fillStyle = ac.accent; g.beginPath(); g.roundRect(bx, by, tw, th, 17 * d); g.fill(); g.fillStyle = '#000'; g.textBaseline = 'middle'; g.fillText(it.label, bx + 14 * d, by + th / 2, tw - 20 * d); }
  }
  g.restore();
}
requestAnimationFrame(frame);

// ============================================================ drawing on the screen
// The assistant's SVG is drawn live over the (dimmed) screen: each stroke is traced one after another with a pen cursor, text is typed.
const $ = (id) => document.getElementById(id);
const D = { frames: [], i: 0, auto: true, items: [], t0: 0, total: 0, raf: 0, timer: 0, title: '', fired: false };
function adapt(c) {   // the model picks colours for a light page: lift dark ones so they read on the dimmed screen
  if (!c || c === 'none' || c.startsWith('url(')) return c; const m = /^#?([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(c.trim()) || null; let r, g2, b;
  if (m) { let h = m[1]; if (h.length === 3) h = h.split('').map((x) => x + x).join(''); r = parseInt(h.slice(0, 2), 16); g2 = parseInt(h.slice(2, 4), 16); b = parseInt(h.slice(4, 6), 16); }
  else { const n = { black: [0, 0, 0], white: [255, 255, 255], red: [229, 57, 53], green: [67, 160, 71], blue: [30, 136, 229], yellow: [253, 216, 53], orange: [251, 140, 0], purple: [142, 36, 170], gray: [158, 158, 158], grey: [158, 158, 158], pink: [236, 64, 122], teal: [0, 137, 123], navy: [26, 35, 126] }[c.trim().toLowerCase()]; if (!n) return c; [r, g2, b] = n; }
  const mx = Math.max(r, g2, b), mn = Math.min(r, g2, b), sat = mx ? (mx - mn) / mx : 0;
  if (sat < 0.18) return mx < 190 ? '#ffffff' : c; const k = 235 / mx; return `rgb(${Math.min(255, Math.round(r * k))},${Math.min(255, Math.round(g2 * k))},${Math.min(255, Math.round(b * k))})`;
}
function lenOf(el) { try { return el.getTotalLength ? el.getTotalLength() : 0; } catch { return 0; } }
function build(frame) {
  const sv = $('sv'); sv.innerHTML = frame.svg; const svg = sv.querySelector('svg'); if (!svg) return false; svg.setAttribute('preserveAspectRatio', 'xMidYMid meet'); svg.removeAttribute('width'); svg.removeAttribute('height');
  const items = []; let at = 200;
  for (const el of svg.querySelectorAll('path,line,polyline,polygon,rect,circle,ellipse,text')) {
    if (el.closest('defs,marker,clipPath,mask,symbol')) continue;
    if (el.tagName.toLowerCase() === 'text') { const full = el.textContent; if (!full.trim()) continue; const f = el.getAttribute('fill'); el.setAttribute('fill', adapt(f || '#fff') || '#fff'); const dur = Math.max(300, Math.min(1600, full.length * 38)); items.push({ el, kind: 'text', full, start: at, dur }); el.textContent = ''; at += dur + 60; continue; }
    const cs = getComputedStyle(el); const stroke = el.getAttribute('stroke') || (cs.stroke && cs.stroke !== 'none' ? cs.stroke : null); let fill = el.hasAttribute('fill') ? el.getAttribute('fill') : null; if (fill === null && !el.closest('[fill]')) fill = '#000'; else if (fill === null) fill = el.closest('[fill]').getAttribute('fill');
    const sCol = stroke && stroke !== 'none' ? adapt(stroke) : null, fCol = fill && fill !== 'none' ? adapt(fill) : null; if (!sCol && !fCol) continue;
    const len = lenOf(el), marker = el.getAttribute('marker-end'); const dur = Math.max(260, Math.min(1500, len / 1.1 || 400));
    el.setAttribute('stroke', sCol || fCol); if (!el.getAttribute('stroke-width')) el.setAttribute('stroke-width', 2.5); el.setAttribute('fill', fCol || 'none'); if (marker) el.removeAttribute('marker-end');
    if (len > 0) { el.style.strokeDasharray = len; el.style.strokeDashoffset = len; } el.style.fillOpacity = 0;
    items.push({ el, kind: 'shape', len, start: at, dur, sCol, fCol, marker }); at += dur + (fCol ? 180 : 0) + 50;
  }
  D.items = items; D.total = at + 300; return true;
}
function place(el, len, p) {   // pen cursor position for a point along a shape
  try { const svg = el.ownerSVGElement; const pt = svg.createSVGPoint(); const q = el.getPointAtLength(len * p); pt.x = q.x; pt.y = q.y; const s = pt.matrixTransform(el.getScreenCTM()); const c = $('cur'); c.style.left = s.x - 2 + 'px'; c.style.top = s.y - 2 + 'px'; c.style.opacity = 1; } catch { /* no geometry (tests) */ }
}
function tick(now) {
  const tt = now - D.t0; let head = null;
  for (const it of D.items) {
    if (tt < it.start) continue; const p = Math.max(0, Math.min(1, (tt - it.start) / it.dur));
    if (it.kind === 'text') { it.el.textContent = it.full.slice(0, Math.max(1, Math.round(it.full.length * p))); if (p < 1) { try { const b = it.el.getBBox(); const pt = it.el.ownerSVGElement.createSVGPoint(); pt.x = b.x + b.width; pt.y = b.y + b.height * 0.7; const s = pt.matrixTransform(it.el.getScreenCTM()); const c = $('cur'); c.style.left = s.x - 2 + 'px'; c.style.top = s.y - 2 + 'px'; c.style.opacity = 1; } catch { /* no geometry */ } head = it; } continue; }
    if (it.len > 0) it.el.style.strokeDashoffset = it.len * (1 - p);
    if (p >= 1) { const f = Math.max(0, Math.min(1, (tt - it.start - it.dur) / 250)); it.el.style.fillOpacity = it.fCol ? f : 0; if (it.marker) it.el.setAttribute('marker-end', it.marker); if (!it.sCol && f >= 1) it.el.setAttribute('stroke', 'none'); }
    else { if (it.len > 0) place(it.el, it.len, p); head = it; }
  }
  if (!head && tt > D.total - 300) $('cur').style.opacity = 0;
  if (tt > D.total - 200) $('dcap').style.opacity = 1;
  if (tt > D.total + 350 && !D.fired) { D.fired = true; const f = D.frames[D.i]; D.timer = setTimeout(() => { if (D.auto && D.i < D.frames.length - 1) play(D.i + 1); else D.timer = setTimeout(stopDraw, (style.hold || 5) * 2000); }, (style.hold || 5) * 1000 + (f.caption || '').length * 45); }
  if (tt <= D.total + 400) D.raf = requestAnimationFrame(() => tick(performance.now()));
}
function play(i) {
  cancelAnimationFrame(D.raf); clearTimeout(D.timer); D.i = Math.max(0, Math.min(D.frames.length - 1, i)); D.fired = false;
  const f = D.frames[D.i]; if (!build(f)) return; $('draw').classList.add('on'); $('dtitle').textContent = D.i === 0 ? D.title : ''; $('dcap').textContent = f.caption || ''; $('dcap').style.opacity = 0; $('cur').style.opacity = 0;
  const ac = window.__accent && window.__accent.accent; if (ac) $('draw').style.setProperty('--ca', ac);
  D.t0 = performance.now(); window.api.send('draw:status', { i: D.i, n: D.frames.length }); D.raf = requestAnimationFrame(() => tick(performance.now()));
}
function stopDraw() { cancelAnimationFrame(D.raf); clearTimeout(D.timer); $('draw').classList.remove('on'); setTimeout(() => { if (!$('draw').classList.contains('on')) $('sv').innerHTML = ''; }, 400); D.frames = []; window.api.send('draw:status', null); }
window.api.on('draw', (d) => { D.frames = d.frames || []; D.title = d.title || ''; D.auto = true; if (D.frames.length) play(0); });
window.api.on('draw:ctl', (c) => { if (!D.frames.length) return; if (c === 'close') return stopDraw(); D.auto = false; if (c === 'finish') { D.t0 = performance.now() - D.total; tick(performance.now()); return; } play(D.i + (c === 'next' ? 1 : -1)); });
window.__fx = { tick, D, play, build, adapt };   // test hooks
