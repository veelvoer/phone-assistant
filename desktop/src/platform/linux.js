'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const { run, which, sleep, spawn } = require('./util');
const { parseCombo, comboSeq } = require('./keys');

const TERMINALS = /(kitty|alacritty|foot|wezterm|gnome-terminal|konsole|xterm|terminator|tilix|st-256color|urxvt|ghostty|xfce4-terminal|lxterminal|sakura|rxvt|org\.wezfurlong)/i;

class Linux {
  constructor(electron) {
    this.electron = electron || {};        // { screen, desktopCapturer, clipboard } when running inside Electron
    this.session = process.env.XDG_SESSION_TYPE || (process.env.WAYLAND_DISPLAY ? 'wayland' : 'x11');
    this.hypr = !!process.env.HYPRLAND_INSTANCE_SIGNATURE && !!which('hyprctl');
    this.sway = !!process.env.SWAYSOCK && !!which('swaymsg');
    this.layout = 'us'; this.helper = null; this.helperReady = null; this.last = null; this.hyprLua = null;
    this.warnings = [];
  }

  describe() {
    return `Linux ${this.hypr ? 'Hyprland' : this.sway ? 'Sway' : (process.env.XDG_CURRENT_DESKTOP || 'desktop')} (${this.session}), keyboard layout ${this.layout}`;
  }

  async init() {
    this.layout = await this.detectLayout();
    const canUinput = (() => { try { fs.accessSync('/dev/uinput', fs.constants.W_OK); return true; } catch { return false; } })();
    this.canUinput = canUinput && !!which('python3');
    if (!this.canUinput && !which('xdotool') && !which('ydotool') && !which('wtype')) this.warnings.push('No way to send mouse/keyboard input: make /dev/uinput writable for your user (udev rule, see README) or install xdotool (X11) / ydotool (Wayland).');
    if (!which('grim') && !which('spectacle') && !which('gnome-screenshot') && !which('maim') && !which('scrot') && !(this.electron.desktopCapturer && this.session !== 'wayland')) this.warnings.push('No screenshot tool: install grim (Wayland/Hyprland/Sway) or maim/scrot (X11).');
    if (this.session === 'wayland' && !which('wl-copy')) this.warnings.push('Install wl-clipboard (wl-copy) so typing text can use the clipboard.');
    return this;
  }

  problems() { return this.warnings; }

  async detectLayout() {
    if (this.hypr) { const r = await run('hyprctl', ['-j', 'getoption', 'input:kb_layout']); try { const s = JSON.parse(r.stdout).str; if (s) return s.split(',')[0].trim(); } catch { /* next */ } }
    if (which('setxkbmap')) { const r = await run('setxkbmap', ['-query']); const m = /layout:\s*(\S+)/.exec(r.stdout); if (m) return m[1].split(',')[0]; }
    if (which('localectl')) { const r = await run('localectl', ['status']); const m = /X11 Layout:\s*(\S+)/.exec(r.stdout); if (m) return m[1].split(',')[0]; }
    return 'us';
  }

  // ---------- monitor + screenshot ----------
  async monitor() {
    if (this.hypr) {
      const r = await run('hyprctl', ['monitors', '-j']);
      try {
        const ms = JSON.parse(r.stdout); const m = ms.find((x) => x.focused) || ms[0];
        const sc = m.scale || 1; const swap = [1, 3, 5, 7].includes(m.transform);
        const w = swap ? m.height : m.width, h = swap ? m.width : m.height;
        return { name: m.name, x: m.x, y: m.y, pw: w, ph: h, scale: sc, lw: w / sc, lh: h / sc };
      } catch { /* fall through */ }
    }
    const scr = this.electron.screen; const d = scr ? scr.getPrimaryDisplay() : null;
    if (d) return { name: '', x: d.bounds.x, y: d.bounds.y, pw: Math.round(d.size.width * d.scaleFactor), ph: Math.round(d.size.height * d.scaleFactor), scale: d.scaleFactor, lw: d.size.width, lh: d.size.height };
    return { name: '', x: 0, y: 0, pw: 1920, ph: 1080, scale: 1, lw: 1920, lh: 1080 };
  }

