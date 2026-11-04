'use strict';
// Allow-list sanitizer for SVG written by the model (no scripts, no external loads, no event handlers).
const ELEMENTS = new Set(['svg', 'g', 'path', 'circle', 'ellipse', 'rect', 'line', 'polyline', 'polygon', 'text', 'tspan', 'defs', 'lineargradient', 'radialgradient', 'stop', 'marker', 'title', 'desc', 'use', 'clippath', 'mask', 'symbol']);
const ATTRS = new Set(['viewbox', 'width', 'height', 'x', 'y', 'x1', 'y1', 'x2', 'y2', 'cx', 'cy', 'r', 'rx', 'ry', 'd', 'points', 'transform', 'fill', 'stroke', 'stroke-width', 'stroke-linecap', 'stroke-linejoin', 'stroke-dasharray', 'stroke-dashoffset', 'stroke-opacity', 'fill-opacity', 'opacity', 'fill-rule', 'clip-rule', 'font-size', 'font-family', 'font-weight', 'font-style', 'text-anchor', 'dominant-baseline', 'alignment-baseline', 'letter-spacing', 'text-decoration', 'id', 'offset', 'stop-color', 'stop-opacity', 'gradientunits', 'gradienttransform', 'markerwidth', 'markerheight', 'refx', 'refy', 'orient', 'markerunits', 'marker-end', 'marker-start', 'marker-mid', 'clip-path', 'mask', 'xmlns', 'preserveaspectratio', 'dx', 'dy', 'class', 'href', 'xlink:href', 'patterntransform', 'visibility', 'display', 'vector-effect']);
const SAFE_VALUE = (name, v) => {
  const s = String(v).trim().toLowerCase();
  if (/javascript:|data:|vbscript:|expression\(|@import|<|&#/.test(s)) return false;
  if (name === 'href' || name === 'xlink:href') return /^#[\w-]+$/.test(s);
  if (/url\(/.test(s)) return /^url\(\s*['"]?#[\w-]+['"]?\s*\)$/.test(s);   // only local references (gradients, markers, clips)
  return true;
};

function sanitizeSvg(src, { maxLen = 60000 } = {}) {
  let s = String(src || '').trim(); if (!s) return '';
  s = s.slice(0, maxLen).replace(/<!--[\s\S]*?-->/g, '').replace(/<!\[CDATA\[[\s\S]*?\]\]>/g, '').replace(/<\?[\s\S]*?\?>/g, '').replace(/<!DOCTYPE[^>]*>/gi, '');
  const start = s.search(/<svg[\s>]/i); if (start < 0) return ''; s = s.slice(start);
  let out = '', i = 0, depth = 0, textOk = 0; const stack = [];
  while (i < s.length) {
    const lt = s.indexOf('<', i);
    if (lt < 0) { out += esc(s.slice(i)); break; }
    if (lt > i) out += stack.length && textOk ? esc(s.slice(i, lt)) : '';
    const gt = findTagEnd(s, lt); if (gt < 0) break;
    const raw = s.slice(lt + 1, gt); i = gt + 1;
    const closing = raw[0] === '/'; const m = /^\/?\s*([a-zA-Z][\w:-]*)/.exec(raw); if (!m) continue; const name = m[1].toLowerCase();
    if (closing) { const top = stack[stack.length - 1]; if (top && top.name === name) { stack.pop(); if (top.keep) out += `</${name}>`; if (name === 'text' || name === 'tspan' || name === 'title' || name === 'desc') textOk--; } continue; }
    const selfClose = /\/\s*$/.test(raw); const keep = ELEMENTS.has(name) && !(stack.length && stack.some((x) => !x.keep));
    if (!keep) { if (!selfClose) stack.push({ name, keep: false }); continue; }
    if (!stack.length && name !== 'svg') continue;
    const attrs = []; const re = /([a-zA-Z_:][\w:.-]*)\s*=\s*("([^"]*)"|'([^']*)')/g; let a;
    while ((a = re.exec(raw))) { const an = a[1].toLowerCase(); const av = a[3] != null ? a[3] : a[4]; if (an.startsWith('on')) continue; if (!ATTRS.has(an)) continue; if (an === 'style') continue; if (!SAFE_VALUE(an, av)) continue; attrs.push(`${an}="${av.replace(/"/g, '&quot;')}"`); }
    if (name === 'svg' && !attrs.some((x) => x.startsWith('xmlns='))) attrs.push('xmlns="http://www.w3.org/2000/svg"');
    out += `<${name}${attrs.length ? ' ' + attrs.join(' ') : ''}${selfClose ? '/>' : '>'}`;
    if (!selfClose) { stack.push({ name, keep: true }); if (name === 'text' || name === 'tspan' || name === 'title' || name === 'desc') textOk++; depth++; }
  }
  while (stack.length) { const t = stack.pop(); if (t.keep) out += `</${t.name}>`; }
  return /^<svg[\s>]/.test(out) ? out : '';
}
function findTagEnd(s, from) { let q = ''; for (let k = from + 1; k < s.length; k++) { const c = s[k]; if (q) { if (c === q) q = ''; } else if (c === '"' || c === "'") q = c; else if (c === '>') return k; } return -1; }
const esc = (t) => t.replace(/&(?!(amp|lt|gt|quot|apos|#\d+);)/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

module.exports = { sanitizeSvg };
