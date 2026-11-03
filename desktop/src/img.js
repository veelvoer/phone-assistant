'use strict';
// Image helpers: use Electron's nativeImage when available (resize + JPEG); plain Node falls back to the original PNG.
let ni = null; try { ni = require('electron').nativeImage; } catch { /* plain node */ }

function pngSize(buf) { return { w: buf.readUInt32BE(16), h: buf.readUInt32BE(20) }; }

/** -> { data: Buffer, mime, w, h, nativeW, nativeH } scaled so the long edge is <= maxEdge */
function prepare(png, maxEdge = 1280) {
  const { w, h } = pngSize(png);
  if (!ni || !ni.createFromBuffer) return { data: png, mime: 'image/png', w, h, nativeW: w, nativeH: h };
  let img = ni.createFromBuffer(png);
  const s = Math.min(1, maxEdge / Math.max(w, h));
  const tw = Math.round(w * s), th = Math.round(h * s);
  if (s < 1) img = img.resize({ width: tw, height: th, quality: 'best' });
  return { data: img.toJPEG(72), mime: 'image/jpeg', w: tw, h: th, nativeW: w, nativeH: h };
}

/** crop a native-pixel region and scale it up so small text is readable */
function crop(png, x, y, w, h, maxEdge = 1280) {
  const { w: W, h: H } = pngSize(png);
  x = Math.max(0, Math.min(W - 2, Math.round(x))); y = Math.max(0, Math.min(H - 2, Math.round(y)));
  w = Math.max(2, Math.min(W - x, Math.round(w))); h = Math.max(2, Math.min(H - y, Math.round(h)));
  if (!ni || !ni.createFromBuffer) return { data: png, mime: 'image/png', w: W, h: H };
  let img = ni.createFromBuffer(png).crop({ x, y, width: w, height: h });
  const s = Math.min(3, maxEdge / Math.max(w, h));
  const tw = Math.round(w * s), th = Math.round(h * s);
  if (s !== 1) img = img.resize({ width: tw, height: th, quality: 'best' });
  return { data: img.toJPEG(80), mime: 'image/jpeg', w: tw, h: th };
}

module.exports = { prepare, crop, pngSize };
