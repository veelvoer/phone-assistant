// Loads the real renderer pages in jsdom with a stubbed bridge and clicks through everything: catches script errors that would silently kill the UI.
const assert = require('assert'); const path = require('path'); const { JSDOM, VirtualConsole } = require('jsdom');
const R = path.join(__dirname, '../src/renderer');
const { DEFAULTS } = require('../src/config');

async function load(file, apiExtra = {}) {
  const errors = []; const vc = new VirtualConsole(); vc.on('jsdomError', (e) => errors.push(String(e.stack || e))); vc.on('error', (e) => errors.push(String(e)));
  const calls = []; const handlers = {};
  const cfgAll = { version: '9.9.9', cfg: { ...DEFAULTS, apiKey: 'sk-test', facts: 'mom = Anna', lessons: '' }, theme: { accent: '#7aa2f7', source: 'test' }, dark: true, platform: 'Test OS', problems: ['⚠ test problem'], tts: 'espeak', hypr: true, launch: '/x/Assistant', usage: { today: 0.5, total: 3, left: 4.2, task: { in: 1000, out: 100, cost: 0.01, calls: 3 } }, facts: ['mom = Anna'] };
  const api = { invoke: async (ch, ...a) => { calls.push([ch, ...a]); if (ch === 'cfg:all') return cfgAll; if (ch === 'hotkey:hypr') return 'CTRL + ALT + space'; return true; }, send: (...a) => calls.push(['send', ...a]), on: (ch, f) => { (handlers[ch] = handlers[ch] || []).push(f); return () => {}; }, ...apiExtra };
  const dom = await JSDOM.fromFile(path.join(R, file), { runScripts: 'dangerously', resources: 'usable', pretendToBeVisual: true, virtualConsole: vc, beforeParse(w) { w.api = api; w.HTMLCanvasElement.prototype.getContext = () => new Proxy({}, { get: () => () => ({ addColorStop() {} }) }); w.requestAnimationFrame = () => 0; w.ResizeObserver = class { observe() {} }; w.HTMLElement.prototype.scrollTo = () => {}; } });
  await new Promise((r) => dom.window.addEventListener('load', r)); await new Promise((r) => setTimeout(r, 150));
  return { dom, w: dom.window, d: dom.window.document, errors, calls, handlers, emit: (ch, ...a) => (handlers[ch] || []).forEach((f) => f(...a)) };
}