  async screenshot() {
    const mon = await this.monitor();
    let png = null;
    if (which('grim')) { const r = await run('grim', mon.name ? ['-o', mon.name, '-t', 'png', '-'] : ['-t', 'png', '-'], { binary: true }); if (r.code === 0 && r.stdout.length > 1000) png = r.stdout; }
    if (!png && which('spectacle')) { const f = path.join(os.tmpdir(), `as-${process.pid}.png`); const r = await run('spectacle', ['-b', '-n', '-f', '-o', f]); if (r.code === 0) { try { png = fs.readFileSync(f); fs.unlinkSync(f); } catch { /* next */ } } }
    if (!png && which('gnome-screenshot')) { const f = path.join(os.tmpdir(), `as-${process.pid}.png`); const r = await run('gnome-screenshot', ['-f', f]); if (r.code === 0) { try { png = fs.readFileSync(f); fs.unlinkSync(f); } catch { /* next */ } } }
    if (!png && which('maim')) { const r = await run('maim', [], { binary: true }); if (r.code === 0) png = r.stdout; }
    if (!png && which('scrot')) { const f = path.join(os.tmpdir(), `as-${process.pid}.png`); const r = await run('scrot', ['-o', f]); if (r.code === 0) { try { png = fs.readFileSync(f); fs.unlinkSync(f); } catch { /* next */ } } }
    if (!png && this.electron.desktopCapturer) {
      const src = await this.electron.desktopCapturer.getSources({ types: ['screen'], thumbnailSize: { width: mon.pw, height: mon.ph } });
      if (src[0]) png = src[0].thumbnail.toPNG();
    }
    if (!png) throw new Error('screenshot failed: ' + (this.warnings.find((w) => /screenshot/i.test(w)) || 'install grim (Wayland) or maim (X11)'));
    this.last = mon;
    return { png, monitor: mon };
  }

  /** native screenshot pixel -> global logical coordinate */
  toGlobal(px, py) {
    const m = this.last || { x: 0, y: 0, scale: 1 };
    return { x: Math.round(m.x + px / m.scale), y: Math.round(m.y + py / m.scale) };
  }

  // ---------- input ----------
  async helperSend(msg) {
    if (!this.helper) {
      const script = path.join(__dirname, 'uinput_helper.py').replace('app.asar' + path.sep, 'app.asar.unpacked' + path.sep);
      this.helper = spawn('python3', ['-u', script], { stdio: ['pipe', 'pipe', 'pipe'] });
      this.helperBuf = ''; this.helperWait = [];
      this.helper.stdout.on('data', (d) => { this.helperBuf += d; let i; while ((i = this.helperBuf.indexOf('\n')) >= 0) { const l = this.helperBuf.slice(0, i); this.helperBuf = this.helperBuf.slice(i + 1); const w = this.helperWait.shift(); if (w) w(l); } });
      this.helper.stderr.on('data', (d) => { this.helperErr = String(d).slice(-300); });
      this.helper.on('exit', () => { this.helper = null; this.helperReady = null; for (const w of this.helperWait.splice(0)) w('err helper exited ' + (this.helperErr || '')); });
      this.helperReady = new Promise((res) => this.helperWait.push(res));
    }
    const r0 = await this.helperReady; if (!/^ready/.test(r0)) throw new Error('input helper failed: ' + r0 + ' ' + (this.helperErr || ''));
    return new Promise((resolve, reject) => {
      this.helperWait.push((l) => (l === 'ok' ? resolve() : reject(new Error(l))));
      this.helper.stdin.write(JSON.stringify(msg) + '\n');
    });
  }

  async moveGlobal(x, y) {
    if (this.hypr) {
      let r = await run('hyprctl', ['dispatch', `hl.dsp.cursor.move({x=${x},y=${y}})`]);          // Hyprland >= 0.55 (Lua)
      if (r.code !== 0 || /error/i.test(r.stdout)) r = await run('hyprctl', ['dispatch', 'movecursor', String(x), String(y)]);   // older
      return;
    }
    if (this.sway) { await run('swaymsg', ['seat', 'seat0', 'cursor', 'set', String(x), String(y)]); return; }
    if (this.session === 'x11' && which('xdotool')) { await run('xdotool', ['mousemove', String(x), String(y)]); return; }
    if (this.canUinput) {
      const scr = this.electron.screen; let w = 1920, h = 1080, ox = 0, oy = 0;
      if (scr) { const b = scr.getAllDisplays().map((d) => d.bounds); ox = Math.min(...b.map((v) => v.x)); oy = Math.min(...b.map((v) => v.y)); w = Math.max(...b.map((v) => v.x + v.width)) - ox; h = Math.max(...b.map((v) => v.y + v.height)) - oy; }
      await this.helperSend({ op: 'abs', x: x - ox, y: y - oy, w, h }); return;
    }
    if (which('ydotool')) { await run('ydotool', ['mousemove', '--absolute', '-x', String(x), '-y', String(y)]); return; }
    throw new Error('cannot move the mouse: no input backend (see README)');
  }

