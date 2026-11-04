'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const img = require('./img');
const { sleep } = require('./platform/util');

const DANGEROUS = [
  /\brm\s+(-[a-zA-Z]*[rR][a-zA-Z]*|--recursive)\b/, /\brm\s+-[a-zA-Z]*f/, /\bsudo\b/, /\bmkfs\b/, /\bdd\s+.*\bof=\/dev\//, /\b(shutdown|reboot|poweroff|halt)\b/, />\s*\/dev\/sd/, /\bchmod\s+-R\b/, /\bchown\s+-R\b/,
  /:\(\)\s*\{/, /\|\s*(sudo\s+)?(ba|z)?sh\b/, /\bgit\s+push\s+.*--force/, /\bgit\s+reset\s+--hard/, /\b(pacman|yay|paru)\s+-R/, /\b(apt|apt-get|dnf|yum)\s+(remove|purge|autoremove)/, /\bpip\s+uninstall/, /\bnpm\s+(uninstall|rm)\s+-g/,
  /\bkill(all)?\s+-9/, /\bpkill\b/, /\bformat\s+[a-zA-Z]:/i, /\bRemove-Item\b.*-Recurse/i, /\bdel\s+\/[sq]/i, /\brd\s+\/s/i, /\bdiskpart\b/i, /\breg\s+(delete|add)\b/i, /\bStop-Computer|Restart-Computer/i,
];
const PROTECTED = process.platform === 'win32' ? /^[a-zA-Z]:\\(Windows|Program Files)/i : /^\/(etc|usr|boot|bin|sbin|lib|lib64|var|sys|proc|dev|root)(\/|$)/;

const expand = (p) => (p.startsWith('~') ? path.join(os.homedir(), p.slice(1)) : p);
const cut = (s, n) => { s = String(s == null ? '' : s); return s.length > n ? s.slice(0, n) + `\n…(${s.length - n} more characters)` : s; };

function buildTools(ctx) {
  const T = [];
  const P = (type, description = '') => ({ type, description });
  const add = (name, description, props, required, run, opts = {}) => T.push({ name, description, props, required: required || [], run, gui: !!opts.gui });
  const toNative = (x, y) => { const s = ctx.shotInfo; if (!s) throw new Error('no screenshot yet: call screenshot first'); return [x * s.nativeW / s.w, y * s.nativeH / s.h]; };
  const confirmGate = (what, input) => (ctx.config.get('confirmDestructive') && !input.confirmed ? `blocked: ${what} could be destructive or irreversible. Ask the user with ask_user (say exactly what it will do); if they agree, call again with confirmed=true. If the user explicitly asked for exactly this, you may set confirmed=true right away.` : '');

  add('plan', 'Set your GOAL and to-do list. Call it FIRST in every task (forced on your first turn), again whenever a step is done/started or the user changes the task. Always send everything.',
    { screen_now: P('string', 'FIRST look at the screenshot: what is on screen (apps, windows) and what matters for the task? one or two sentences'), goal: P('string', 'the concrete end result the user wants, one sentence'), done_when: P('string', 'what exactly must be visible/true to prove the goal is reached'),
      items: { type: 'array', items: { type: 'object', properties: { text: P('string'), status: { type: 'string', enum: ['pending', 'doing', 'done', 'skipped'] } }, required: ['text', 'status'] } } },
    ['screen_now', 'goal', 'done_when', 'items'], (i) => {
      if (i.screen_now) ctx.log('👁 ' + i.screen_now);
      if (i.goal) ctx.state.goal = String(i.goal).slice(0, 240);
      if (i.done_when) ctx.state.doneWhen = String(i.done_when).slice(0, 240);
      if (ctx.state.goal) ctx.log('🎯 ' + ctx.state.goal + (ctx.state.doneWhen ? '  ✔ ' + ctx.state.doneWhen : ''));
      ctx.state.todos = (Array.isArray(i.items) ? i.items : []).map((t) => ({ text: String(t.text || '').slice(0, 120), status: t.status || 'pending' }));
      ctx.emit('plan', ctx.state);
      return `plan updated (${ctx.state.todos.filter((t) => t.status === 'done').length}/${ctx.state.todos.length} done)`;
    });

  add('ask_user', 'Ask the user a short question out loud and wait for their spoken or typed answer. Use when the request is unclear or does not match what you see, when several options fit, when something unexpected blocks you (login, captcha, permission), before destructive actions, or after two failed approaches.',
    { question: P('string') }, ['question'], async (i) => { const a = await ctx.ask(String(i.question)); return a == null ? 'error: the user did not answer. Make your best guess or finish.' : 'User answered: ' + a; });

  add('remember', "Save a lasting fact about the user, very short (<= 80 chars), e.g. 'mom = Anna Peeters', 'I am Warre'. Only lasting things the user states; never passwords.", { fact: P('string') }, ['fact'], (i) => ctx.remember(String(i.fact)));
  add('learn', 'After the user CORRECTED you ("no, I meant…", "not that one"), save ONE short general rule so you never repeat the mistake, e.g. "when I say browser I mean Firefox", "downloads go in ~/Downloads". Specific, <= 120 chars, no passwords. Call it after you fixed the mistake.', { lesson: P('string') }, ['lesson'], (i) => ctx.learn(String(i.lesson)));
  add('forget', 'Delete saved facts containing this text.', { text: P('string') }, ['text'], (i) => ctx.forget(String(i.text)));

  add('screenshot', 'Take a fresh screenshot of the current screen (use after waiting, or when you need to see the result of a non-GUI action).', {}, [], async () => { ctx.wantShot = true; return 'screenshot attached below'; });
  add('zoom', 'Zoom into a region of the screen at higher resolution to read small text. Coordinates are in screenshot pixels.', { x: P('integer'), y: P('integer'), w: P('integer'), h: P('integer') }, ['x', 'y', 'w', 'h'], async (i) => {
    const s = await ctx.freshShot(); const k = s.nativeW / s.w;
    ctx.pendingImages.push(img.crop(s.png, i.x * k, i.y * k, i.w * k, i.h * k)); return 'zoomed region attached below';
  });

  const clickTool = (name, desc, button, count) => add(name, desc, { x: P('integer', 'x in screenshot pixels'), y: P('integer', 'y in screenshot pixels') }, ['x', 'y'], async (i) => {
    const [nx, ny] = toNative(i.x, i.y); await ctx.platform.click(nx, ny, { button, count }); return 'ok';
  }, { gui: true });
  clickTool('click', 'Left-click at a point in the latest screenshot (screenshot pixel coordinates).', 'left', 1);
  clickTool('double_click', 'Double-click at a point.', 'left', 2);
  clickTool('right_click', 'Right-click at a point.', 'right', 1);
  add('move', 'Move the mouse pointer (hover) to a point.', { x: P('integer'), y: P('integer') }, ['x', 'y'], async (i) => { const [nx, ny] = toNative(i.x, i.y); await ctx.platform.move(nx, ny); return 'ok'; }, { gui: true });
  add('drag', 'Drag with the left button from (x1,y1) to (x2,y2) in screenshot pixels.', { x1: P('integer'), y1: P('integer'), x2: P('integer'), y2: P('integer') }, ['x1', 'y1', 'x2', 'y2'], async (i) => {
    const [a, b] = toNative(i.x1, i.y1), [c, d] = toNative(i.x2, i.y2); await ctx.platform.drag(a, b, c, d); return 'ok';
  }, { gui: true });
  add('scroll', 'Scroll the content under the point. dy > 0 scrolls down (3 = about one screenful of a small list), dy < 0 up; dx for sideways.', { x: P('integer'), y: P('integer'), dy: P('integer'), dx: P('integer') }, ['dy'], async (i) => {
    ctx.scrollStreak = (ctx.scrollStreak || 0) + 1;
    if (ctx.scrollStreak > 3) return 'error: you scrolled 4 times in a row without acting. STOP scrolling: re-read the screenshot, say where the target should be, then use search, another window/tab, shell, open_url or ask_user.';
    const p = i.x != null && i.y != null ? toNative(i.x, i.y) : [null, null]; await ctx.platform.scroll(p[0], p[1], i.dx || 0, i.dy || 0); return 'ok';
  }, { gui: true });
  add('type', 'Type text into the focused field (layout-independent; replaces nothing: click the field first). submit=true presses Enter afterwards.', { text: P('string'), submit: P('boolean') }, ['text'], async (i) => {
    await ctx.platform.typeText(String(i.text)); if (i.submit) { await sleep(120); await ctx.platform.key('enter'); } return 'typed';
  }, { gui: true });
  add('key', 'Press a key or combination, e.g. "Return", "Escape", "ctrl+l", "ctrl+shift+t", "alt+Tab", "super+1", "F5", "ctrl+a". Letters/digits refer to the physical key.', { key: P('string') }, ['key'], async (i) => { await ctx.platform.key(String(i.key)); return 'ok'; }, { gui: true });
  add('wait', 'Wait for something to load.', { seconds: P('number') }, [], async (i) => { await sleep(Math.max(0.2, Math.min(15, Number(i.seconds) || 1)) * 1000); return 'waited'; });

  add('shell', 'Run a shell command (bash on Linux, PowerShell on Windows) and get its output. Much faster and cheaper than clicking: use for files, git, packages, system info, opening things, scripts. Destructive commands need confirmed=true after asking the user.',
    { command: P('string'), timeout_seconds: P('integer', 'default 60, max 300'), confirmed: P('boolean') }, ['command'], async (i) => {
      const cmd = String(i.command); const g = DANGEROUS.some((r) => r.test(cmd)) ? confirmGate('this command', i) : ''; if (g) return g;
      const r = await ctx.platform.shell(cmd, { timeout: Math.min(300, Number(i.timeout_seconds) || 60) * 1000 });
      return `exit ${r.code}${r.error ? ' (' + r.error + ')' : ''}\n` + cut(String(r.stdout || '').trim(), 5000) + (r.stderr ? '\nstderr: ' + cut(String(r.stderr).trim(), 1500) : '');
    });
  add('open_app', 'Launch an installed application by name (uses desktop entries / Start menu).', { name: P('string') }, ['name'], async (i) => { ctx.wantShot = true; return ctx.platform.openApp(String(i.name)); });
  add('open_url', 'Open a link or file in the default app (https://, mailto:, file path).', { url: P('string') }, ['url'], async (i) => { ctx.wantShot = true; return ctx.platform.openUrl(String(i.url)); });
  add('windows', 'List the open windows (class + title).', {}, [], async () => (await ctx.platform.listWindows()).map((w) => `${w.class} - ${w.title}`).join('\n') || '(none)');
  add('focus_window', 'Bring a window to the front by (part of) its title or class.', { query: P('string') }, ['query'], async (i) => { const ok = await ctx.platform.focusWindow(String(i.query)); ctx.wantShot = true; return ok ? 'focused' : 'error: no window matches ' + i.query; });
  add('read_file', 'Read a text file (first 20000 characters).', { path: P('string') }, ['path'], async (i) => { try { return cut(fs.readFileSync(expand(String(i.path)), 'utf8'), 20000); } catch (e) { return 'error: ' + e.message; } });
  add('write_file', 'Write (or append to) a text file; creates folders. System folders are blocked unless confirmed=true.', { path: P('string'), content: P('string'), append: P('boolean'), confirmed: P('boolean') }, ['path', 'content'], async (i) => {
    const p = path.resolve(expand(String(i.path))); if (PROTECTED.test(p)) { const g = confirmGate('writing to a system location', i); if (g) return g; }
    try { fs.mkdirSync(path.dirname(p), { recursive: true }); (i.append ? fs.appendFileSync : fs.writeFileSync)(p, String(i.content)); return `wrote ${String(i.content).length} characters to ${p}`; } catch (e) { return 'error: ' + e.message; }
  });
  add('get_clipboard', 'Read the clipboard text.', {}, [], async () => cut(await ctx.platform.getClipboard(), 4000) || '(empty)');
  add('set_clipboard', 'Put text on the clipboard.', { text: P('string') }, ['text'], async (i) => { await ctx.platform.setClipboard(String(i.text)); return 'ok'; });
  add('draw', 'Draw a clear visual for the user on a whiteboard window: diagrams, flows, maps, comparisons, step-by-step schemes. Give 1-6 frames (shown as slides with captions) of SVG (viewBox 0 0 800 500, no scripts, no images). Use dark strokes and fills on the light background, accent colour #4f6bed, readable text (font-size >= 20), generous spacing, arrows with marker-end, labels near shapes. Use it when the user asks to explain, teach, sketch or visualise something.',
    { title: P('string'), frames: { type: 'array', items: { type: 'object', properties: { svg: P('string', 'complete <svg viewBox="0 0 800 500">…</svg>'), caption: P('string', 'one or two sentences explaining this frame') }, required: ['svg'] } } }, ['title', 'frames'], async (i) => {
      const { sanitizeSvg } = require('./svg'); const frames = (Array.isArray(i.frames) ? i.frames : []).slice(0, 6).map((f) => ({ svg: sanitizeSvg(f.svg), caption: String(f.caption || '').slice(0, 300) })).filter((f) => f.svg);
      if (!frames.length) return 'error: no valid SVG (it must start with <svg viewBox="0 0 800 500"> and use only shapes and text)';
      ctx.emit('draw', { title: String(i.title || '').slice(0, 80), frames }); return `showing ${frames.length} frame(s) on the whiteboard`;
    });
  add('highlight', 'Show the user something on their screen: dims the screen and outlines the given rectangles with an optional label. Use for "show me / where is / which one" requests. Coordinates are screenshot pixels. Afterwards finish with a short spoken explanation.',
    { items: { type: 'array', items: { type: 'object', properties: { x: P('integer'), y: P('integer'), w: P('integer'), h: P('integer'), label: P('string') }, required: ['x', 'y', 'w', 'h'] } }, seconds: P('integer', 'default 10') }, ['items'], async (i) => {
      const list = [];
      for (const it of i.items || []) { const [nx, ny] = toNative(it.x, it.y); const [nw, nh] = [it.w * ctx.shotInfo.nativeW / ctx.shotInfo.w, it.h * ctx.shotInfo.nativeH / ctx.shotInfo.h]; const g = ctx.platform.toGlobal(nx, ny); const g2 = ctx.platform.toGlobal(nx + nw, ny + nh); list.push({ x: g.x, y: g.y, w: g2.x - g.x, h: g2.y - g.y, label: it.label || '' }); }
      if (!list.length) return 'error: nothing to highlight'; ctx.emit('highlight', { items: list, seconds: i.seconds || 10 }); ctx.wantShot = true; return `highlighted ${list.length} area(s); check the screenshot, redo if off`;
    });
  return T;
}

const specFor = (t) => ({ name: t.name, description: t.description, input_schema: { type: 'object', properties: { ...t.props, why: { type: 'string', description: 'one short sentence: how this serves the goal' } }, required: t.required } });

module.exports = { buildTools, specFor, DANGEROUS };
