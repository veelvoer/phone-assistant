'use strict';
const path = require('path');
const { run, sleep, spawn } = require('./util');

// Windows virtual-key codes
const VK = { backspace: 8, tab: 9, enter: 13, return: 13, shift: 16, ctrl: 17, control: 17, alt: 18, esc: 27, escape: 27, space: 32, pageup: 33, pgup: 33, pagedown: 34, pgdown: 34, end: 35, home: 36, left: 37, up: 38, right: 39, down: 40, insert: 45, delete: 46, del: 46, super: 91, win: 91, windows: 91, meta: 91, cmd: 91, f1: 112, f2: 113, f3: 114, f4: 115, f5: 116, f6: 117, f7: 118, f8: 119, f9: 120, f10: 121, f11: 122, f12: 123, ';': 186, '=': 187, ',': 188, '-': 189, '.': 190, '/': 191, '`': 192, '[': 219, '\\': 220, ']': 221, "'": 222, volumeup: 175, volumedown: 174, mute: 173, playpause: 179, printscreen: 44, print: 44 };
function vkOf(name) { const n = String(name).toLowerCase(); if (VK[n] != null) return VK[n]; if (/^[a-z]$/.test(n)) return n.toUpperCase().charCodeAt(0); if (/^[0-9]$/.test(n)) return n.charCodeAt(0); throw new Error('unknown key "' + name + '"'); }

class Windows {
  constructor(electron) { this.electron = electron || {}; this.last = null; this.proc = null; this.buf = ''; this.waiters = []; this.warnings = []; }
  describe() { return 'Windows ' + require('os').release(); }
  problems() { return this.warnings; }
  async init() { return this; }

  async helper() {
    if (this.proc) return;
    const script = path.join(__dirname, 'windows_helper.ps1').replace('app.asar' + path.sep, 'app.asar.unpacked' + path.sep);
    this.proc = spawn('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', script], { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
    this.proc.stdout.setEncoding('utf8'); this.proc.stdout.on('data', (d) => { this.buf += d; let i; while ((i = this.buf.indexOf('\n')) >= 0) { const l = this.buf.slice(0, i).trim(); this.buf = this.buf.slice(i + 1); const w = this.waiters.shift(); if (w && l) { try { w(JSON.parse(l)); } catch { w({ error: l }); } } else if (!w && l) { /* ready line */ } } });
    this.proc.on('exit', () => { this.proc = null; for (const w of this.waiters.splice(0)) w({ error: 'helper exited' }); });
    this.ready = new Promise((res) => this.waiters.push(res)); await this.ready;
  }
  async call(msg) { await this.helper(); return new Promise((res) => { this.waiters.push(res); this.proc.stdin.write(JSON.stringify(msg) + '\n'); }); }

  async monitor() {
    const d = this.electron.screen.getPrimaryDisplay();
    return { name: String(d.id), x: d.bounds.x, y: d.bounds.y, pw: Math.round(d.size.width * d.scaleFactor), ph: Math.round(d.size.height * d.scaleFactor), scale: d.scaleFactor, lw: d.size.width, lh: d.size.height };
  }
  async screenshot() {
    const mon = await this.monitor();
    const src = await this.electron.desktopCapturer.getSources({ types: ['screen'], thumbnailSize: { width: mon.pw, height: mon.ph } });
    if (!src[0]) throw new Error('screenshot failed');
    this.last = mon; return { png: src[0].thumbnail.toPNG(), monitor: mon };
  }
  toGlobal(px, py) { const m = this.last || { x: 0, y: 0, scale: 1 }; return { x: Math.round(m.x + px / m.scale), y: Math.round(m.y + py / m.scale) }; }
  phys(px, py) { const m = this.last || { x: 0, y: 0, scale: 1 }; return { x: Math.round(m.x * m.scale + px), y: Math.round(m.y * m.scale + py) }; }

  async move(px, py) { const p = this.phys(px, py); await this.call({ op: 'move', x: p.x, y: p.y }); }
  async click(px, py, { button = 'left', count = 1 } = {}) { const p = this.phys(px, py); await this.call({ op: 'click', x: p.x, y: p.y, button, count }); }
  async drag(x1, y1, x2, y2) { await this.move(x1, y1); await this.call({ op: 'btn', button: 'left', down: true }); await sleep(80); for (let i = 1; i <= 8; i++) { await this.move(x1 + (x2 - x1) * i / 8, y1 + (y2 - y1) * i / 8); await sleep(25); } await this.call({ op: 'btn', button: 'left', down: false }); }
  async scroll(px, py, dx, dy) { if (px != null) await this.move(px, py); await this.call({ op: 'wheel', v: -Math.round(dy || 0), h: Math.round(dx || 0) }); }
  async typeText(text) { await this.call({ op: 'type', text }); }
  async key(combo) { const vks = String(combo).split('+').map((s) => vkOf(s.trim())); await this.call({ op: 'keys', vks }); }
  async getClipboard() { return this.electron.clipboard ? this.electron.clipboard.readText() : ''; }
  async setClipboard(t) { if (this.electron.clipboard) this.electron.clipboard.writeText(t); }
  async activeWindow() { const r = await this.call({ op: 'active' }); return { title: r.title || '', class: r.class || '' }; }
  async listWindows() { const r = await this.call({ op: 'windows' }); return (r.list || []).slice(0, 30); }
  async focusWindow(q) { const r = await this.call({ op: 'focus', q }); return !!r.ok; }
  async openApp(name) { const r = await this.call({ op: 'app', name }); await sleep(1500); return r.ok ? 'opened ' + (r.name || name) : `error: no application named "${name}" found`; }
  async openUrl(url) { await run('powershell.exe', ['-NoProfile', '-Command', `Start-Process '${String(url).replace(/'/g, "''")}'`]); await sleep(1000); return 'opened'; }
  async shell(cmd, { timeout = 60000, cwd } = {}) { const r = await run('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', cmd], { timeout, cwd }); return { code: r.code, stdout: r.stdout, stderr: r.stderr, error: r.error }; }
  async findDesktop() { return null; }
  dispose() { try { if (this.proc) this.proc.stdin.end(); } catch { /* ignore */ } }
}
module.exports = { Windows };
