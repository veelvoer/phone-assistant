'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const { run, which } = require('./platform/util');

const GNOME = { blue: '#3584e4', teal: '#2190a4', green: '#3a944a', yellow: '#c88800', orange: '#ed5b00', red: '#e62d42', pink: '#d56199', purple: '#9141ac', slate: '#6f8396' };
const hex = (s) => { const m = /#?([0-9a-fA-F]{6})/.exec(String(s)); return m ? '#' + m[1].toLowerCase() : ''; };

/** System accent colour: Windows accent, Hyprland border, GNOME/KDE accent, pywal, else a calm blue. */
async function detectAccent(electron, override) {
  if (hex(override)) return { accent: hex(override), source: 'settings' };
  try {
    if (process.platform === 'win32' && electron.systemPreferences) { const c = hex(electron.systemPreferences.getAccentColor()); if (c) return { accent: c, source: 'windows' }; }
    if (process.env.HYPRLAND_INSTANCE_SIGNATURE && which('hyprctl')) {
      const r = await run('hyprctl', ['-j', 'getoption', 'general:col.active_border']);
      const j = JSON.parse(r.stdout); const v = String(j.custom || j.str || j.int || '');
      const m = /([0-9a-fA-F]{8})/.exec(v); if (m) { const c = '#' + m[1].slice(2, 8); if (c !== '#000000') return { accent: c, source: 'hyprland' }; }
    }
    if (which('gsettings')) { const r = await run('gsettings', ['get', 'org.gnome.desktop.interface', 'accent-color']); const m = /'(\w+)'/.exec(r.stdout); if (m && GNOME[m[1]]) return { accent: GNOME[m[1]], source: 'gnome' }; }
    try { const k = fs.readFileSync(path.join(os.homedir(), '.config/kdeglobals'), 'utf8'); const sec = /\[Colors:Selection\][^[]*?BackgroundNormal=(\d+),(\d+),(\d+)/s.exec(k); if (sec) return { accent: '#' + [sec[1], sec[2], sec[3]].map((n) => Number(n).toString(16).padStart(2, '0')).join(''), source: 'kde' }; } catch { /* no kde */ }
    try { const w = JSON.parse(fs.readFileSync(path.join(os.homedir(), '.cache/wal/colors.json'), 'utf8')); const c = hex(w.colors && (w.colors.color4 || w.colors.color2)); if (c) return { accent: c, source: 'pywal' }; } catch { /* no wal */ }
  } catch { /* fall through */ }
  return { accent: '#7aa2f7', source: 'default' };
}
module.exports = { detectAccent };
