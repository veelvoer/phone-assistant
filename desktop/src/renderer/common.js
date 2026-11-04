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
  R.setProperty('--glass-h', Math.round(h)); R.setProperty('--glass-s', (dark ? 10 : 20) + '%'); R.setProperty('--glass-l', (dark ? 11 : 98) + '%'); document.documentElement.dataset.theme = dark ? 'dark' : 'light';
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
Object.assign(window.ICON, (() => { const mk = (d, s = 20) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="currentColor"><path d="${d}"/></svg>`; return {
  home: mk('M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z'), ai: mk('M19 9l1.25-2.75L23 5l-2.75-1.25L19 1l-1.25 2.75L15 5l2.75 1.25L19 9zm-7.5.5L9 4 6.5 9.5 1 12l5.5 2.5L9 20l2.5-5.5L17 12l-5.5-2.5zM19 15l-1.25 2.75L15 19l2.75 1.25L19 23l1.25-2.75L23 19l-2.75-1.25L19 15z'),
  keyboard: mk('M20 5H4c-1.1 0-1.99.9-1.99 2L2 17c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm-9 3h2v2h-2V8zm0 3h2v2h-2v-2zM8 8h2v2H8V8zm0 3h2v2H8v-2zm-1 2H5v-2h2v2zm0-3H5V8h2v2zm9 7H8v-2h8v2zm0-4h-2v-2h2v2zm0-3h-2V8h2v2zm3 3h-2v-2h2v2zm0-3h-2V8h2v2z'),
  palette: mk('M12 3c-4.97 0-9 4.03-9 9s4.03 9 9 9c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01-.23-.26-.38-.61-.38-.99 0-.83.67-1.5 1.5-1.5H16c2.76 0 5-2.24 5-5 0-4.42-4.03-8-9-8zm-5.5 9c-.83 0-1.5-.67-1.5-1.5S5.67 9 6.5 9 8 9.67 8 10.5 7.33 12 6.5 12zm3-4C8.67 8 8 7.33 8 6.5S8.67 5 9.5 5s1.5.67 1.5 1.5S10.33 8 9.5 8zm5 0c-.83 0-1.5-.67-1.5-1.5S13.67 5 14.5 5s1.5.67 1.5 1.5S15.33 8 14.5 8zm3 4c-.83 0-1.5-.67-1.5-1.5S16.67 9 17.5 9s1.5.67 1.5 1.5-.67 1.5-1.5 1.5z'),
  face: mk('M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm3.5-9c.83 0 1.5-.67 1.5-1.5S16.33 8 15.5 8 14 8.67 14 9.5s.67 1.5 1.5 1.5zm-7 0c.83 0 1.5-.67 1.5-1.5S9.33 8 8.5 8 7 8.67 7 9.5 7.67 11 8.5 11zm3.5 6.5c2.33 0 4.31-1.46 5.11-3.5H6.89c.8 2.04 2.78 3.5 5.11 3.5z'),
  brain: mk('M13 3c-3.87 0-7 3.13-7 7 0 .66.1 1.3.27 1.9L4 14v3h2v3h5v-3h2c3.87 0 7-3.13 7-7s-3.13-7-7-7z'), wallet: mk('M21 18v1c0 1.1-.9 2-2 2H5c-1.11 0-2-.9-2-2V5c0-1.1.89-2 2-2h14c1.1 0 2 .9 2 2v1h-9c-1.11 0-2 .9-2 2v8c0 1.1.89 2 2 2h9zm-9-2h10V8H12v8zm4-2.5c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5z'),
  info: mk('M11 7h2v2h-2zm0 4h2v6h-2zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z'), mic20: mk('M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.3-3c0 3-2.54 5.1-5.3 5.1S6.7 14 6.7 11H5c0 3.41 2.72 6.23 6 6.72V21h2v-3.28c3.28-.48 6-3.3 6-6.72h-1.7z'),
}; })());

