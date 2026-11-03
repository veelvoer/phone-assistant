// shared renderer helpers: palette from the system accent colour
function hexToHsl(hex) { const m = /#?(..)(..)(..)/.exec(hex); let r = parseInt(m[1], 16) / 255, g = parseInt(m[2], 16) / 255, b = parseInt(m[3], 16) / 255; const mx = Math.max(r, g, b), mn = Math.min(r, g, b); let h = 0, s = 0; const l = (mx + mn) / 2; if (mx !== mn) { const d = mx - mn; s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn); h = mx === r ? (g - b) / d + (g < b ? 6 : 0) : mx === g ? (b - r) / d + 2 : (r - g) / d + 4; h *= 60; } return [h, s * 100, l * 100]; }
function applyTheme(t, dark) {
  const [h, s0] = hexToHsl(t.accent); const s = Math.max(35, Math.min(85, s0));
  const R = document.documentElement.style; const hsl = (hh, ss, ll, a = 1) => `hsla(${Math.round(hh)}, ${Math.round(ss)}%, ${Math.round(ll)}%, ${a})`;
  R.setProperty('--accent', dark ? hsl(h, s, 72) : hsl(h, s, 42));
  R.setProperty('--accent-2', hsl(h + 25, s, dark ? 68 : 55)); R.setProperty('--accent-3', hsl(h - 25, s, dark ? 62 : 50));
  R.setProperty('--on-accent', dark ? hsl(h, s, 14) : '#fff');
  R.setProperty('--container', dark ? hsl(h, Math.min(s, 40), 26) : hsl(h, Math.min(s, 70), 90));
  R.setProperty('--on-container', dark ? hsl(h, 60, 92) : hsl(h, 70, 14));
  R.setProperty('--bg', dark ? hsl(h, 8, 9) : hsl(h, 20, 98)); R.setProperty('--surface', dark ? hsl(h, 9, 14) : hsl(h, 18, 94)); R.setProperty('--surface-2', dark ? hsl(h, 9, 19) : hsl(h, 16, 90));
  R.setProperty('--fg', dark ? hsl(h, 10, 92) : hsl(h, 15, 11)); R.setProperty('--muted', dark ? hsl(h, 6, 66) : hsl(h, 8, 38)); R.setProperty('--outline', dark ? hsl(h, 6, 32) : hsl(h, 8, 76));
  R.setProperty('--glass', dark ? hsl(h, 10, 11, 0.9) : hsl(h, 20, 98, 0.93)); document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  window.__accent = { accent: dark ? hsl(h, s, 72) : hsl(h, s, 50), a2: hsl(h + 25, s, 66), a3: hsl(h - 25, s, 60) };
}
window.api.invoke('cfg:all').then((d) => applyTheme(d.theme, d.dark));
window.api.on('theme', (t, dark) => applyTheme(t, dark));

window.ICON = {
  mic: '<svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.3-3c0 3-2.54 5.1-5.3 5.1S6.7 14 6.7 11H5c0 3.41 2.72 6.23 6 6.72V21h2v-3.28c3.28-.48 6-3.3 6-6.72h-1.7z"/></svg>',
  stop: '<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>',
  play: '<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>',
  close: '<svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg>',
};
