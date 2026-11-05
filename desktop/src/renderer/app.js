const $ = (id) => document.getElementById(id); const A = window.api; let cfg = {}, D = {}, running = false, voice = false;
const money = (x) => (x < 0.01 ? '$' + x.toFixed(4) : '$' + x.toFixed(2)); const kk = (n) => (n >= 1000 ? (n / 1000).toFixed(1) + 'k' : String(n));
const esc = (t) => String(t == null ? '' : t).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])); const el = (html) => { const t = document.createElement('template'); t.innerHTML = html.trim(); return t.content.firstChild; };
const toast = (m) => { const t = $('toast'); t.textContent = m; t.style.display = 'block'; clearTimeout(toast.t); toast.t = setTimeout(() => { t.style.display = 'none'; }, 3500); };
const set = (patch) => { Object.assign(cfg, patch); return A.invoke('cfg:set', patch); };
const OVERLAY_KEYS = ['glowMode', 'cardOn', 'cardStyle', 'cardText', 'cardOffset', 'showTitle', 'anim', 'waveStyle', 'waveCount', 'cardPos', 'cardSize', 'cardOpacity', 'cardRadius', 'showBars', 'showProgress', 'glowOn', 'glowWidth', 'glowIntensity', 'glowSpeed', 'overlay', 'accent'];

