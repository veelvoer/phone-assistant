'use strict';
// Linux input-event key codes by physical key (QWERTY positions); letter keys are remapped for AZERTY layouts.
const BASE = {
  esc: 1, escape: 1, '1': 2, '2': 3, '3': 4, '4': 5, '5': 6, '6': 7, '7': 8, '8': 9, '9': 10, '0': 11, minus: 12, '-': 12, equal: 13, '=': 13,
  backspace: 14, tab: 15, q: 16, w: 17, e: 18, r: 19, t: 20, y: 21, u: 22, i: 23, o: 24, p: 25, '[': 26, ']': 27, enter: 28, return: 28,
  ctrl: 29, control: 29, a: 30, s: 31, d: 32, f: 33, g: 34, h: 35, j: 36, k: 37, l: 38, ';': 39, semicolon: 39, "'": 40, '`': 41,
  shift: 42, '\\': 43, z: 44, x: 45, c: 46, v: 47, b: 48, n: 49, m: 50, ',': 51, comma: 51, '.': 52, period: 52, dot: 52, '/': 53, slash: 53,
  alt: 56, space: 57, ' ': 57, caps: 58, capslock: 58,
  f1: 59, f2: 60, f3: 61, f4: 62, f5: 63, f6: 64, f7: 65, f8: 66, f9: 67, f10: 68, f11: 87, f12: 88,
  home: 102, up: 103, pageup: 104, pgup: 104, left: 105, right: 106, end: 107, down: 108, pagedown: 109, pgdown: 109, insert: 110, delete: 111, del: 111,
  super: 125, meta: 125, win: 125, windows: 125, cmd: 125, menu: 127,
  mute: 113, volumedown: 114, volumeup: 115, playpause: 164, next: 163, prev: 165, brightnessdown: 224, brightnessup: 225, printscreen: 99, print: 99,
};
const AZERTY = { a: 16, z: 17, q: 30, w: 44, m: 39 };
const AZERTY_LAYOUTS = new Set(['be', 'fr']);

/** "ctrl+shift+t" -> [29, 42, 20] (modifiers first, last one is the key). */
function parseCombo(combo, layout = 'us') {
  const az = AZERTY_LAYOUTS.has(String(layout).split(/[,(]/)[0].trim().toLowerCase());
  const parts = String(combo).toLowerCase().split('+').map((s) => s.trim()).filter(Boolean);
  const codes = [];
  for (const p of parts) {
    let c = (az && AZERTY[p]) || BASE[p];
    if (c == null) throw new Error('unknown key "' + p + '"');
    codes.push(c);
  }
  if (!codes.length) throw new Error('empty key combo');
  return codes;
}

/** Sequence for the uinput helper: press all in order, release in reverse. */
function comboSeq(codes) {
  const seq = [];
  for (const c of codes) seq.push([c, 1]);
  seq.push({ sleep: 30 });
  for (const c of codes.slice().reverse()) seq.push([c, 0]);
  return seq;
}

module.exports = { parseCombo, comboSeq, BASE, AZERTY };
