const $ = (id) => document.getElementById(id); window.addEventListener('DOMContentLoaded', () => { $('mic').innerHTML = ICON.mic; $('close').innerHTML = ICON.close; }); const cv = $('wave'); const g = cv.getContext('2d'); let mode = 'idle', level = 0, sm = 0, ph = 0;
let wstyle = 'bars', wcount = 4;
function draw() {
  requestAnimationFrame(draw); ph += 0.16; sm += (level - sm) * 0.25; const W = cv.width, H = cv.height; g.clearRect(0, 0, W, H);
  const a = window.__accent || { accent: '#88f', a2: '#a8f', a3: '#8af' }; const grd = g.createLinearGradient(0, 0, W, 0); grd.addColorStop(0, a.accent); grd.addColorStop(.5, a.a2); grd.addColorStop(1, a.a3); g.fillStyle = grd; g.strokeStyle = grd;
  const amp = mode === 'listening' ? 0.25 + 0.75 * sm : mode === 'asking' ? 0.3 + 0.6 * sm : mode === 'working' ? 0.45 : 0.15, n = Math.max(3, Math.min(7, wcount)), sp = mode === 'working' ? 1.4 : 1;
  if (wstyle === 'dots') { const slot = W / n, r = slot * 0.28; for (let i = 0; i < n; i++) { const k = 0.5 + 0.5 * Math.sin(ph * sp + i * 1.1); g.beginPath(); g.arc(slot * (i + 0.5), H / 2 - amp * k * H * 0.28, r * (0.7 + 0.6 * amp * k), 0, 7); g.fill(); } }
  else if (wstyle === 'ring') { const k = 0.5 + 0.5 * Math.sin(ph * sp); g.lineWidth = 6; g.beginPath(); g.arc(W / 2, H / 2, W * (0.22 + 0.2 * amp * k), 0, 7); g.stroke(); g.beginPath(); g.arc(W / 2, H / 2, W * (0.09 + 0.07 * amp), 0, 7); g.fill(); }
  else { const bw = W / (n * 2), gap = bw; for (let i = 0; i < n; i++) { const k = 0.5 + 0.5 * Math.sin(ph * sp + i * 1.3); const bh = Math.min(H, H * (0.22 + 0.78 * amp * (0.35 + 0.65 * k))); const x = bw / 2 + i * (bw + gap); g.beginPath(); g.roundRect(x, (H - bh) / 2, bw, bh, bw / 2); g.fill(); } }
}
draw();
function applyStyle(st) { if (!st) return; wstyle = st.waveStyle || 'bars'; wcount = st.waveCount || 4; const B = document.body.classList; B.toggle('cs-outline', st.cardStyle === 'outline'); B.toggle('cs-tinted', st.cardStyle === 'tinted'); B.toggle('notitle', st.showTitle === false); ['slide', 'fade', 'pop'].forEach((x) => B.toggle('a-' + x, st.anim === x)); document.documentElement.style.setProperty('--card-text', (st.cardText || 17) + 'px'); const R = document.documentElement.style; R.setProperty('--card-alpha', (st.cardOpacity || 92) / 100); R.setProperty('--card-radius', (st.cardRadius || 28) + 'px'); document.body.classList.toggle('nobars', st.showBars === false); document.body.classList.toggle('noprogress', st.showProgress === false); document.body.classList.toggle('top', st.cardPos === 'top'); }
window.api.on('state', (s) => { applyStyle(s.style); s.sub = String(s.sub || '').replace(/^🎤 /, ''); mode = s.mode; $('title').textContent = s.title; if ($('text').textContent !== s.text) $('text').textContent = s.text; $('sub').textContent = s.sub || ''; $('bar').style.display = s.progress > 0 && !document.body.classList.contains('noprogress') ? 'block' : 'none'; $('bar').firstElementChild.style.width = Math.round(s.progress * 100) + '%'; level = s.level || 0; $('mic').style.opacity = s.voice ? 1 : .7; $('mic').innerHTML = s.voice ? ICON.stop : ICON.mic; fit(); });
window.api.on('level', (v) => { level = v; });
window.api.on('draw:status', (d) => { $('dctl').hidden = !d; if (d) { $('dcount').textContent = (d.i + 1) + ' / ' + d.n; $('dprev').disabled = d.i === 0; $('dnext').disabled = d.i === d.n - 1; } });
$('dprev').onclick = () => window.api.send('draw:ctl', 'prev'); $('dnext').onclick = () => window.api.send('draw:ctl', 'next'); $('dskip').onclick = () => window.api.send('draw:ctl', 'finish');
$('form').addEventListener('submit', (e) => { e.preventDefault(); const t = $('input').value.trim(); if (t) { window.api.invoke('ui:run', t); $('input').value = ''; } });
$('mic').addEventListener('click', () => window.api.invoke('ui:voice'));
$('close').addEventListener('click', () => { window.api.invoke('ui:stop'); window.api.send('card:close'); });
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') { window.api.send('card:close'); } });
let lastH = 0; function fit() { const h = Math.ceil($('card').getBoundingClientRect().height + 22); if (Math.abs(h - lastH) > 3) { lastH = h; window.api.send('card:height', h); } }
new ResizeObserver(fit).observe($('card')); window.addEventListener('focus', () => $('input').focus()); setTimeout(() => $('input').focus(), 200);