// ---------------- settings schema ----------------
const SWATCHES = ['#7aa2f7', '#4f6bed', '#8b5cf6', '#d946ef', '#ef4444', '#f97316', '#eab308', '#22c55e', '#14b8a6', '#06b6d4'];
const SECTIONS = [
  { id: 'ai', title: 'AI', icon: 'ai', sub: 'Which Claude does the work, and how careful it is.', groups: [
    { title: 'Provider', items: [
      { type: 'seg', key: 'provider', title: 'Provider', options: [['claude', 'Claude API'], ['hearth', 'Hearth']], rerender: true },
      { type: 'password', key: 'apiKey', title: 'Claude API key', placeholder: 'sk-ant-…', note: 'Get one at console.anthropic.com. Stored only on this computer.', show: (c) => c.provider !== 'hearth' },
      { type: 'text', key: 'model', title: 'Model', show: (c) => c.provider !== 'hearth' },
      { type: 'chips', key: 'model', options: [['claude-haiku-5-5', 'Haiku · fast'], ['claude-sonnet-5-5', 'Sonnet · smart'], ['claude-opus-5-5', 'Opus · best']], show: (c) => c.provider !== 'hearth' },
      { type: 'text', key: 'hearthUrl', title: 'Hearth server URL', placeholder: 'https://…', show: (c) => c.provider === 'hearth' },
      { type: 'password', key: 'hearthKey', title: 'Hearth API key', show: (c) => c.provider === 'hearth' },
      { type: 'text', key: 'hearthModel', title: 'Hearth model', show: (c) => c.provider === 'hearth', note: 'Hearth is text-only here: no screenshots, so it works through shell commands, files and keyboard shortcuts.' },
      { type: 'toggle', key: 'smartPlan', title: 'Smart planning', sub: 'A stronger model (Sonnet) writes the plan, the cheap one carries it out', show: (c) => c.provider !== 'hearth' },
    ] },
    { title: 'Safety', items: [
      { type: 'toggle', key: 'confirmDestructive', title: 'Ask before destructive commands', sub: 'rm -r, sudo, uninstall, kill…' },
      { type: 'number', key: 'costCap', title: 'Max cost per task', unit: '$', step: 0.05, min: 0.05 },
    ] } ] },
  { id: 'voice', title: 'Voice', icon: 'mic20', sub: 'Talking to it, and how it talks back.', groups: [
    { title: 'Speech recognition', items: [
      { type: 'seg', key: 'lang', title: 'Language', options: [['nl', 'Nederlands'], ['en', 'English'], ['auto', 'Auto']] },
      { type: 'seg', key: 'whisperModel', title: 'Recognition quality', sub: 'Runs on this computer. In my Dutch test the word error rate was about 48% (Low), 14–24% (Medium) and 6% (High).', norm: (v) => ({ base: 'low', small: 'medium' }[v] || v), options: [['low', 'Low', 'Base · 80 MB · fast'], ['medium', 'Medium', 'Small · 240 MB · okay'], ['high', 'High', 'Large-v3 Turbo · 760 MB · best']] },
      { type: 'buttons', buttons: [{ label: 'Download speech model now', action: 'warm' }] },
      { type: 'note', id: 'sttInfo', text: 'The speech model downloads once, the first time you use voice.' },
    ] },
    { title: 'Spoken answers', items: [
      { type: 'toggle', key: 'speak', title: 'Spoken answers', sub: 'A natural offline voice (Piper). It downloads once, about 90 MB.' },
      { type: 'voice' },
      { type: 'range', key: 'ttsVolume', title: 'Voice volume', min: 50, max: 250, step: 10, unit: '%', sub: 'On top of your system volume' },
      { type: 'range', key: 'ttsSpeed', title: 'Speaking speed', min: 70, max: 150, step: 5, unit: '%' },
      { type: 'buttons', buttons: [{ label: 'Test voice', action: 'testVoice' }, { label: 'Download voice now', action: 'ttsDownload' }] },
      { type: 'note', id: 'ttsInfo', text: '' },
      { type: 'toggle', key: 'sounds', title: 'Start / done sounds', sub: 'A short chime when listening starts and when a task finishes' },
    ] } ] },
  { id: 'shortcut', title: 'Shortcut', icon: 'keyboard', sub: 'The keys that wake the assistant.', groups: [{ title: '', items: [{ type: 'hotkey' }] }] },
  { id: 'look', title: 'Appearance', icon: 'palette', sub: 'Make the overlay yours. Changes show a live preview.', groups: [
    { title: 'Looks', items: [{ type: 'presets' }] },
    { title: 'Colour', items: [{ type: 'swatches', key: 'accent', title: 'Accent colour', sub: () => `Following your system (${D.theme.source}) unless you pick one.`, rerender: true }, { type: 'swatches', key: 'accent2', title: 'Second colour (optional)', none: true, show: (c) => !!c.accent }] },
    { title: 'Screen glow & drawing', items: [
      { type: 'toggle', key: 'overlay', title: 'Screen overlay', sub: 'The glowing edge and highlight spotlight', rerender: true },
      { type: 'toggle', key: 'glowOn', title: 'Glowing edge', show: (c) => c.overlay !== false, rerender: true },
      { type: 'seg', key: 'glowMode', title: 'Where', options: [['full', 'Full edge'], ['bottom', 'Bottom'], ['top', 'Top'], ['sides', 'Sides']], show: (c) => c.overlay !== false && c.glowOn !== false },
      { type: 'seg', key: 'glowWidth', title: 'Thickness', num: true, options: [[1, 'Thin'], [2, 'Normal'], [3, 'Bold']], show: (c) => c.overlay !== false && c.glowOn !== false },
      { type: 'range', key: 'glowIntensity', title: 'Brightness', min: 20, max: 140, step: 5, unit: '%', show: (c) => c.overlay !== false && c.glowOn !== false },
      { type: 'range', key: 'glowSoft', title: 'Softness', min: 0, max: 100, step: 5, unit: '%', sub: 'Crisp line … soft haze', show: (c) => c.overlay !== false && c.glowOn !== false },
      { type: 'range', key: 'glowRadius', title: 'Corner radius of the glow', min: 0, max: 80, step: 2, unit: ' px', show: (c) => c.overlay !== false && c.glowOn !== false },
      { type: 'seg', key: 'glowSpeed', title: 'Animation speed', num: true, options: [[1, 'Calm'], [2, 'Normal'], [3, 'Lively']], show: (c) => c.overlay !== false && c.glowOn !== false },
    ] },
    { title: 'Card', items: [
      { type: 'toggle', key: 'cardOn', title: 'Show the card', sub: 'Turn it off to keep only the glow', rerender: true },
      { type: 'seg', key: 'cardPos', title: 'Position', options: [['bottom', 'Bottom'], ['top', 'Top']], show: (c) => c.cardOn !== false },
      { type: 'seg', key: 'cardSize', title: 'Size', options: [['compact', 'Compact'], ['normal', 'Normal'], ['large', 'Large']], show: (c) => c.cardOn !== false },
      { type: 'seg', key: 'cardStyle', title: 'Style', options: [['solid', 'Solid'], ['outline', 'Outline'], ['tinted', 'Tinted']], show: (c) => c.cardOn !== false },
      { type: 'range', key: 'cardOpacity', title: 'Transparency', min: 40, max: 100, step: 1, unit: '%', sub: 'Lower = see-through', show: (c) => c.cardOn !== false },
      { type: 'range', key: 'cardRadius', title: 'Corner roundness', min: 6, max: 44, step: 1, unit: ' px', show: (c) => c.cardOn !== false },
      { type: 'range', key: 'cardText', title: 'Text size', min: 13, max: 24, step: 1, unit: ' px', show: (c) => c.cardOn !== false },
      { type: 'range', key: 'cardOffset', title: 'Distance from the edge', min: 8, max: 200, step: 2, unit: ' px', show: (c) => c.cardOn !== false },
      { type: 'toggle', key: 'showTitle', title: 'Show the title line', show: (c) => c.cardOn !== false },
      { type: 'seg', key: 'anim', title: 'Appearing', options: [['slide', 'Slide'], ['fade', 'Fade'], ['pop', 'Pop']], show: (c) => c.cardOn !== false },
      { type: 'toggle', key: 'showBars', title: 'Voice indicator', rerender: true, show: (c) => c.cardOn !== false },
      { type: 'seg', key: 'waveStyle', title: 'Indicator style', options: [['bars', 'Bars'], ['dots', 'Dots'], ['ring', 'Ring']], show: (c) => c.cardOn !== false && c.showBars !== false },
      { type: 'range', key: 'waveCount', title: 'Bars / dots', min: 3, max: 7, step: 1, show: (c) => c.cardOn !== false && c.showBars !== false },
      { type: 'toggle', key: 'showProgress', title: 'Plan progress bar', show: (c) => c.cardOn !== false },
      { type: 'range', key: 'autoHide', title: 'Show results for', min: 2, max: 15, step: 1, unit: ' s' },
      { type: 'buttons', buttons: [{ label: 'Preview', action: 'preview' }, { label: 'Reset to default', action: 'resetLook' }] },
    ] } ] },
  { id: 'me', title: 'Personality', icon: 'face', sub: 'Name, tone and how much it says.', groups: [
    { title: 'Character', items: [
      { type: 'text', key: 'assistantName', title: 'Its name', placeholder: 'Assistant', note: 'It uses this when it introduces itself.' },
      { type: 'seg', key: 'tone', title: 'Tone', options: [['neutral', 'Neutral'], ['friendly', 'Friendly'], ['concise', 'Concise'], ['playful', 'Playful']] },
      { type: 'seg', key: 'replyLength', title: 'Spoken reply length', options: [['short', 'Short'], ['normal', 'Normal'], ['detailed', 'Detailed']] },
    ] },
    { title: 'Quiet hours', items: [
      { type: 'toggle', key: 'quietOn', title: 'Stay quiet at night', sub: 'No spoken answers or sounds in this time window', rerender: true },
      { type: 'times', show: (c) => !!c.quietOn },
    ] } ] },
  { id: 'memory', title: 'Memory', icon: 'brain', sub: 'What it knows about you and what it learned.', groups: [{ title: '', items: [
    { type: 'textarea', key: 'facts', title: 'About you', sub: 'One short fact per line: who you are, family, friends.', rows: 5, placeholder: 'I am Warre\nmom = Anna Peeters\nbrother = Lars' },
    { type: 'textarea', key: 'lessons', title: 'Lessons from your corrections', sub: 'When you say “nee, ik bedoelde …” it saves a rule here.', rows: 4, placeholder: 'Nothing learned yet' },
    { type: 'text', key: 'names', title: 'Names for speech recognition', placeholder: 'Jan, Sanne, Wout' },
  ] }] },
  { id: 'budget', title: 'Budget', icon: 'wallet', sub: 'Keep an eye on what it costs.', groups: [{ title: '', items: [
    { type: 'number', key: 'balance', title: 'Credit balance', unit: '$', step: 0.01, note: "Anthropic doesn't let apps read your balance. Enter it once (console.anthropic.com → Billing) and the app subtracts what it spends.", special: 'balance' },
    { type: 'number', key: 'dailyLimit', title: 'Daily limit', unit: '$', step: 0.1, min: 0, note: '0 = no limit. It stops starting tasks once today’s spending reaches this.' },
  ] }] },
  { id: 'system', title: 'System', icon: 'info', sub: 'Version, updates and your desktop.', groups: [{ title: '', items: [{ type: 'system' }] }] },
];

