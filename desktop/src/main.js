'use strict';
const { app, BrowserWindow, ipcMain, screen, globalShortcut, Tray, Menu, nativeTheme, clipboard, desktopCapturer, session, nativeImage, systemPreferences, shell } = require('electron');
const path = require('path');
const os = require('os');
const fs = require('fs');
const { Config } = require('./config');
const { Usage, money, k } = require('./usage');
const { Agent, STOP_WORDS, norm } = require('./agent');
const platformFactory = require('./platform');
const { detectAccent } = require('./theme');
const { Tts } = require('./tts');
const { Stt, fixNames } = require('./stt');
const { run, which } = require('./platform/util');
const { toHypr } = require('./hotkey');

const ARGS = process.argv.slice(1);
const flag = (f) => ARGS.includes(f);
if (!app.requestSingleInstanceLock()) { if (!flag('--no-single')) { app.quit(); process.exit(0); } }

let config, usage, platform, agent, tts, stt, theme = { accent: '#7aa2f7', source: 'default' };
let mainWin, cardWin, fxWin, audioWin, tray;
const S = { voice: false, tempVoice: false, speaking: false, status: '', result: '', resultUntil: 0, asking: '', partial: '', micLevel: 0, sttProgress: -1, sttReady: false, cardVisible: false, lastCmdAt: 0 };
const win = (w) => w && !w.isDestroyed() && w.webContents && !w.webContents.isDestroyed();
const send = (w, ch, ...a) => { if (win(w)) w.webContents.send(ch, ...a); };
const broadcast = (ch, ...a) => [mainWin, cardWin, fxWin].forEach((w) => send(w, ch, ...a));
const log = (m) => { console.log('[assistant]', m); send(mainWin, 'log', m); };
const isHypr = () => !!process.env.HYPRLAND_INSTANCE_SIGNATURE && !!which('hyprctl');

// ---------- Hyprland: float/pin the overlay windows without touching the user's config ----------
async function installHyprRules() {
  if (!isHypr()) return;
  const common = 'float=true, pin=true, no_anim=true, no_shadow=true, border_size=0, rounding=0, no_blur=true';
  const lua = `
    hl.window_rule({ name = "assistant-card", match = { title = "^Assistant Card$" }, ${common} })
    hl.window_rule({ name = "assistant-fx", match = { title = "^Assistant FX$" }, ${common}, no_focus = true })
    return 1`;
  const r = await run('hyprctl', ['eval', lua]);
  if (/error/i.test(r.stdout + r.stderr)) { // older Hyprland (hyprlang) rules
    for (const rule of ['float,title:^(Assistant Card)$', 'pin,title:^(Assistant Card)$', 'noanim,title:^(Assistant Card)$', 'noshadow,title:^(Assistant Card)$', 'noborder,title:^(Assistant Card)$', 'float,title:^(Assistant FX)$', 'pin,title:^(Assistant FX)$', 'noanim,title:^(Assistant FX)$', 'noshadow,title:^(Assistant FX)$', 'noborder,title:^(Assistant FX)$', 'nofocus,title:^(Assistant FX)$']) await run('hyprctl', ['keyword', 'windowrulev2', rule]);
  }
}

let hyprBound = '';
async function bindHypr(accel) {
  if (!isHypr()) return 'not Hyprland';
  const cmd = `${launchCommand()} --toggle`; const key = toHypr(accel); if (!key) return 'error: empty key';
  let lua = '';
  if (hyprBound && hyprBound !== key) lua += `pcall(function() hl.unbind(${JSON.stringify(hyprBound)}) end) `;
  lua += `hl.bind(${JSON.stringify(key)}, hl.dsp.exec_cmd(${JSON.stringify(cmd)})) return 1`;
  const r = await run('hyprctl', ['eval', lua]);
  if (/error/i.test(r.stdout + r.stderr)) return 'error: ' + (r.stdout + r.stderr).slice(0, 200);
  hyprBound = key; return 'ok';
}

