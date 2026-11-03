'use strict';
// Electron accelerator ("Ctrl+Alt+Space") -> Hyprland bind ("CTRL + ALT, space" parts) and back.
const MODS = { ctrl: 'CTRL', control: 'CTRL', commandorcontrol: 'CTRL', cmdorctrl: 'CTRL', alt: 'ALT', option: 'ALT', shift: 'SHIFT', super: 'SUPER', meta: 'SUPER', cmd: 'SUPER', command: 'SUPER', win: 'SUPER' };
const KEYS = { space: 'space', return: 'Return', enter: 'Return', escape: 'Escape', esc: 'Escape', tab: 'Tab', backspace: 'BackSpace', delete: 'Delete', insert: 'Insert', home: 'Home', end: 'End', pageup: 'Prior', pagedown: 'Next', up: 'Up', down: 'Down', left: 'Left', right: 'Right', plus: 'plus', minus: 'minus', '`': 'grave', ',': 'comma', '.': 'period', '/': 'slash', ';': 'semicolon', "'": 'apostrophe', '[': 'bracketleft', ']': 'bracketright', '\\': 'backslash', '=': 'equal', '-': 'minus' };
function toHypr(accel) {
  const parts = String(accel).split('+').map((s) => s.trim()).filter(Boolean); if (!parts.length) return '';
  const key = parts.pop(); const mods = [...new Set(parts.map((m) => MODS[m.toLowerCase()]).filter(Boolean))];
  const k = KEYS[key.toLowerCase()] || (/^f\d+$/i.test(key) ? key.toUpperCase() : key.length === 1 ? key.toUpperCase() : key);
  return mods.concat(k).join(' + ');
}
module.exports = { toHypr };