// ---------------- rendering ----------------
const pages = {};
function controlFor(it) {
  const val = it.norm ? it.norm(cfg[it.key]) : cfg[it.key];
  const commit = (v) => { set({ [it.key]: v }); if (OVERLAY_KEYS.includes(it.key)) { /* main shows the preview */ } if (it.rerender) rerender(it.__sec); };
  if (it.type === 'seg') {
    const w = el('<div class="seg"></div>');
    for (const [v, label, sub] of it.options) { const b = el(`<button data-v="${esc(v)}" type="button">${esc(label)}${sub ? `<small>${esc(sub)}</small>` : ''}</button>`); b.classList.toggle('on', String(val) === String(v)); b.onclick = () => { w.querySelectorAll('button').forEach((x) => x.classList.toggle('on', x === b)); commit(it.num ? Number(v) : v); }; w.appendChild(b); }
    return w;
  }
  if (it.type === 'chips') { const w = el('<div class="chips"></div>'); for (const [v, label] of it.options) { const c = el(`<span class="chip">${esc(label)}</span>`); c.onclick = () => { commit(v); const inp = w.closest('.page').querySelector('[data-key="model"]'); if (inp) inp.value = v; }; w.appendChild(c); } return w; }
  if (it.type === 'toggle') { const c = el('<input type="checkbox">'); c.checked = it.key === 'overlay' || it.key === 'glowOn' || it.key === 'showBars' || it.key === 'showProgress' ? cfg[it.key] !== false : !!cfg[it.key]; c.dataset.key = it.key; c.onchange = () => commit(c.checked); return c; }
  if (it.type === 'range') {
    const w = el('<div class="rangewrap"><input type="range"><output></output></div>'); const r = w.querySelector('input'), o = w.querySelector('output'); r.min = it.min; r.max = it.max; r.step = it.step; r.value = cfg[it.key] == null ? it.min : cfg[it.key]; r.dataset.key = it.key;
    const paint = () => { o.textContent = r.value + (it.unit || ''); }; paint(); r.oninput = paint; r.onchange = () => commit(Number(r.value)); return w;
  }
  if (it.type === 'textarea') { const t = el(`<textarea rows="${it.rows || 3}" placeholder="${esc(it.placeholder || '')}"></textarea>`); t.value = cfg[it.key] || ''; t.dataset.key = it.key; t.onchange = () => commit(t.value.split('\n').map((x) => x.trim()).filter(Boolean).join('\n')); return t; }
  if (it.type === 'swatches') {
    const w = el('<div class="swatches"></div>'); const sys = el(`<div class="sw sys" title="${it.none ? 'None' : 'Follow system'}"></div>`); if (it.none) sys.style.background = 'transparent', sys.style.border = '3px dashed var(--outline)'; w.appendChild(sys);
    const paint = () => { sys.classList.toggle('on', !cfg[it.key]); w.querySelectorAll('.sw:not(.sys)').forEach((s) => s.classList.toggle('on', s.dataset.c === cfg[it.key])); };
    sys.onclick = () => { commit(''); paint(); }; for (const c of SWATCHES) { const s = el(`<div class="sw" data-c="${c}" style="background:${c}"></div>`); s.onclick = () => { commit(c); paint(); }; w.appendChild(s); }
    const custom = el('<input type="color" class="sw" style="padding:0;width:34px;height:34px">'); custom.value = /^#[0-9a-f]{6}$/i.test(cfg[it.key] || '') ? cfg[it.key] : '#7aa2f7'; custom.onchange = () => { commit(custom.value); paint(); }; w.appendChild(custom); paint(); return w;
  }
  const typ = it.type === 'password' ? 'password' : it.type === 'number' ? 'number' : 'text';
  const i = el(`<input type="${typ}" placeholder="${esc(it.placeholder || '')}">`); i.dataset.key = it.key; if (typ === 'number') { if (it.step) i.step = it.step; if (it.min != null) i.min = it.min; }
  if (it.special === 'balance') i.value = D.usage && D.usage.left != null ? D.usage.left.toFixed(2) : ''; else i.value = cfg[it.key] == null ? '' : cfg[it.key];
  i.onchange = () => commit(typ === 'number' ? (i.value === '' ? (it.key === 'balance' ? null : 0) : Number(i.value)) : i.value.trim());
  return i;
}
const ACTIONS = { ttsDownload: async () => { toast('Downloading the voice…'); const r = await A.invoke('tts:download'); toast(r.ok ? 'Voice ready' : 'Voice download failed'); const n = $('ttsInfo'); if (n) n.textContent = r.info; }, resetLook: async () => { cfg = (await A.invoke('look:reset')); rerender(SECTIONS.find((x) => x.id === 'look')); toast('Appearance reset'); }, warm: () => { A.invoke('ui:warm-stt'); toast('Downloading the speech model…'); }, testVoice: () => A.invoke('ui:test-voice'), preview: () => A.invoke('ui:preview'), checkUpd: () => A.invoke('update:check') };
function buildItem(it, sec) {
  it.__sec = sec;
  if (it.type === 'buttons') { const w = el('<div class="item"><div class="row"></div></div>'); for (const b of it.buttons) { const x = el(`<button class="tonal" type="button">${esc(b.label)}</button>`); x.onclick = () => ACTIONS[b.action] && ACTIONS[b.action](); w.firstChild.appendChild(x); } return w; }
  if (it.type === 'note') return el(`<div class="item"><p class="hint" id="${it.id || ''}">${esc(it.text)}</p></div>`);
  if (it.type === 'hotkey') return buildHotkey();
  if (it.type === 'voice') { const w = el('<div class="item"><div class="t">Voice</div><div class="ctl"><select id="voiceSel"></select></div></div>'); A.invoke('tts:info').then((t) => { const sel = w.querySelector('select'); sel.innerHTML = (t.voices || []).map((v) => `<option value="${esc(v.id)}">${esc(v.label)}</option>`).join(''); sel.value = t.voice || ''; const n = $('ttsInfo'); if (n) n.textContent = t.info; }); w.querySelector('select').onchange = (e) => { set({ voiceName: e.target.value }); }; return w; }
  if (it.type === 'presets') { const w = el('<div class="item"><div class="s">One click, then fine-tune below.</div><div class="chips ctl"></div></div>'); for (const [id, n] of [['classic', 'Classic'], ['minimal', 'Minimal'], ['neon', 'Neon'], ['calm', 'Calm'], ['glass', 'Glass'], ['contrast', 'High contrast'], ['bottom', 'Bottom glow']]) { const c = el(`<span class="chip">${n}</span>`); c.onclick = async () => { cfg = await A.invoke('look:preset', id); rerender(it.__sec); }; w.querySelector('.chips').appendChild(c); } return w; }
  if (it.type === 'times') { const w = el('<div class="item"><div class="tworow"><label class="s">From<input type="time" data-key="quietFrom"></label><label class="s">Until<input type="time" data-key="quietTo"></label></div></div>'); w.querySelectorAll('input').forEach((i) => { i.value = cfg[i.dataset.key] || ''; i.onchange = () => set({ [i.dataset.key]: i.value }); }); return w; }
  if (it.type === 'system') return buildSystem();
  const sub = typeof it.sub === 'function' ? it.sub() : it.sub; const inline = it.type === 'toggle'; const unit = it.unit && it.type === 'number' ? `<span class="s"> (${esc(it.unit)})</span>` : '';
  const w = el(`<div class="item${inline ? ' inline' : ''}">${it.title ? `<div><div class="t">${esc(it.title)}${unit}</div>${sub ? `<div class="s">${esc(sub)}</div>` : ''}</div>` : ''}<div class="ctl"></div>${it.note ? `<p class="hint">${esc(it.note)}</p>` : ''}</div>`);
  w.querySelector('.ctl').appendChild(controlFor(it)); return w;
}
function buildSection(sec) {
  const page = el(`<section class="page" id="page-${sec.id}"><h1>${esc(sec.title)}</h1><p class="sub">${esc(sec.sub || '')}</p></section>`);
  for (const g of sec.groups) { const items = g.items.filter((it) => !it.show || it.show(cfg)); if (!items.length) continue; if (g.title) page.appendChild(el(`<div class="gtitle">${esc(g.title)}</div>`)); const card = el('<div class="card list"></div>'); items.forEach((it) => card.appendChild(buildItem(it, sec))); page.appendChild(card); }
  return page;
}
function rerender(sec) { const old = $('page-' + sec.id); const on = old.classList.contains('on'); const p = buildSection(sec); if (on) p.classList.add('on'); old.replaceWith(p); pages[sec.id] = p; }