  async clickGlobal(button, count) {
    if (this.canUinput) return this.helperSend({ op: 'click', btn: button, count });
    if (which('xdotool')) { await run('xdotool', ['click', '--repeat', String(count), button === 'right' ? '3' : button === 'middle' ? '2' : '1']); return; }
    if (which('ydotool')) { const code = button === 'right' ? '0xC1' : button === 'middle' ? '0xC2' : '0xC0'; for (let i = 0; i < count; i++) await run('ydotool', ['click', code]); return; }
    throw new Error('cannot click: no input backend (see README)');
  }

  async move(px, py) { const g = this.toGlobal(px, py); await this.moveGlobal(g.x, g.y); await sleep(40); }

  async click(px, py, { button = 'left', count = 1 } = {}) { await this.move(px, py); await sleep(30); await this.clickGlobal(button, count); }

  async drag(x1, y1, x2, y2) {
    await this.move(x1, y1); await sleep(60);
    if (!this.canUinput) throw new Error('drag needs the uinput helper');
    await this.helperSend({ op: 'btn', btn: 'left', down: true }); await sleep(80);
    const N = 8;
    for (let i = 1; i <= N; i++) { await this.move(x1 + (x2 - x1) * i / N, y1 + (y2 - y1) * i / N); await sleep(25); }
    await sleep(60); await this.helperSend({ op: 'btn', btn: 'left', down: false });
  }

  async scroll(px, py, dx, dy) {
    if (px != null) await this.move(px, py);
    if (this.canUinput) return this.helperSend({ op: 'wheel', v: -Math.round(dy || 0), h: Math.round(dx || 0) });   // dy > 0 = scroll down
    if (which('xdotool')) { const n = Math.abs(Math.round(dy || 0)); if (n) await run('xdotool', ['click', '--repeat', String(n), dy > 0 ? '5' : '4']); return; }
    throw new Error('cannot scroll: no input backend');
  }

  async key(combo) {
    const codes = parseCombo(combo, this.layout);
    if (this.canUinput) return this.helperSend({ op: 'keys', seq: comboSeq(codes) });
    if (which('xdotool')) { await run('xdotool', ['key', String(combo).replace(/\bsuper\b/gi, 'Super')]); return; }
    throw new Error('cannot press keys: no input backend (see README)');
  }

  async getClipboard() {
    if (this.session === 'wayland' && which('wl-paste')) { const r = await run('wl-paste', ['--no-newline']); return r.code === 0 ? r.stdout : ''; }
    if (which('xclip')) { const r = await run('xclip', ['-o', '-selection', 'clipboard']); return r.code === 0 ? r.stdout : ''; }
    return this.electron.clipboard ? this.electron.clipboard.readText() : '';
  }

  async setClipboard(t) {
    if (this.session === 'wayland' && which('wl-copy')) { await run('wl-copy', [], { input: t, timeout: 3000 }); return; }
    if (which('xclip')) { const c = spawn('xclip', ['-selection', 'clipboard'], { stdio: ['pipe', 'ignore', 'ignore'] }); c.stdin.end(t); await sleep(80); return; }
    if (this.electron.clipboard) this.electron.clipboard.writeText(t);
  }

  /** Type text layout-independently: clipboard + paste (wtype/xdotool type for short plain ASCII if present). */
  async typeText(text) {
    if (which('wtype') && this.session === 'wayland') { const r = await run('wtype', ['--', text]); if (r.code === 0) return; }
    if (which('xdotool') && this.session === 'x11') { const r = await run('xdotool', ['type', '--delay', '8', '--', text]); if (r.code === 0) return; }
    const prev = await this.getClipboard().catch(() => '');
    await this.setClipboard(text); await sleep(80);
    const w = await this.activeWindow().catch(() => ({}));
    await this.key(TERMINALS.test(`${w.class || ''}`) ? 'ctrl+shift+v' : 'ctrl+v');
    await sleep(250);
    if (prev) await this.setClipboard(prev).catch(() => {});
  }