function launchCommand() {
  if (process.env.APPIMAGE) return `"${process.env.APPIMAGE}"`;
  return app.isPackaged ? `"${process.execPath}"` : `"${process.execPath}" "${app.getAppPath()}" --no-sandbox`;
}

// ---------- windows ----------
const preload = path.join(__dirname, 'preload.js');
function baseWebPrefs() { return { preload, contextIsolation: true, nodeIntegration: false, backgroundThrottling: false }; }

function createMain() {
  mainWin = new BrowserWindow({ width: 980, height: 760, minWidth: 760, minHeight: 560, title: 'Assistant', icon: path.join(__dirname, '..', 'assets', 'icon.png'), show: !flag('--hidden') && !flag('--toggle') && !flag('--ask'), backgroundColor: nativeTheme.shouldUseDarkColors ? '#16161a' : '#fbfbfd', autoHideMenuBar: true, webPreferences: baseWebPrefs() });
  mainWin.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  mainWin.on('close', (e) => { if (!app.isQuitting && tray) { e.preventDefault(); mainWin.hide(); } });
}

async function focusedMonitor() { try { const m = await platform.monitor(); return { x: m.x, y: m.y, w: Math.round(m.lw), h: Math.round(m.lh) }; } catch { const d = screen.getPrimaryDisplay(); return { x: d.bounds.x, y: d.bounds.y, w: d.bounds.width, h: d.bounds.height }; } }

async function showCard(focus) {
  const m = await focusedMonitor(); const w = 600, h = S.cardH || 190;
  if (!win(cardWin)) {
    cardWin = new BrowserWindow({ width: w, height: h, x: m.x + Math.round((m.w - w) / 2), y: m.y + m.h - h - 36, title: 'Assistant Card', frame: false, transparent: true, resizable: false, alwaysOnTop: true, skipTaskbar: true, hasShadow: false, show: false, focusable: true, backgroundColor: '#00000000', webPreferences: baseWebPrefs() });
    cardWin.setAlwaysOnTop(true, 'screen-saver');
    await cardWin.loadFile(path.join(__dirname, 'renderer', 'card.html'));
    cardWin.on('closed', () => { cardWin = null; S.cardVisible = false; });
  } else cardWin.setBounds({ x: m.x + Math.round((m.w - w) / 2), y: m.y + m.h - cardWin.getBounds().height - 36, width: w, height: cardWin.getBounds().height });
  if (focus) { cardWin.show(); cardWin.focus(); } else cardWin.showInactive();
  S.cardVisible = true; pushState();
}

async function showFx() {
  if (!config.get('overlay')) return;
  const m = await focusedMonitor();
  if (!win(fxWin)) {
    fxWin = new BrowserWindow({ x: m.x, y: m.y, width: m.w, height: m.h, title: 'Assistant FX', frame: false, transparent: true, resizable: false, movable: false, alwaysOnTop: true, skipTaskbar: true, hasShadow: false, show: false, focusable: false, backgroundColor: '#00000000', webPreferences: baseWebPrefs() });
    fxWin.setIgnoreMouseEvents(true); fxWin.setAlwaysOnTop(true, 'screen-saver');
    await fxWin.loadFile(path.join(__dirname, 'renderer', 'fx.html'));
    fxWin.on('closed', () => { fxWin = null; });
  } else fxWin.setBounds({ x: m.x, y: m.y, width: m.w, height: m.h });
  fxWin.showInactive(); pushState();
}

function hideOverlay() { if (win(cardWin)) cardWin.hide(); if (win(fxWin)) fxWin.hide(); S.cardVisible = false; }

let audioReady = Promise.resolve();
function createAudio() {
  audioWin = new BrowserWindow({ show: false, width: 200, height: 100, webPreferences: baseWebPrefs() });
  audioReady = new Promise((res) => audioWin.webContents.once('did-finish-load', res));
  audioWin.loadFile(path.join(__dirname, 'renderer', 'audio.html'));
}