// shortcut recorder (like in real apps)
const MODNAMES = ['Control', 'Alt', 'Shift', 'Meta', 'AltGraph']; let recording = false, hkEl = null;
function keyName(e) {
  const c = e.code || ''; if (/^Digit\d$/.test(c)) return c.slice(5); if (/^Numpad\d$/.test(c)) return c.slice(6);
  const named = { Space: 'Space', Enter: 'Return', NumpadEnter: 'Return', Escape: 'Escape', Tab: 'Tab', Backspace: 'Backspace', Delete: 'Delete', Insert: 'Insert', Home: 'Home', End: 'End', PageUp: 'PageUp', PageDown: 'PageDown', ArrowUp: 'Up', ArrowDown: 'Down', ArrowLeft: 'Left', ArrowRight: 'Right' };
  const nk = named[e.key === ' ' ? 'Space' : e.key] || named[c]; if (nk) return nk; if (/^F\d{1,2}$/.test(e.key)) return e.key; if (e.key && e.key.length === 1) return e.key.toUpperCase();
  const sym = { Backquote: '`', Minus: '-', Equal: '=', Comma: ',', Period: '.', Slash: '/', Semicolon: ';', Quote: "'", BracketLeft: '[', BracketRight: ']', Backslash: '\\' }; return sym[c] || '';
}
function buildHotkey() {
  const w = el('<div class="item"><div class="t">Wake shortcut</div><div class="s">Press the keys you want, like in any app. Esc cancels.</div><div class="ctl row"><input class="grow" id="hotkey" readonly><button id="hkRec" class="filled" type="button">Press keys…</button></div><p class="hint" id="hkInfo"></p><pre id="hyprSnippet" hidden></pre></div>');
  const inp = w.querySelector('#hotkey'), btn = w.querySelector('#hkRec'); inp.value = cfg.hotkey || ''; hkEl = { inp, btn };
  w.querySelector('#hkInfo').textContent = D.hypr ? 'Hyprland: the shortcut is bound through hyprctl when you save it and re-applied each time the app starts.' : 'Works while another app is focused (X11 / Windows). On other Wayland compositors, bind a key to “Assistant --toggle” in your compositor.';
  btn.onclick = () => { if (recording) return stopRec(); recording = true; btn.textContent = 'Press the keys… (Esc cancels)'; inp.value = ''; A.invoke('hotkey:pause', true); };
  if (D.hypr) paintSnippet(w.querySelector('#hyprSnippet')); return w;
}
function stopRec(text) { recording = false; if (hkEl) { hkEl.btn.textContent = 'Press keys…'; hkEl.inp.value = text == null ? cfg.hotkey : text; } A.invoke('hotkey:pause', false); }
async function paintSnippet(pre) { pre = pre || $('hyprSnippet'); if (!pre || !D.hypr) return; const h = await A.invoke('hotkey:hypr', cfg.hotkey); pre.hidden = false; pre.textContent = `-- hyprland.lua (permanent; the app also re-binds it by itself when it starts)\nhl.bind("${h}", hl.dsp.exec_cmd('${D.launch} --toggle'))\n\n# hyprland.conf (older)\nbind = ${h.replace(/ \+ [^+]+$/, '').replace(/ \+ /g, ' ')}, ${h.split(' + ').pop()}, exec, ${D.launch} --toggle`; }
document.addEventListener('keydown', async (e) => {
  if (!recording) return; e.preventDefault(); e.stopPropagation();
  if (e.key === 'Escape' && !e.ctrlKey && !e.altKey && !e.metaKey) return stopRec();
  const mods = []; if (e.ctrlKey) mods.push('Ctrl'); if (e.altKey) mods.push('Alt'); if (e.shiftKey) mods.push('Shift'); if (e.metaKey) mods.push('Super');
  if (MODNAMES.includes(e.key)) { hkEl.inp.value = mods.join('+') + '+…'; return; }
  const k = keyName(e); if (!k) return;
  if (!mods.length && !/^F\d+$/.test(k)) { hkEl.inp.value = 'Hold Ctrl, Alt, Shift or Super too'; return; }
  const combo = mods.concat(k).join('+'); stopRec(combo); $('hk').textContent = combo;
  await set({ hotkey: combo, hotkeyBound: !!D.hypr }); paintSnippet(); toast('Shortcut saved: ' + combo);
}, true);