async function run() {
  // ---- main window ----
  const m = await load('index.html');
  const { d, w } = m;
  const navs = [...d.querySelectorAll('.navi')]; assert.ok(navs.length >= 9, 'nav items: ' + navs.length);
  for (const n of navs) { n.click(); const page = d.getElementById('page-' + n.dataset.id); assert.ok(page && page.classList.contains('on'), 'page opens: ' + n.dataset.id); assert.ok(page.textContent.length > 20, 'page has content: ' + n.dataset.id); }
  // every control on every settings page can be used without errors
  for (const n of navs) {
    if (n.dataset.id === 'home') continue; n.click(); const page = d.getElementById('page-' + n.dataset.id);
    page.querySelectorAll('input[type=checkbox]').forEach((c) => { c.checked = !c.checked; c.dispatchEvent(new w.Event('change')); });
    page.querySelectorAll('.seg button').forEach((b) => b.click()); page.querySelectorAll('.chip').forEach((b) => b.click()); page.querySelectorAll('.sw').forEach((b) => b.click());
    page.querySelectorAll('input[type=range]').forEach((r) => { r.value = r.max; r.dispatchEvent(new w.Event('input')); r.dispatchEvent(new w.Event('change')); });
    page.querySelectorAll('input[type=text],input[type=password],input[type=number],textarea').forEach((i) => { i.value = i.type === 'number' ? '1.5' : 'abc'; i.dispatchEvent(new w.Event('change')); });
    page.querySelectorAll('button.tonal').forEach((b) => b.click());
  }
  const keys = m.calls.filter((c) => c[0] === 'cfg:set').flatMap((c) => Object.keys(c[1]));
  for (const k of ['provider', 'apiKey', 'smartPlan', 'whisperModel', 'lang', 'glowWidth', 'cardPos', 'cardOpacity', 'assistantName', 'tone', 'quietOn', 'facts', 'balance', 'dailyLimit']) assert.ok(keys.includes(k), 'setting saved: ' + k);
  const stt = m.calls.filter((c) => c[0] === 'cfg:set' && 'whisperModel' in c[1]).map((c) => c[1].whisperModel); assert.ok(stt.includes('low') && stt.includes('medium') && stt.includes('high'), 'three STT levels: ' + stt);
  assert.ok(m.calls.some((c) => c[0] === 'ui:preview'), 'preview button');
  // events from the main process
  m.emit('update', { version: '2.0.0', url: 'https://github.com/veelvoer/x', notes: 'n', canInstall: true }); m.emit('update:state', { phase: 'downloading', pct: 0.5 }); m.emit('plan', { todos: [{ text: 'a', status: 'doing' }] }); m.emit('status', { running: true, voice: false }); m.emit('usage', { today: 1, left: null, task: { calls: 0 } }); m.emit('stt', { progress: 0.4 }); m.emit('facts');
  assert.strictEqual(d.getElementById('updB').disabled, true); d.querySelector('.navi[data-id=home]').click();
  assert.ok(/Stop/.test(d.getElementById('run').textContent), 'run button turns into stop');
  d.getElementById('task').value = 'hallo'; m.emit('status', { running: false, voice: false }); d.getElementById('run').click(); assert.ok(m.calls.some((c) => c[0] === 'ui:run' && c[1] === 'hallo'), 'run sends the task');
  // shortcut recorder
  d.querySelector('.navi[data-id=shortcut]').click(); d.getElementById('hkRec').click();
  const ev = (init) => new w.KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init });
  d.dispatchEvent(ev({ key: 'Control', code: 'ControlLeft', ctrlKey: true })); d.dispatchEvent(ev({ key: 'k', code: 'KeyK', ctrlKey: true, altKey: true })); await new Promise((r) => setTimeout(r, 50));
  assert.ok(m.calls.some((c) => c[0] === 'cfg:set' && c[1].hotkey === 'Ctrl+Alt+K'), 'recorded shortcut saved');
  assert.deepStrictEqual(m.errors, [], 'no script errors in the main window'); m.dom.window.close();

  // ---- card ----
  const c = await load('card.html'); c.emit('state', { mode: 'working', title: 'Working', text: 'Tapping', sub: '1/3', progress: 0.4, level: 0.2, voice: true, style: { cardOpacity: 70, cardRadius: 12, showBars: false, showProgress: false, cardPos: 'top' } });
  assert.ok(c.d.body.classList.contains('nobars') && c.d.body.classList.contains('top'), 'card style applied'); assert.strictEqual(c.d.getElementById('text').textContent, 'Tapping');
  c.d.getElementById('input').value = 'doe iets'; c.d.getElementById('form').dispatchEvent(new c.w.Event('submit', { cancelable: true })); assert.ok(c.calls.some((x) => x[0] === 'ui:run' && x[1] === 'doe iets'));
  assert.deepStrictEqual(c.errors, [], 'no script errors in the card');

  // ---- board (whiteboard) ----
  const { sanitizeSvg } = require('../src/svg'); const b = await load('board.html');
  b.emit('board', { title: 'DNS', frames: [{ svg: sanitizeSvg('<svg viewBox="0 0 800 500"><rect width="100" height="50" fill="#ccc"/><text x="5" y="30">hi</text></svg>'), caption: 'Step one' }, { svg: sanitizeSvg('<svg viewBox="0 0 800 500"><circle r="20" cx="50" cy="50"/></svg>'), caption: 'Step two' }] });
  assert.ok(b.d.querySelector('#stage svg rect') && b.d.getElementById('cap').textContent === 'Step one'); b.d.getElementById('next').click(); assert.ok(b.d.querySelector('#stage svg circle') && b.d.getElementById('count').textContent === '2 / 2');
  b.d.dispatchEvent(new b.w.KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); assert.ok(b.calls.some((x) => x[0] === 'send' && x[1] === 'board:close'));
  assert.deepStrictEqual(b.errors, [], 'no script errors in the board');
  console.log('ui tests passed');
}
module.exports = { run };
if (require.main === module) run().catch((e) => { console.error('UI TEST FAILED', e); process.exit(1); });