// ---------- overlay state ----------
function overlayState() {
  const todos = agent ? agent.state.todos : []; const cur = todos.find((t) => t.status === 'doing') || todos.find((t) => t.status === 'pending');
  const sub = cur ? `${todos.filter((t) => t.status === 'done').length + 1}/${todos.length} · ${cur.text}` : '';
  const prog = todos.length ? (todos.filter((t) => t.status === 'done').length + 0.3) / todos.length : 0;
  const fresh = Date.now() < S.resultUntil; const running = agent && agent.running;
  let o;
  if (S.asking) o = { mode: 'asking', title: 'Question', text: S.asking, sub: S.partial || (S.voice ? 'Say your answer…' : 'Type your answer'), progress: 0 };
  else if (running) o = { mode: 'working', title: S.voice ? 'Working · listening' : 'Working', text: S.status || 'Thinking…', sub: S.partial ? '🎤 ' + S.partial : sub, progress: prog };
  else if (S.voice) o = { mode: 'listening', title: fresh ? 'Done' : 'Listening', text: fresh ? S.result : S.sttReady || config.get('whisperModel') ? (S.partial || 'Say what I should do…') : '', sub: S.sttProgress >= 0 && S.sttProgress < 1 ? `Loading speech model… ${Math.round(S.sttProgress * 100)}%` : fresh ? 'Listening for your next command' : '', progress: 0 };
  else if (fresh) o = { mode: 'info', title: 'Done', text: S.result, sub: '', progress: 0 };
  else if (S.cardVisible) o = { mode: 'idle', title: 'Assistant', text: 'What should I do?', sub: 'Type, or press the mic', progress: 0 };
  else o = null;
  return o;
}
let pushTimer = null;
function pushState() { if (pushTimer) return; pushTimer = setTimeout(() => { pushTimer = null; const o = overlayState(); if (!o) { if (!S.keepCard) hideOverlay(); return; } o.level = S.micLevel; o.voice = S.voice; send(cardWin, 'state', o); send(fxWin, 'state', o); send(mainWin, 'status', { voice: S.voice, running: !!(agent && agent.running), text: o.text, mode: o.mode }); }, 30); }

// ---------- voice ----------
function micCmd(cmd) { audioReady.then(() => send(audioWin, 'audio:cmd', cmd)); }
async function startVoice(temp) {
  if (S.voice) return;
  S.voice = true; S.tempVoice = !!temp; S.partial = '';
  stt.start(); micCmd({ cmd: 'start' }); await showCard(false); await showFx(); pushState();
}
function endVoice(stopAgent) {
  if (!S.voice && !stopAgent) return;
  S.voice = false; S.tempVoice = false; S.partial = ''; micCmd({ cmd: 'stop' }); tts.stop(); S.speaking = false;
  if (stopAgent && agent) agent.stop();
  pushState(); setTimeout(pushState, 600);
}
function speak(t) {
  if (!config.get('speak') || !tts.available()) return;
  S.speaking = true; micCmd({ cmd: 'mute', on: true });
  tts.speak(t).finally(() => { S.speaking = false; micCmd({ cmd: 'mute', on: false }); });
}

const HALLUCINATIONS = /^(ondertitel|ondertiteld|vertaling|bedankt voor het kijken|thanks for watching|subtitles by|untertitel|amara\.org|\.+|ja\.?|you\.?|oké\.?)/i;
async function onUtterance(buf) {
  if (S.speaking) return;
  try {
    const f32 = new Float32Array(buf.buffer ? buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength) : buf);
    S.partial = '…'; pushState();
    let text = await stt.transcribe(f32); S.partial = '';
    text = fixNames(text, config.names()).trim();
    if (!text || text.length < 2 || HALLUCINATIONS.test(text)) { pushState(); return; }
    handleText(text, true);
  } catch (e) { log('Speech error: ' + e.message); S.partial = ''; pushState(); }
}