let updUrl = '', updCan = true;
function buildSystem() {
  const w = el(`<div><div class="item"><div class="t">Assistant ${esc(D.version || '')}</div><div class="s">${esc(D.platform)}</div><div class="ctl row"><button class="tonal" id="checkUpd" type="button">Check for updates</button></div></div></div>`);
  w.querySelector('#checkUpd').onclick = () => A.invoke('update:check'); return w;
}

// ---------------- home ----------------
function stats(u) { if (!u) return; D.usage = u; $('sToday').textContent = cfg.provider === 'hearth' ? 'plan' : money(u.today); $('sLeft').textContent = u.left == null ? 'set in Budget' : money(u.left); $('sLast').textContent = u.task && u.task.calls ? (cfg.provider === 'hearth' ? kk(u.task.in + u.task.out) + ' tok' : money(u.task.cost)) : '–'; }
function paintPlan(s) { const todos = (s && s.todos) || []; $('planCard').hidden = !todos.length; $('plan').innerHTML = todos.map((t) => `${{ done: '✓', doing: '▶', skipped: '–' }[t.status] || '○'}&nbsp; ${esc(t.text)}`).join('<br>'); }
const lines = []; function addLog(m) { lines.push(String(m).split('\n')[0].slice(0, 160)); if (lines.length > 14) lines.shift(); $('log').textContent = lines.join('\n'); }
function setStatus(s) { running = s.running; voice = s.voice; $('statusText').textContent = running ? 'Working…' : voice ? 'Listening…' : 'Ready'; $('dot').className = running || voice ? 'work' : ''; $('run').innerHTML = (running ? ICON.stop + ' Stop' : ICON.play + ' Run'); $('run').classList.toggle('stop', running); $('mic').innerHTML = voice ? ICON.stop : ICON.mic; }

