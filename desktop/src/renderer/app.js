const $ = (id) => document.getElementById(id); const A = window.api; let cfg = {}, running = false, voice = false;
const money = (x) => (x < 0.01 ? '$' + x.toFixed(4) : '$' + x.toFixed(2)); const kk = (n) => (n >= 1000 ? (n / 1000).toFixed(1) + 'k' : String(n));
const toast = (m) => { const t = $('toast'); t.textContent = m; t.style.display = 'block'; setTimeout(() => { t.style.display = 'none'; }, 3500); };
document.querySelectorAll('.tab').forEach((b) => b.addEventListener('click', () => { document.querySelectorAll('.tab').forEach((x) => x.classList.toggle('on', x === b)); document.querySelectorAll('.page').forEach((p) => p.classList.toggle('on', p.id === b.dataset.tab)); }));
const set = (patch) => { Object.assign(cfg, patch); return A.invoke('cfg:set', patch); };
function seg(id, key, num) { const el = $(id); el.querySelectorAll('button').forEach((b) => b.addEventListener('click', () => { set({ [key]: b.dataset.v }); paintSeg(id, b.dataset.v); if (id === 'provider') paintProvider(); })); }
function paintSeg(id, v) { $(id).querySelectorAll('button').forEach((b) => b.classList.toggle('on', b.dataset.v === v)); }
function paintProvider() { $('claudeBox').hidden = cfg.provider === 'hearth'; $('hearthBox').hidden = cfg.provider !== 'hearth'; }
function bind(id, key, type) { const el = $(id); if (type === 'bool') { el.checked = !!cfg[key]; el.addEventListener('change', () => set({ [key]: el.checked })); } else { el.value = cfg[key] == null ? '' : cfg[key]; el.addEventListener('change', () => set({ [key]: type === 'num' ? (el.value === '' ? null : Number(el.value)) : el.value.trim() })); } }
function stats(u) { if (!u) return; $('sToday').textContent = cfg.provider === 'hearth' ? 'plan' : money(u.today); $('sLeft').textContent = u.left == null ? 'set in Settings' : money(u.left); $('sLast').textContent = u.task && u.task.calls ? (cfg.provider === 'hearth' ? kk(u.task.in + u.task.out) + ' tok' : money(u.task.cost)) : '–'; }
function paintPlan(s) { const todos = (s && s.todos) || []; $('planCard').hidden = !todos.length; $('plan').innerHTML = todos.map((t) => `${{ done: '✓', doing: '▶', skipped: '–' }[t.status] || '○'}&nbsp; ${t.text.replace(/</g, '&lt;')}`).join('<br>'); }
const lines = []; function addLog(m) { lines.push(String(m).split('\n')[0].slice(0, 160)); if (lines.length > 14) lines.shift(); $('log').textContent = lines.join('\n'); }
function setStatus(s) { running = s.running; voice = s.voice; $('statusText').textContent = running ? 'Working…' : voice ? 'Listening…' : 'Ready'; $('dot').className = running || voice ? 'work' : ''; $('run').innerHTML = (running ? ICON.stop + ' Stop' : ICON.play + ' Run'); $('run').classList.toggle('stop', running); $('mic').innerHTML = voice ? ICON.stop : ICON.mic; }
async function init() {
  const d = await A.invoke('cfg:all'); cfg = d.cfg; $('hk').textContent = cfg.hotkey;
  $('warnings').innerHTML = d.problems.map((p) => `<div class="warn">⚠ ${p}</div>`).join('');
  paintSeg('provider', cfg.provider); paintProvider(); paintSeg('lang', cfg.lang); paintSeg('whisper', cfg.whisperModel); seg('provider', 'provider'); seg('lang', 'lang'); seg('whisper', 'whisperModel');
  bind('apiKey', 'apiKey'); bind('model', 'model'); bind('hearthUrl', 'hearthUrl'); bind('hearthKey', 'hearthKey'); bind('hearthModel', 'hearthModel'); bind('smartPlan', 'smartPlan', 'bool'); bind('confirmDestructive', 'confirmDestructive', 'bool'); bind('costCap', 'costCap', 'num');
  bind('speak', 'speak', 'bool'); $('hotkey').value = cfg.hotkey; hkInfo(d.hypr); bind('facts', 'facts'); bind('lessons', 'lessons'); bind('names', 'names'); bind('overlay', 'overlay', 'bool'); bind('accent', 'accent');
  $('balance').value = d.usage.left == null ? '' : d.usage.left.toFixed(2); $('balance').addEventListener('change', () => set({ balance: $('balance').value === '' ? null : Number($('balance').value) }));
  $('modelChips').querySelectorAll('.chip').forEach((c) => c.addEventListener('click', () => { $('model').value = c.dataset.m; set({ model: c.dataset.m }); }));
  $('ttsInfo').textContent = 'Voice: ' + d.tts; $('sysInfo').textContent = d.platform; $('themeInfo').textContent = `Accent: ${d.theme.accent} (from ${d.theme.source})`;
  if (d.hypr) { window.__hypr = d; $('hyprBox').hidden = false; await paintSnippet(); }
  stats(d.usage); $('facts').value = d.facts.join('\n');
}
$('run').addEventListener('click', () => { if (running) return A.invoke('ui:stop'); const t = $('task').value.trim(); if (!t) return toast('Describe a task first'); A.invoke('ui:run', t); });
$('mic').addEventListener('click', () => A.invoke('ui:voice'));
document.querySelectorAll('.ask .chip').forEach((c) => c.addEventListener('click', () => { $('task').value = c.textContent; $('task').focus(); }));
$('task').addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); $('run').click(); } });
$('planSend').addEventListener('click', () => { const t = $('planIn').value.trim(); if (t) { A.invoke('ui:interrupt', t); $('planIn').value = ''; } });
$('clear').addEventListener('click', () => { lines.length = 0; $('log').textContent = ''; });
$('testVoice').addEventListener('click', () => A.invoke('ui:test-voice')); $('warm').addEventListener('click', () => { A.invoke('ui:warm-stt'); toast('Downloading the speech model…'); });
$('hyprBind').addEventListener('click', async () => { const r = await A.invoke('hypr:bind', cfg.hotkey); if (r === 'ok') { set({ hotkeyBound: true }); toast('Bound ' + cfg.hotkey + ' in Hyprland (re-applied every time the app starts)'); } else toast('Could not bind: ' + r); });
async function paintSnippet() {
  const d = window.__hypr; if (!d) return; const h = await A.invoke('hotkey:hypr', cfg.hotkey);
  $('hyprSnippet').textContent = `-- hyprland.lua (permanent; the app also re-binds it by itself when it starts)\nhl.bind("${h}", hl.dsp.exec_cmd('${d.launch} --toggle'))\nhl.bind("${h.replace(/ \+ [^+]+$/, ' + SHIFT + ') + h.split(' + ').pop()}", hl.dsp.exec_cmd('${d.launch} --ask'))\n\n# hyprland.conf (older)\nbind = ${h.replace(/ \+ [^+]+$/, '').replace(/ \+ /g, ' ')}, ${h.split(' + ').pop()}, exec, ${d.launch} --toggle`;
}
function hkInfo(hypr) { $('hkInfo').textContent = hypr ? 'Hyprland: the shortcut is bound through hyprctl when you save it.' : 'Works while another app is focused (X11 / Windows). On Wayland compositors other than Hyprland, bind a key to “Assistant --toggle” in your compositor.'; }
// ---- shortcut recorder (like in real apps)
const MODNAMES = ['Control', 'Alt', 'Shift', 'Meta', 'AltGraph'];
function keyName(e) {
  const c = e.code || ''; if (/^Digit\d$/.test(c)) return c.slice(5); if (/^Numpad\d$/.test(c)) return c.slice(6);
  const named = { Space: 'Space', Enter: 'Return', NumpadEnter: 'Return', Escape: 'Escape', Tab: 'Tab', Backspace: 'Backspace', Delete: 'Delete', Insert: 'Insert', Home: 'Home', End: 'End', PageUp: 'PageUp', PageDown: 'PageDown', ArrowUp: 'Up', ArrowDown: 'Down', ArrowLeft: 'Left', ArrowRight: 'Right' };
  if (named[e.key === ' ' ? 'Space' : e.key] || named[c]) return named[e.key === ' ' ? 'Space' : e.key] || named[c];
  if (/^F\d{1,2}$/.test(e.key)) return e.key; if (e.key && e.key.length === 1) return e.key.toUpperCase();
  const sym = { Backquote: '`', Minus: '-', Equal: '=', Comma: ',', Period: '.', Slash: '/', Semicolon: ';', Quote: "'", BracketLeft: '[', BracketRight: ']', Backslash: '\\' }; return sym[c] || '';
}
let recording = false;
function stopRec(text) { recording = false; $('hkRec').textContent = 'Press keys…'; $('hotkey').value = text == null ? cfg.hotkey : text; A.invoke('hotkey:pause', false); }
$('hkRec').addEventListener('click', () => { if (recording) return stopRec(); recording = true; $('hkRec').textContent = 'Press the keys… (Esc cancels)'; $('hotkey').value = ''; A.invoke('hotkey:pause', true); });
document.addEventListener('keydown', async (e) => {
  if (!recording) return; e.preventDefault(); e.stopPropagation();
  if (e.key === 'Escape' && !e.ctrlKey && !e.altKey && !e.metaKey) return stopRec();
  const mods = []; if (e.ctrlKey) mods.push('Ctrl'); if (e.altKey) mods.push('Alt'); if (e.shiftKey) mods.push('Shift'); if (e.metaKey) mods.push('Super');
  if (MODNAMES.includes(e.key)) { $('hotkey').value = mods.join('+') + '+…'; return; }
  const k = keyName(e); if (!k) return;
  if (!mods.length && !/^F\d+$/.test(k)) { $('hotkey').value = 'Hold Ctrl, Alt, Shift or Super too'; return; }
  const combo = mods.concat(k).join('+'); stopRec(combo); $('hk').textContent = combo;
  await set({ hotkey: combo, hotkeyBound: !!window.__hypr }); if (window.__hypr) paintSnippet(); toast('Shortcut saved: ' + combo);
}, true);
$('copySnippet').addEventListener('click', () => { navigator.clipboard.writeText($('hyprSnippet').textContent); toast('Copied'); });
let updUrl = ''; A.on('update', (u) => { updUrl = u.url; $('update').hidden = false; $('updT').textContent = 'Update available: v' + u.version; $('updN').textContent = u.notes; });
$('updB').addEventListener('click', () => A.invoke('update:open', updUrl)); $('checkUpd').addEventListener('click', () => A.invoke('update:check'));
A.on('log', addLog); A.on('plan', paintPlan); A.on('usage', stats); A.on('status', setStatus); A.on('toast', toast); A.on('facts', async (f) => { if (document.activeElement !== $('facts')) $('facts').value = f.join('\n'); const d = await A.invoke('cfg:all'); cfg.lessons = d.cfg.lessons; if (document.activeElement !== $('lessons')) $('lessons').value = d.cfg.lessons || ''; });
A.on('stt', (s) => { if (s.progress != null && s.progress < 1) $('sttInfo').textContent = `Downloading speech model… ${Math.round(s.progress * 100)}%`; if (s.ready) $('sttInfo').textContent = 'Speech model ready.'; });
$('mic').innerHTML = ICON.mic; $('run').innerHTML = ICON.play + ' Run';
init();