function handleText(text, fromVoice) {
  const n = norm(text).replace(/[^\p{L}\p{N}' ]/gu, '').trim();
  if (fromVoice && STOP_WORDS.has(n)) { log('🎤 "' + text + '" → stop'); endVoice(true); return; }
  log((fromVoice ? '🎤 ' : '⌨ ') + text);
  if (agent.answer(text)) { S.asking = ''; if (S.tempVoice) endVoice(false); pushState(); return; }
  if (agent.running) { agent.interrupt(text); return; }
  runTask(text);
}

async function runTask(text) {
  if (!S.cardVisible) await showCard(false);
  await showFx();
  agent.run(text);
}

// ---------- IPC ----------
function registerIpc() {
  ipcMain.handle('cfg:all', () => ({ cfg: config.all(), theme, dark: nativeTheme.shouldUseDarkColors, platform: platform.describe(), problems: platform.problems(), tts: tts.describe(), hypr: isHypr(), launch: launchCommand(), usage: usage.summary(), facts: config.facts() }));
  ipcMain.handle('cfg:set', async (_e, patch) => {
    config.set(patch);
    if ('accent' in patch) { theme = await detectAccent({ systemPreferences }, config.get('accent')); broadcast('theme', theme, nativeTheme.shouldUseDarkColors); }
    if ('hotkey' in patch) { registerHotkey(); if (isHypr() && patch.hotkey) { const r = await bindHypr(patch.hotkey); if (r !== 'ok') broadcast('toast', 'Could not bind in Hyprland: ' + r); } }
    if ('balance' in patch) usage.setBalance(patch.balance);
    if ('whisperModel' in patch || 'lang' in patch) { if (stt.worker) { stt.stop(); } }
    broadcast('usage', usage.summary()); return true;
  });
  ipcMain.handle('ui:run', (_e, text) => { handleText(String(text), false); return true; });
  ipcMain.handle('ui:stop', () => { endVoice(true); return true; });
  ipcMain.handle('ui:voice', () => { S.voice ? endVoice(false) : startVoice(false); return true; });
  ipcMain.handle('ui:interrupt', (_e, t) => agent.interrupt(String(t)));
  ipcMain.handle('ui:test-voice', () => { speak(config.get('lang') === 'en' ? 'Hi! This is my voice. This is how I sound when I answer you.' : 'Hoi! Dit is mijn stem. Zo klink ik als ik je een antwoord geef.'); return true; });
  ipcMain.handle('ui:warm-stt', () => { stt.start(); return true; });
  ipcMain.handle('sys:check', async () => { const r = []; r.push({ name: 'Screenshots', ok: !!(await platform.screenshot().then(() => true).catch(() => false)) }); return r; });
  ipcMain.handle('hypr:bind', async (_e, accel) => bindHypr(accel || config.get('hotkey')));
  ipcMain.handle('hotkey:hypr', (_e, accel) => toHypr(accel));
  ipcMain.handle('hotkey:pause', (_e, on) => { try { if (on) globalShortcut.unregisterAll(); else registerHotkey(); } catch { /* ignore */ } return true; });
  ipcMain.on('audio:utterance', (_e, buf) => onUtterance(buf));
  ipcMain.on('audio:level', (_e, v) => { if (!S.loggedLevel && v >= 0) { S.loggedLevel = true; console.log('[assistant] mic level events flowing'); } S.micLevel = v; if (S.voice) send(cardWin, 'level', v); send(fxWin, 'level', v); });
  ipcMain.on('audio:error', (_e, m) => { log('Microphone: ' + m); S.partial = ''; broadcast('toast', 'Microphone: ' + m); });
  ipcMain.on('card:height', async (_e, h) => { if (win(cardWin)) { const b = cardWin.getBounds(); const m = await focusedMonitor(); S.cardH = Math.round(h); cardWin.setBounds({ x: b.x, y: m.y + m.h - S.cardH - 36, width: b.width, height: S.cardH }); } });
  ipcMain.on('card:close', () => { S.cardVisible = false; endVoice(false); hideOverlay(); });
  ipcMain.on('win:main', () => { if (win(mainWin)) { mainWin.show(); mainWin.focus(); } });
}

function registerHotkey() {
  try { globalShortcut.unregisterAll(); const key = config.get('hotkey'); if (key && !process.env.WAYLAND_DISPLAY) globalShortcut.register(key, () => toggle()); } catch { /* unsupported (Wayland): use the CLI flag from a compositor bind */ }
}

async function toggle() { if (S.voice) endVoice(agent.running ? false : false); else { S.keepCard = true; await startVoice(false); } }

function handleArgs(args) {
  if (args.includes('--toggle')) toggle();
  else if (args.includes('--ask')) { S.keepCard = true; showCard(true).then(showFx); }
  else if (args.includes('--stop')) endVoice(true);
  else if (args.includes('--show')) { mainWin.show(); mainWin.focus(); }
  const ri = args.indexOf('--run'); if (ri >= 0 && args[ri + 1]) handleText(args[ri + 1], false);
}

// ---------- updates from GitHub releases ----------
const vnum = (v) => String(v).replace(/^\D+/, '').split('.').map((x) => parseInt(x, 10) || 0);
const newer = (a, b) => { const x = vnum(a), y = vnum(b); for (let i = 0; i < 3; i++) { if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) > (y[i] || 0); } return false; };
async function checkUpdate(manual) {
  try {
    const r = await fetch('https://api.github.com/repos/veelvoer/phone-assistant/releases?per_page=30', { headers: { Accept: 'application/vnd.github+json' }, signal: AbortSignal.timeout(15000) });
    const list = await r.json(); const rel = list.find((x) => /^desktop-v/.test(x.tag_name) && !x.draft);
    if (rel && newer(rel.tag_name, app.getVersion())) { send(mainWin, 'update', { version: rel.tag_name.replace(/^desktop-v/, ''), url: rel.html_url, notes: String(rel.body || '').slice(0, 400) }); return true; }
    if (manual) send(mainWin, 'toast', `You're up to date (v${app.getVersion()})`);
  } catch (e) { if (manual) send(mainWin, 'toast', 'Update check failed: ' + e.message); }
  return false;
}
ipcMain.handle('update:check', () => checkUpdate(true));
ipcMain.handle('update:open', (_e, url) => { if (/^https:\/\/github\.com\/veelvoer\//.test(url)) shell.openExternal(url); return true; });

// ---------- start ----------
app.whenReady().then(async () => {
  config = new Config(path.join(app.getPath('userData'), 'config.json'));
  usage = new Usage(config);
  platform = await platformFactory.create({ screen, desktopCapturer, clipboard }).init();
  tts = new Tts(config);
  stt = new Stt(config, path.join(app.getPath('userData'), 'models'));
  stt.on('progress', (v) => { S.sttProgress = v; pushState(); send(mainWin, 'stt', { progress: v }); });
  stt.on('ready', () => { S.sttReady = true; S.sttProgress = 1; pushState(); send(mainWin, 'stt', { ready: true }); });
  stt.on('error', (e) => log('Speech engine: ' + e.message));
  agent = new Agent({ config, platform, usage, speak, ask: null });
  agent.on('log', log);
  agent.on('start', () => { S.status = ''; S.resultUntil = 0; pushState(); send(mainWin, 'plan', agent.state); });
  agent.on('status', (t) => { S.status = t; pushState(); });
  agent.on('plan', () => { send(mainWin, 'plan', agent.state); pushState(); });
  agent.on('usage', (u) => broadcast('usage', u));
  agent.on('asking', async (q) => { S.asking = q; if (!S.cardVisible) await showCard(true); await showFx(); if (!S.voice) startVoice(true); pushState(); });
  agent.on('result', (m) => { S.result = m; S.resultUntil = Date.now() + 5000; pushState(); setTimeout(pushState, 5200); });
  agent.on('highlight', (d) => { const b = win(fxWin) ? fxWin.getBounds() : { x: 0, y: 0 }; send(fxWin, 'highlight', { seconds: d.seconds, items: d.items.map((i) => ({ ...i, x: i.x - b.x, y: i.y - b.y })) }); });
  agent.on('facts', () => send(mainWin, 'facts', config.facts()));
  agent.on('end', () => { S.asking = ''; S.status = ''; send(mainWin, 'plan', agent.state); pushState(); setTimeout(pushState, 5200); if (!S.voice && !S.keepCard) setTimeout(() => { if (!agent.running && !S.voice) hideOverlay(); }, 5400); });

  theme = await detectAccent({ systemPreferences }, config.get('accent'));
  session.defaultSession.setPermissionRequestHandler((_wc, perm, cb) => cb(perm === 'media' || perm === 'audioCapture'));
  session.defaultSession.setPermissionCheckHandler(() => true);
  registerIpc(); createMain(); createAudio(); await installHyprRules(); registerHotkey();
  nativeTheme.on('updated', () => broadcast('theme', theme, nativeTheme.shouldUseDarkColors));
  try { tray = new Tray(nativeImage.createFromPath(path.join(__dirname, '..', 'assets', 'tray.png')).resize({ width: 22, height: 22 })); tray.setToolTip('Assistant'); tray.setContextMenu(Menu.buildFromTemplate([{ label: 'Talk', click: () => toggle() }, { label: 'Open', click: () => { mainWin.show(); } }, { type: 'separator' }, { label: 'Quit', click: () => { app.isQuitting = true; app.quit(); } }])); tray.on('click', () => mainWin.show()); } catch { tray = null; }
  for (const p of platform.problems()) log('⚠ ' + p);
  if (isHypr() && config.get('hotkeyBound')) bindHypr(config.get('hotkey'));
  handleArgs(ARGS); setTimeout(() => checkUpdate(false), 4000);
  if (flag('--selftest')) setTimeout(() => selftest(), 1500);
});

app.on('second-instance', (_e, argv) => handleArgs(argv.slice(1)));
app.on('window-all-closed', () => { if (!tray) app.quit(); });
app.on('before-quit', () => { app.isQuitting = true; try { platform.dispose(); } catch { /* ignore */ } try { stt.stop(); } catch { /* ignore */ } });

// ---------- self test (dev): exercises windows + input without the AI ----------
async function selftest() {
  const out = {}; const t0 = Date.now();
  try {
    S.keepCard = true; await showCard(false); await showFx();
    S.result = 'Zelftest: de overlay werkt.'; S.resultUntil = Date.now() + 8000; pushState();
    await new Promise((r) => setTimeout(r, 2500));
    const shot = await platform.screenshot(); out.screenshotBytes = shot.png.length;
    require('fs').writeFileSync('/tmp/assistant-selftest.png', shot.png);
    out.cardBounds = win(cardWin) ? cardWin.getBounds() : null; out.fxBounds = win(fxWin) ? fxWin.getBounds() : null;
    if (flag('--selftest-stt')) {
      const buf = fs.readFileSync('/tmp/s1.f32'); const f32 = new Float32Array(buf.buffer, buf.byteOffset, buf.length / 4); const t1 = Date.now();
      config.set({ lang: 'en', whisperModel: 'base' }); out.stt = await stt.transcribe(f32); out.sttMs = Date.now() - t1;
    }
    if (flag('--selftest-input') && win(cardWin)) {
      const r = await cardWin.webContents.executeJavaScript("(()=>{const r=document.getElementById('input').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()");
      const b = cardWin.getBounds(); await platform.screenshot();
      await platform.click(b.x + r.x, b.y + r.y); await new Promise((r2) => setTimeout(r2, 400));
      await platform.typeText('hallo wereld æøå €'); await new Promise((r2) => setTimeout(r2, 600));
      out.inputValue = await cardWin.webContents.executeJavaScript("document.getElementById('input').value");
      out.focused = await cardWin.webContents.executeJavaScript("document.activeElement && document.activeElement.id");
      await platform.key('ctrl+a'); await new Promise((r2) => setTimeout(r2, 200));
      out.selection = await cardWin.webContents.executeJavaScript("(()=>{const i=document.getElementById('input');return i.selectionEnd-i.selectionStart})()");
      await platform.key('backspace'); await new Promise((r2) => setTimeout(r2, 200));
      out.afterBackspace = await cardWin.webContents.executeJavaScript("document.getElementById('input').value");
    }
  } catch (e) { out.error = e.message; }
  out.ms = Date.now() - t0; console.log('SELFTEST ' + JSON.stringify(out));
}