function show(id) { document.querySelectorAll('.navi').forEach((n) => n.classList.toggle('on', n.dataset.id === id)); document.querySelectorAll('.page').forEach((p) => p.classList.toggle('on', p.id === 'page-' + id)); $('content').scrollTop = 0; }
async function init() {
  D = await A.invoke('cfg:all'); cfg = D.cfg; $('hk').textContent = cfg.hotkey; $('ver').textContent = 'v' + (D.version || '');
  $('warnings').innerHTML = D.problems.map((p) => `<div class="warn">⚠ ${esc(p)}</div>`).join('');
  const nav = $('nav'); const mk = (id, title, icon) => { const b = el(`<button class="navi" data-id="${id}" type="button">${ICON[icon] || ''}<span>${esc(title)}</span></button>`); b.onclick = () => show(id); nav.appendChild(b); };
  mk('home', 'Home', 'home'); nav.appendChild(el('<div class="navsep"></div>'));
  for (const sec of SECTIONS) { mk(sec.id, sec.title, sec.icon); const p = buildSection(sec); pages[sec.id] = p; $('content').appendChild(p); }
  show('home'); stats(D.usage);
}
$('run').addEventListener('click', () => { if (running) return A.invoke('ui:stop'); const t = $('task').value.trim(); if (!t) return toast('Describe a task first'); A.invoke('ui:run', t); });
$('mic').addEventListener('click', () => A.invoke('ui:voice'));
document.querySelectorAll('.ask .chip').forEach((c) => c.addEventListener('click', () => { $('task').value = c.textContent; $('task').focus(); }));
$('task').addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); $('run').click(); } });
$('planSend').addEventListener('click', () => { const t = $('planIn').value.trim(); if (t) { A.invoke('ui:interrupt', t); $('planIn').value = ''; } });
$('clear').addEventListener('click', () => { lines.length = 0; $('log').textContent = ''; });
A.on('update', (u) => { updUrl = u.url; updCan = u.canInstall; $('update').hidden = false; $('updB').textContent = u.canInstall ? 'Update now' : 'Download'; $('updT').textContent = 'Update available: v' + u.version; $('updN').textContent = u.notes; });
$('updB').addEventListener('click', () => (updCan ? A.invoke('update:install') : A.invoke('update:open', updUrl))); $('updPage').addEventListener('click', () => A.invoke('update:open', updUrl));
A.on('update:state', (s) => { $('updBar').hidden = s.phase !== 'downloading'; if (s.phase === 'downloading') { $('updBar').firstElementChild.style.width = Math.round(s.pct * 100) + '%'; $('updB').textContent = 'Downloading… ' + Math.round(s.pct * 100) + '%'; $('updB').disabled = true; } else if (s.phase === 'installing') $('updB').textContent = 'Installing…'; else if (s.phase === 'restarting') $('updB').textContent = 'Restarting…'; else if (s.phase === 'error') { $('updB').disabled = false; $('updB').textContent = 'Update now'; toast('Update failed: ' + s.error); } });
A.on('log', addLog); A.on('plan', paintPlan); A.on('usage', stats); A.on('status', setStatus); A.on('toast', toast);
A.on('facts', async () => { const d = await A.invoke('cfg:all'); cfg.facts = d.cfg.facts; cfg.lessons = d.cfg.lessons; document.querySelectorAll('[data-key="facts"],[data-key="lessons"]').forEach((t) => { if (document.activeElement !== t) t.value = cfg[t.dataset.key] || ''; }); });
A.on('tts', (s) => { const n = $('ttsInfo'); if (!n) return; if (s.progress != null && s.progress < 1) n.textContent = `Downloading ${s.label || 'the voice'}… ${Math.round(s.progress * 100)}%`; else if (s.info) n.textContent = s.info; else if (s.error) n.textContent = 'Voice problem: ' + s.error; });
A.on('stt', (s) => { const n = $('sttInfo'); if (!n) return; if (s.progress != null && s.progress < 1) n.textContent = `Downloading speech model… ${Math.round(s.progress * 100)}%`; if (s.ready) n.textContent = 'Speech model ready.'; });
$('mic').innerHTML = ICON.mic; $('run').innerHTML = ICON.play + ' Run';
init();
