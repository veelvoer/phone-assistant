const $ = (id) => document.getElementById(id); const api = window.api; let cfg = {}, running = false, voice = false;
const money = (x) => (x < 0.01 ? '$' + x.toFixed(4) : '$' + x.toFixed(2)); const kk = (n) => (n >= 1000 ? (n / 1000).toFixed(1) + 'k' : String(n));
const toast = (m) => { const t = $('toast'); t.textContent = m; t.style.display = 'block'; setTimeout(() => { t.style.display = 'none'; }, 3500); };
document.querySelectorAll('.tab').forEach((b) => b.addEventListener('click', () => { document.querySelectorAll('.tab').forEach((x) => x.classList.toggle('on', x === b)); document.querySelectorAll('.page').forEach((p) => p.classList.toggle('on', p.id === b.dataset.tab)); }));
const set = (patch) => { Object.assign(cfg, patch); return api.invoke('cfg:set', patch); };
function seg(id, key, num) { const el = $(id); el.querySelectorAll('button').forEach((b) => b.addEventListener('click', () => { set({ [key]: b.dataset.v }); paintSeg(id, b.dataset.v); if (id === 'provider') paintProvider(); })); }
function paintSeg(id, v) { $(id).querySelectorAll('button').forEach((b) => b.classList.toggle('on', b.dataset.v === v)); }
function paintProvider() { $('claudeBox').hidden = cfg.provider === 'hearth'; $('hearthBox').hidden = cfg.provider !== 'hearth'; }
function bind(id, key, type) { const el = $(id); if (type === 'bool') { el.checked = !!cfg[key]; el.addEventListener('change', () => set({ [key]: el.checked })); } else { el.value = cfg[key] == null ? '' : cfg[key]; el.addEventListener('change', () => set({ [key]: type === 'num' ? (el.value === '' ? null : Number(el.value)) : el.value.trim() })); } }
function stats(u) { if (!u) return; $('sToday').textContent = cfg.provider === 'hearth' ? 'plan' : money(u.today); $('sLeft').textContent = u.left == null ? 'set in Settings' : money(u.left); $('sLast').textContent = u.task && u.task.calls ? (cfg.provider === 'hearth' ? kk(u.task.in + u.task.out) + ' tok' : money(u.task.cost)) : '–'; }
function paintPlan(s) { const todos = (s && s.todos) || []; $('planCard').hidden = !todos.length; $('plan').innerHTML = todos.map((t) => `${{ done: '✓', doing: '▶', skipped: '–' }[t.status] || '○'}&nbsp; ${t.text.replace(/</g, '&lt;')}`).join('<br>'); }
const lines = []; function addLog(m) { lines.push(String(m).split('\n')[0].slice(0, 160)); if (lines.length > 14) lines.shift(); $('log').textContent = lines.join('\n'); }
function setStatus(s) { running = s.running; voice = s.voice; $('statusText').textContent = running ? 'Working…' : voice ? 'Listening…' : 'Ready'; $('dot').className = running || voice ? 'work' : ''; $('run').innerHTML = (running ? ICON.stop + ' Stop' : ICON.play + ' Run'); $('run').classList.toggle('stop', running); $('mic').innerHTML = voice ? ICON.stop : ICON.mic; }
async function init() {
  const d = await api.invoke('cfg:all'); cfg = d.cfg; $('hk').textContent = cfg.hotkey;
  $('warnings').innerHTML = d.problems.map((p) => `<div class="warn">⚠ ${p}</div>`).join('');
  paintSeg('provider', cfg.provider); paintProvider(); paintSeg('lang', cfg.lang); paintSeg('whisper', cfg.whisperModel); seg('provider', 'provider'); seg('lang', 'lang'); seg('whisper', 'whisperModel');
  bind('apiKey', 'apiKey'); bind('model', 'model'); bind('hearthUrl', 'hearthUrl'); bind('hearthKey', 'hearthKey'); bind('hearthModel', 'hearthModel'); bind('smartPlan', 'smartPlan', 'bool'); bind('confirmDestructive', 'confirmDestructive', 'bool'); bind('costCap', 'costCap', 'num');
  bind('speak', 'speak', 'bool'); bind('hotkey', 'hotkey'); bind('facts', 'facts'); bind('names', 'names'); bind('overlay', 'overlay', 'bool'); bind('accent', 'accent');
  $('balance').value = d.usage.left == null ? '' : d.usage.left.toFixed(2); $('balance').addEventListener('change', () => set({ balance: $('balance').value === '' ? null : Number($('balance').value) }));
  $('modelChips').querySelectorAll('.chip').forEach((c) => c.addEventListener('click', () => { $('model').value = c.dataset.m; set({ model: c.dataset.m }); }));
  $('ttsInfo').textContent = 'Voice: ' + d.tts; $('sysInfo').textContent = d.platform; $('themeInfo').textContent = `Accent: ${d.theme.accent} (from ${d.theme.source})`;
  if (d.hypr) { $('hyprBox').hidden = false; $('hyprSnippet').textContent = `-- hyprland.lua\nhl.bind("SUPER + A", hl.dsp.exec_cmd('${d.launch} --toggle'))\nhl.bind("SUPER + SHIFT + A", hl.dsp.exec_cmd('${d.launch} --ask'))\n\n# hyprland.conf (older)\nbind = SUPER, A, exec, ${d.launch} --toggle`; }
  stats(d.usage); $('facts').value = d.facts.join('\n');
}
$('run').addEventListener('click', () => { if (running) return api.invoke('ui:stop'); const t = $('task').value.trim(); if (!t) return toast('Describe a task first'); api.invoke('ui:run', t); });
$('mic').addEventListener('click', () => api.invoke('ui:voice'));
document.querySelectorAll('.ask .chip').forEach((c) => c.addEventListener('click', () => { $('task').value = c.textContent; $('task').focus(); }));
$('task').addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); $('run').click(); } });
$('planSend').addEventListener('click', () => { const t = $('planIn').value.trim(); if (t) { api.invoke('ui:interrupt', t); $('planIn').value = ''; } });
$('clear').addEventListener('click', () => { lines.length = 0; $('log').textContent = ''; });
$('testVoice').addEventListener('click', () => api.invoke('ui:test-voice')); $('warm').addEventListener('click', () => { api.invoke('ui:warm-stt'); toast('Downloading the speech model…'); });
$('hyprBind').addEventListener('click', async () => toast(await api.invoke('hypr:bind', 'SUPER + A') === 'ok' ? 'Bound SUPER+A for this session; add the snippet to keep it' : 'Could not bind (see snippet)'));
$('copySnippet').addEventListener('click', () => { navigator.clipboard.writeText($('hyprSnippet').textContent); toast('Copied'); });
let updUrl = ''; api.on('update', (u) => { updUrl = u.url; $('update').hidden = false; $('updT').textContent = 'Update available: v' + u.version; $('updN').textContent = u.notes; });
$('updB').addEventListener('click', () => api.invoke('update:open', updUrl)); $('checkUpd').addEventListener('click', () => api.invoke('update:check'));
api.on('log', addLog); api.on('plan', paintPlan); api.on('usage', stats); api.on('status', setStatus); api.on('toast', toast); api.on('facts', (f) => { if (document.activeElement !== $('facts')) $('facts').value = f.join('\n'); });
api.on('stt', (s) => { if (s.progress != null && s.progress < 1) $('sttInfo').textContent = `Downloading speech model… ${Math.round(s.progress * 100)}%`; if (s.ready) $('sttInfo').textContent = 'Speech model ready.'; });
$('mic').innerHTML = ICON.mic; $('run').innerHTML = ICON.play + ' Run';
init();