  // ---------- windows / apps ----------
  async activeWindow() {
    if (this.hypr) { const r = await run('hyprctl', ['activewindow', '-j']); try { const w = JSON.parse(r.stdout); if (w && w.title !== undefined) return { title: w.title, class: w.class, pid: w.pid, address: w.address }; } catch { /* none */ } return { title: '', class: '' }; }
    if (this.session === 'x11' && which('xdotool')) { const r = await run('xdotool', ['getactivewindow', 'getwindowname']); const c = await run('xdotool', ['getactivewindow', 'getwindowclassname']); return { title: r.stdout.trim(), class: c.stdout.trim() }; }
    return { title: '', class: '' };
  }

  async listWindows() {
    if (this.hypr) { const r = await run('hyprctl', ['clients', '-j']); try { return JSON.parse(r.stdout).filter((w) => w.mapped).map((w) => ({ title: w.title, class: w.class, workspace: w.workspace && w.workspace.name })); } catch { return []; } }
    if (which('wmctrl')) { const r = await run('wmctrl', ['-lx']); return r.stdout.split('\n').filter(Boolean).map((l) => { const p = l.split(/\s+/); return { class: p[2], title: p.slice(4).join(' ') }; }); }
    return [];
  }

  async focusWindow(q) {
    const esc = String(q).replace(/[.*+?^${}()|[\]\\"]/g, '\\$&');
    if (this.hypr) {
      for (const sel of [`title:(?i).*${esc}.*`, `class:(?i).*${esc}.*`]) {
        const r = await run('hyprctl', ['dispatch', `hl.dsp.focus({window="${sel.replace(/\\/g, '\\\\')}"})`]);
        if (!/not found|error/i.test(r.stdout + r.stderr)) return true;
        const r2 = await run('hyprctl', ['dispatch', 'focuswindow', sel]);
        if (r2.code === 0 && !/error|not found/i.test(r2.stdout)) return true;
      }
      return false;
    }
    if (which('wmctrl')) { const r = await run('wmctrl', ['-a', q]); return r.code === 0; }
    if (which('xdotool')) { const r = await run('xdotool', ['search', '--name', q, 'windowactivate']); return r.code === 0; }
    return false;
  }

  // Application launching: .desktop files first, then the plain command.
  async findDesktop(name) {
    const dirs = [path.join(os.homedir(), '.local/share/applications'), '/usr/share/applications', '/var/lib/flatpak/exports/share/applications', path.join(os.homedir(), '.local/share/flatpak/exports/share/applications')];
    const q = name.toLowerCase(); let best = null;
    for (const d of dirs) {
      let files = []; try { files = fs.readdirSync(d).filter((f) => f.endsWith('.desktop')); } catch { continue; }
      for (const f of files) {
        let txt = ''; try { txt = fs.readFileSync(path.join(d, f), 'utf8'); } catch { continue; }
        if (/^NoDisplay=true/m.test(txt)) continue;
        const n = (/^Name=(.*)$/m.exec(txt) || [])[1] || ''; const ex = (/^Exec=(.*)$/m.exec(txt) || [])[1] || '';
        const nl = n.toLowerCase(); const id = f.replace(/\.desktop$/, '').toLowerCase();
        const score = nl === q || id === q ? 3 : nl.startsWith(q) || id.startsWith(q) ? 2 : nl.includes(q) || id.includes(q) ? 1 : 0;
        if (score && (!best || score > best.score)) best = { score, name: n, id: f.replace(/\.desktop$/, ''), exec: ex.replace(/%[a-zA-Z]/g, '').trim() };
      }
    }
    return best;
  }

  async openApp(name) {
    const d = await this.findDesktop(name);
    const cmd = d ? d.exec : name;
    const label = d ? d.name : name;
    if (!d && !which(name.split(' ')[0])) return `error: no application named "${name}" found`;
    if (this.hypr) { await run('hyprctl', ['dispatch', `hl.dsp.exec_cmd(${JSON.stringify(cmd)})`]); }
    else { const c = spawn('sh', ['-c', cmd], { detached: true, stdio: 'ignore' }); c.unref(); }
    await sleep(1200);
    return `opened ${label}`;
  }

  async openUrl(url) { const c = spawn(which('xdg-open') || 'xdg-open', [url], { detached: true, stdio: 'ignore' }); c.unref(); await sleep(1000); return 'opened'; }

  async shell(cmd, { timeout = 60000, cwd } = {}) {
    const r = await run('bash', ['-lc', cmd], { timeout, cwd: cwd || os.homedir() });
    return { code: r.code, stdout: r.stdout, stderr: r.stderr, error: r.error };
  }

  dispose() { try { if (this.helper) this.helper.stdin.end(); } catch { /* ignore */ } }
}

module.exports = { Linux };
