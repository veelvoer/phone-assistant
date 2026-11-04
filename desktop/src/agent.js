'use strict';
const crypto = require('crypto');
const { EventEmitter } = require('events');
const img = require('./img');
const { buildTools, specFor } = require('./tools');
const { sleep } = require('./platform/util');
const { explain } = require('./errors');

const RULES = `How to work (follow strictly):
0a. LOOK FIRST. Before every decision read the latest screenshot and the window list: what is on screen, is the target already visible? Say it in "why". Only act on what you actually see. Coordinates for click/drag/zoom/highlight are pixels in the latest screenshot.
0b. PREFER THE CHEAP WAY. Shell commands, open_url, open_app, read_file/write_file are faster and far cheaper than clicking. Use the GUI (click/type/key/scroll) only when there is no direct way. After every GUI action you automatically get a fresh screenshot.
0. GOAL FIRST. Your first action is always plan: screen_now = what you see, goal = the concrete end result the user wants (one sentence), done_when = what exactly must be visible/true to prove it, items = 2-8 steps. The goal and list are shown to you every turn. Everything you do must serve the goal: use every tool until it is reached; never stop at a partial result; never say you cannot without trying at least three different approaches. Update plan whenever a step is done, in the same turn as your next action. Every tool has a "why" field: one short sentence on how the action serves the goal.
1. Before every action write 1-2 short sentences: GOAL, what you see, whether the last action worked, your next step.
2. After every action check the result: did it do what you intended? If not, try something different (other element, other route, zoom, key shortcut, shell). Never repeat a failing action more than twice.
3a. Questions: answer exactly what was asked from what you actually see or measured (exact label, exact number). Never answer from guesses or memory when the computer can tell you.
3. Finish only when done_when is verified: call finish with evidence = what you saw/measured that proves it. For multi-part requests do every part. If the user asked to be SHOWN something, find it, call highlight, then finish with a short spoken answer.
4. Ask the user (ask_user) when the request is unclear or does not match what you see, when several options fit, when something unexpected blocks you (login, captcha, permission), before irreversible or destructive actions (deleting files, sudo, installing/removing software, sending money/messages the user did not dictate), or after two failed approaches. Ask short concrete questions. Do not ask when the request is clear.
5. Be efficient: fewest steps. Several tool calls per turn are fine when they do not depend on a changed screen.
6. The user may interrupt while you work. Lines "User then added (INTERRUPT…): …" are new instructions: first update the plan, then continue. "Earlier commands this session" lists previous commands; a new message is ALWAYS a new job (a correction, an undo, a follow-up): look at the current state and do it, never reply that it is already done.
7. The user may speak Dutch. Spoken messages (finish.message, ask_user.question) are READ ALOUD by a voice: write natural, warm, complete sentences in the user's language (1-2 sentences, at most ~35 words), result first, like a helpful person talking. No markdown, emoji, lists, paths or technical words. Say numbers and units the way you say them.
8a. Learning: "Lessons from earlier corrections" are rules the user taught you: follow them over your own guess. When the user corrects you ("nee, ik bedoelde…", "niet die", "no, I meant…"): fix it first, then call learn with ONE short, specific rule that prevents the same mistake (what they meant vs what you did). Only learn from what the user told you, not from your own failures.
8. Memory: "About the user" lists saved facts. Use them to resolve "my mom", "me" etc. When the user states a new lasting fact, call remember with a very short fact. Never save passwords or codes.
9. Do everything the user says: it is their own computer. Be careful with irreversible commands (see 4).`;

const SYSTEM = (desc) => `You control the user's computer (${desc}) with tools to complete their request. Each turn you receive the window list and a screenshot of the screen.\n${RULES}`;

const SYSTEM_TEXT = (desc, tools) => `You control the user's computer (${desc}) to complete their request. You cannot see the screen: work with shell commands, files, links and keyboard shortcuts.
Reply with your 1-2 sentence thinking, then a JSON array of actions (nothing after it), e.g.
GOAL: list the files in Downloads. [{"tool":"shell","command":"ls ~/Downloads"}]
Tools:
${tools.map((t) => `${t.name}{${Object.keys(t.props).join(',')}} - ${t.description.split('.')[0]}`).join('\n')}
${RULES}
Always end your reply with the JSON array of actions. When done: [{"tool":"finish","message":"…","evidence":"…"}]`;

const STOP_WORDS = new Set(['stop', 'stoppen', 'stop maar', 'stop met luisteren', 'klaar', 'dat was het', 'annuleer', 'annuleren', 'cancel', "that's all", 'exit', 'stop listening']);
const norm = (x) => String(x).normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase().replace(/\s+/g, ' ').trim();
const stripThinking = (a) => a.filter((b) => b.type !== 'thinking' && b.type !== 'redacted_thinking');
const spoken = (m) => { const ss = String(m).trim().split(/(?<=[.!?])\s+/); let o = ss[0] || ''; if (ss.length > 1 && (o + ' ' + ss[1]).length <= 280) o += ' ' + ss[1]; return o.slice(0, 300); };

class Agent extends EventEmitter {
  constructor({ config, platform, usage, ask, speak, fetchImpl }) {
    super();
    this.config = config; this.platform = platform; this.usage = usage; this.askImpl = ask; this.speakImpl = speak || (() => {}); this.fetch = fetchImpl || fetch;
    this.running = false; this.stopFlag = false; this.extras = []; this.notes = []; this.runDid = []; this.noted = false;
    this.state = { goal: '', doneWhen: '', todos: [] }; this.shotInfo = null; this.wantShot = false; this.pendingImages = []; this.scrollStreak = 0;
  }

  log(m) { this.emit('log', m); }
  stop() { this.stopFlag = true; if (this.pendingAsk) { this.pendingAsk(null); } }
  interrupt(t) { if (this.running && t && t.trim()) { this.extras.push(t.trim()); this.log('➕ ' + t.trim()); return true; } return false; }

  // ---- memory ----
  remember(fact) {
    const f = String(fact).trim().replace(/\.$/, '').slice(0, 110); if (f.length < 3) return 'error: fact too short';
    const key = /[=:]/.test(f) ? f.split(/[=:]/)[0].trim().toLowerCase() : null;
    const l = this.config.facts().filter((x) => x.toLowerCase() !== f.toLowerCase() && !(key && /[=:]/.test(x) && x.split(/[=:]/)[0].trim().toLowerCase() === key));
    l.push(f); while (l.join('\n').length > 1800) l.shift();
    this.config.set({ facts: l.join('\n') }); this.emit('facts'); return 'remembered: ' + f;
  }
  forget(q) {
    const n = norm(q); if (n.length < 2) return 'error: say what to forget';
    const l = this.config.facts(), keep = l.filter((x) => !norm(x).includes(n)); const ls = this.config.lessons(), lk = ls.filter((x) => !norm(x).includes(n));
    this.config.set({ facts: keep.join('\n'), lessons: lk.join('\n') }); this.emit('facts'); return `forgot ${l.length - keep.length + ls.length - lk.length} item(s)`;
  }
  learn(lesson) {
    const t = String(lesson).trim().replace(/\.$/, '').slice(0, 160); if (t.length < 8) return 'error: lesson too short';
    const n = norm(t); const l = this.config.lessons().filter((x) => { const o = norm(x); return !(o === n || o.includes(n) || n.includes(o)); }); l.push(t);
    while (l.join('\n').length > 1800) l.shift();
    this.config.set({ lessons: l.join('\n') }); this.log('🎓 learned: ' + t); this.emit('facts'); return 'learned: ' + t;
  }

  // ---- screen ----
  async freshShot() {
    const { png, monitor } = await this.platform.screenshot();
    const p = img.prepare(png, 1280);
    this.shotInfo = { w: p.w, h: p.h, nativeW: p.nativeW, nativeH: p.nativeH, mime: p.mime, monitor };
    this.lastPng = png;
    return { png, nativeW: p.nativeW, nativeH: p.nativeH, w: p.w, h: p.h, prepared: p, monitor };
  }
  imageBlock(p) { return { type: 'image', source: { type: 'base64', media_type: p.mime, data: Buffer.from(p.data).toString('base64') } }; }

  async windowsLine() {
    try {
      const a = await this.platform.activeWindow(); const ws = (await this.platform.listWindows()).slice(0, 10);
      return `active window: ${a.class || '?'} — ${String(a.title || '').slice(0, 90)}` + (ws.length ? ` · open: ${ws.map((w) => `${w.class}: ${String(w.title).slice(0, 40)}`).join(' | ')}` : '');
    } catch { return ''; }
  }

  planText() {
    const s = this.state;
    return (s.goal ? `\n🎯 GOAL: ${s.goal}` + (s.doneWhen ? `\n✔ DONE WHEN: ${s.doneWhen}` : '') : '') + (s.todos.length ? '\nTo-do list:\n' + s.todos.map((t, i) => `${i + 1}. [${t.status}] ${t.text}`).join('\n') : '');
  }

  // ---- API ----
  async api(body, cfg) {
    const hearth = cfg.provider === 'hearth';
    const url = hearth ? cfg.hearthUrl.replace(/\/+$/, '').replace(/\/v1\/messages$/, '') + '/v1/messages' : 'https://api.anthropic.com/v1/messages';
    let last = '';
    for (let attempt = 0; attempt < 3; attempt++) {
      if (this.stopFlag) throw new Error('stopped');
      let r, text;
      try {
        r = await this.fetch(url, { method: 'POST', headers: { 'content-type': 'application/json', 'x-api-key': hearth ? cfg.hearthKey : cfg.apiKey, 'anthropic-version': '2023-06-01' }, body: JSON.stringify(body), signal: AbortSignal.timeout(hearth ? 300000 : 90000) });
        text = await r.text();
      } catch (e) { last = 'NETWORK: ' + (e && e.message ? e.message : e); if (attempt < 2) await sleep(1500 * (attempt + 1)); continue; }
      if (r.ok) return JSON.parse(text);
      last = `API ${r.status}: ${text.slice(0, 900)}`;
      if (r.status !== 429 && r.status !== 529 && r.status < 500) throw new Error(last);
      await sleep(2000 * (attempt + 1));
    }
    throw new Error(last);
  }

  parseActions(s) {
    const a = s.indexOf('['), b = s.lastIndexOf(']');
    try { if (a >= 0 && b > a) return JSON.parse(s.slice(a, b + 1)).filter((x) => x && typeof x === 'object'); const x = s.indexOf('{'), y = s.lastIndexOf('}'); if (x >= 0 && y > x) return [JSON.parse(s.slice(x, y + 1))]; } catch { /* none */ }
    return [];
  }

  // ---- shortcuts without the model ----
  async shortcut(task) {
    const t = task.trim();
    let m = /^(onthoud|onthou|remember)( dat| that)?\s+(.{3,150})$/i.exec(t);
    if (m) { const r = this.remember(m[3]); this.log('🧠 ' + r + ' (no AI needed)'); this.emit('result', 'Onthouden: ' + m[3]); this.speakImpl('Onthouden'); return true; }
    m = /^(vergeet|forget)( dat| that)?\s+(.{2,100})$/i.exec(t);
    if (m) { const r = this.forget(m[3]); this.log('🧠 ' + r + ' (no AI needed)'); this.emit('result', r); this.speakImpl('Vergeten'); return true; }
    m = /^(open|start|launch|ga naar)\s+(.{2,40})$/i.exec(t);
    if (m && this.platform.findDesktop) { const d = await this.platform.findDesktop(m[2].trim()); if (d && d.score === 3) { const r = await this.platform.openApp(d.name); this.log('⚡ ' + r + ' (no AI needed)'); this.emit('result', r); return true; } }
    return false;
  }

  addNote(task, result) { this.noted = true; this.notes.push(`User said: "${task}"\n  You did: ${this.runDid.slice(-20).join('; ').slice(0, 700)}\n  Result: ${result}`); while (this.notes.length > 4) this.notes.shift(); }

  // ---- the loop ----
  async run(task) {
    if (this.running) return false;
    this.running = true; this.stopFlag = false; this.extras = []; this.state = { goal: '', doneWhen: '', todos: [] }; this.runDid = []; this.noted = false; this.scrollStreak = 0; this.usage.begin();
    this.emit('start', task); this.log('▶ ' + task);
    try {
      if (await this.shortcut(task)) return true;
      await this.loop(task);
    } catch (e) {
      const raw = e && e.message ? e.message : String(e); this.log('Error: ' + raw);
      if (raw !== 'stopped') { const say = explain(raw, this.config.get('lang') !== 'en'); this.speakImpl(say); this.emit('result', say); }
    }
    if (!this.noted) this.addNote(task, '(did not finish: stopped or failed)');
    this.log('💰 ' + `${this.usage.taskIn} in · ${this.usage.taskOut} out · ${this.usage.calls} calls ≈ $${this.usage.taskCost.toFixed(4)}`);
    this.running = false; this.emit('end');
    return true;
  }

  async loop(task) {
    const cfg = this.config.all(); const hearth = cfg.provider === 'hearth';
    if (!hearth && !cfg.apiKey) throw new Error('No API key: open Settings and add your Claude API key.');
    if (hearth && (!cfg.hearthUrl || !cfg.hearthKey)) throw new Error('Hearth URL and key are required in Settings.');
    let model = hearth ? cfg.hearthModel : cfg.model; let noThinking = false; let smartOk = true; let stall = 0, lastDone = 0, same = 0, nudges = 0, lastHash = '', finishWarned = false, evWarned = false;
    const desc = this.platform.describe();
    const ctx = this.makeCtx();
    const tools = buildTools(ctx); const byName = Object.fromEntries(tools.map((t) => [t.name, t]));
    const finishSpec = { name: 'finish', description: 'End the task ONLY when done_when is verified (or truly impossible). message = one or two natural spoken sentences (result first). evidence = what you saw/measured that proves done_when.', input_schema: { type: 'object', properties: { message: { type: 'string' }, evidence: { type: 'string' }, why: { type: 'string' } }, required: ['message', 'evidence'] } };
    const allSpecs = [...tools.map(specFor), finishSpec]; allSpecs[allSpecs.length - 1].cache_control = { type: 'ephemeral' };
    const escalate = (why) => { if (!hearth && model.includes('haiku')) { model = 'claude-sonnet-5-5'; noThinking = true; turns.forEach((t) => { t.assistant = stripThinking(t.assistant); }); this.log(`⬆ ${why}: switching to a stronger model for this task`); } };
    const turns = [];
    let taskText = task;
    if (this.notes.length && /^(nee|neen|nope|niet|fout|verkeerd|verkeerde|ik bedoel|bedoel|dat is niet|dat klopt niet|no|wrong|that's wrong|thats wrong|i meant|i mean|not that)\b/i.test(task.trim())) taskText += '\n(The user is CORRECTING your previous work: fix it, then call learn with one short general rule so this mistake is not repeated.)';
    let first = !hearth ? await this.freshShot() : null;
    const winLine = await this.windowsLine();

    for (let step = 1; step <= 60; step++) {
      while (this.extras.length) { const x = this.extras.shift(); taskText += `\nUser then added (INTERRUPT: update your plan to reflect this, then continue): ${x}`; this.log('➕ ' + x); }
      if (this.stopFlag) { this.log('■ Stopped'); return; }
      const facts = this.config.facts(), names = this.config.names(), aliases = this.config.aliases();
      const lessons = this.config.lessons();
      const head = (lessons.length ? `Lessons from earlier corrections by the user (ALWAYS follow them):\n${lessons.join('\n')}\n\n` : '') + (facts.length ? `About the user (saved memory; use it to resolve "my mom", "me", etc.):\n${facts.join('\n')}\n\n` : '')
        + (names.length ? `Known names/words of the user (speech recognition often mishears names: correct what they said to the closest of these): ${names.join(', ')}${aliases.length ? '\nLearned corrections (heard=actual): ' + aliases.join('; ') : ''}\n\n` : '')
        + (this.notes.length ? `Earlier commands this session (the new message may correct, undo or continue them; check the screen and fix what is wrong):\n${this.notes.join('\n')}\n\n` : '');
      const firstText = head + `Task: ${taskText}` + (turns.length === 0 ? `\n\n${winLine}\n(First call the plan tool.)` : '');
      const firstContent = hearth ? firstText : [{ type: 'text', text: firstText }].concat(turns.length === 0 && first ? [this.imageBlock(first.prepared)] : []);
      const msgs = [{ role: 'user', content: firstContent }];
      turns.forEach((t, i) => {
        const last = i === turns.length - 1;
        if (hearth) { msgs.push({ role: 'assistant', content: t.raw }); msgs.push({ role: 'user', content: (t.briefs.length ? t.briefs.join('\n') : 'Continue.') + (last ? '\n' + t.observation : '') }); return; }
        msgs.push({ role: 'assistant', content: t.assistant });
        if (!t.ids.length) { msgs.push({ role: 'user', content: last ? t.obs : [{ type: 'text', text: 'Continue.' }] }); return; }
        msgs.push({ role: 'user', content: t.ids.map((id, j) => ({ type: 'tool_result', tool_use_id: id, content: last && j === t.ids.length - 1 ? t.obs : t.briefs[j], ...(i === turns.length - 2 && j === t.ids.length - 1 ? { cache_control: { type: 'ephemeral' } } : {}) })) });
      });
      const planCall = step === 1 && turns.length === 0;
      const callModel = !hearth && planCall && cfg.smartPlan && smartOk && model.includes('haiku') ? 'claude-sonnet-5-5' : model;
      const body = { model: callModel, max_tokens: 900, messages: msgs };
      if (hearth) { body.system = SYSTEM_TEXT(desc, tools); body.stream = false; }
      else {
        body.system = [{ type: 'text', text: SYSTEM(desc), cache_control: { type: 'ephemeral' } }]; body.tools = allSpecs;
        if (callModel.includes('haiku')) body.tool_choice = planCall ? { type: 'tool', name: 'plan' } : { type: 'any' };
        if (callModel.includes('sonnet')) body.output_config = { effort: 'medium' };
        if (noThinking) body.thinking = { type: callModel.includes('haiku') ? 'disabled' : 'between_tools' };
      }
      this.emit('status', 'Thinking…');
      let resp;
      try { resp = await this.api(body, cfg); } catch (e) {
        if (!hearth && callModel !== model && smartOk && /API (400|403|404)/.test(e.message || '')) { smartOk = false; this.log('↻ planning model unavailable, using the main model'); continue; }
        if (!hearth && !noThinking && /thinking/.test(e.message || '')) { noThinking = true; turns.forEach((t) => { t.assistant = stripThinking(t.assistant); }); this.log('↻ retrying without thinking blocks'); continue; }
        throw e;
      }
      this.usage.record(callModel, resp.usage, hearth); this.emit('usage', this.usage.summary());
      const content = callModel !== model ? stripThinking(resp.content) : resp.content;
      const raw = resp.content.filter((b) => b.type === 'text').map((b) => b.text).join('\n');
      let calls = [];
      if (hearth) { this.log('💬 ' + raw.split('[')[0].trim().slice(0, 300)); this.parseActions(raw).forEach((a, k) => calls.push({ id: `h${step}-${k}`, name: a.tool, input: a })); }
      else for (const b of content) { if (b.type === 'text' && b.text.trim()) this.log('💬 ' + b.text.trim()); if (b.type === 'tool_use') calls.push({ id: b.id, name: b.name, input: b.input || {} }); }
      if (!calls.length) {
        if (++nudges > 3) { this.log('■ Done'); return; }
        const ob = 'You must call a tool now: continue towards the goal, ask_user if unsure, or finish if the goal is verified done.\n' + await this.windowsLine() + this.planText();
        turns.push({ assistant: content, ids: [], briefs: [], obs: [{ type: 'text', text: ob }], raw, observation: ob }); continue;
      }
      const ids = [], briefs = []; let guiUsed = false;
      for (const c of calls) {
        if (this.stopFlag) { this.log('■ Stopped'); return; }
        if (c.input && c.input.why) this.log('💬 ' + c.input.why);
        const shown = { ...c.input }; delete shown.why; this.log('→ ' + c.name + ' ' + JSON.stringify(shown).slice(0, 220));
        if (c.name !== 'scroll') this.scrollStreak = 0;
        if (c.name === 'finish') {
          if (this.extras.length) { ids.push(c.id); briefs.push('NOT finished: the user just gave new instructions (see Task below). Do them first, then finish.'); continue; }
          if (!String(c.input.evidence || '').trim() && !evWarned) { evWarned = true; ids.push(c.id); briefs.push(`Finish rejected: give evidence = what on the CURRENT screen / output proves done_when (${this.state.doneWhen}). Check first; if the goal is not reached yet, keep working.`); continue; }
          const open = this.state.todos.filter((t) => t.status === 'pending' || t.status === 'doing');
          if (!finishWarned && open.length) { finishWarned = true; ids.push(c.id); briefs.push('Not finished: to-do items still open (' + open.map((t) => t.text).join('; ') + '). Complete them, or mark them done/skipped with the plan tool. Then finish.'); continue; }
          const m = String(c.input.message || ''); this.log('✅ ' + m); this.addNote(task, m); this.speakImpl(spoken(m)); this.emit('result', m);
          for (const c2 of calls) if (!ids.includes(c2.id)) { ids.push(c2.id); briefs.push(c2.id === c.id ? 'Finished.' : 'skipped'); }
          turns.push({ assistant: content, ids, briefs, obs: [{ type: 'text', text: 'Finished.' }], raw, observation: 'Finished.' });
          return;
        }
        const tool = byName[c.name];
        let brief;
        if (!tool) brief = 'error: unknown tool ' + c.name;
        else {
          this.emit('status', this.friendly(c.name, c.input));
          try { brief = String(await tool.run(c.input)); } catch (e) { brief = 'error: ' + (e && e.message ? e.message : e); }
          if (tool.gui) guiUsed = true;
          if (c.name !== 'plan' && c.name !== 'wait' && c.name !== 'screenshot') this.runDid.push(`${c.name} ${JSON.stringify(Object.fromEntries(Object.entries(c.input).filter(([k]) => k !== 'why'))).slice(0, 60)}`);
        }
        ids.push(c.id); briefs.push(brief);
      }
      await sleep(guiUsed ? 700 : 250);
      stall += 2 * briefs.filter((b) => /^error/.test(b)).length;
      const doneNow = this.state.todos.filter((t) => t.status === 'done').length;
      if (doneNow > lastDone) { lastDone = doneNow; stall = 0; } else stall++;
      if (stall >= 8) escalate(`no progress for ${stall} steps`);
      if (!hearth && this.usage.taskCost > (cfg.costCap || 0.3)) { this.log(`■ Cost limit ($${cfg.costCap}) reached for one task`); this.speakImpl('Gestopt: deze taak werd te duur'); this.emit('result', 'Stopped: cost limit reached'); return; }
      const blocks = []; let warn = '';
      if (!hearth && (guiUsed || this.wantShot || this.pendingImages.length)) {
        const s = await this.freshShot(); const hash = crypto.createHash('md5').update(s.prepared.data).digest('hex');
        if (guiUsed && hash === lastHash) { same++; warn = '\n⚠ The screen did NOT change after your last action. Do not repeat it: pick a different element or approach.' + (same >= 2 ? ' You are stuck: call ask_user now.' : ''); if (same >= 2) escalate('stuck'); } else same = 0;
        lastHash = hash; blocks.push(this.imageBlock(s.prepared));
      }
      for (const p of this.pendingImages.splice(0)) blocks.push(this.imageBlock(p));
      this.wantShot = false;
      const observation = (briefs[briefs.length - 1] || '') + warn + '\n' + (this.shotInfo && !hearth ? `Screenshot ${this.shotInfo.w}x${this.shotInfo.h}px (use these coordinates). ` : '') + await this.windowsLine() + this.planText();
      turns.push({ assistant: content, ids, briefs, obs: [{ type: 'text', text: observation }, ...blocks], raw, observation });
    }
    this.log('■ Step limit reached');
  }

  friendly(name, i) {
    return ({ open_app: 'Opening ' + (i.name || ''), open_url: 'Opening link', click: 'Clicking', double_click: 'Double-clicking', right_click: 'Right-clicking', drag: 'Dragging', scroll: 'Scrolling', type: 'Typing “' + String(i.text || '').slice(0, 40) + '”', key: 'Pressing ' + (i.key || 'a key'), shell: 'Running a command', read_file: 'Reading a file', write_file: 'Writing a file', screenshot: 'Looking at the screen', zoom: 'Zooming in', highlight: 'Showing you', ask_user: 'Asking you', plan: 'Planning', wait: 'Waiting…', focus_window: 'Switching window', windows: 'Checking windows' })[name] || 'Working…';
  }

  makeCtx() {
    const self = this;
    return {
      platform: this.platform, config: this.config, state: this.state,
      get shotInfo() { return self.shotInfo; }, get wantShot() { return self.wantShot; }, set wantShot(v) { self.wantShot = v; },
      get pendingImages() { return self.pendingImages; }, get scrollStreak() { return self.scrollStreak; }, set scrollStreak(v) { self.scrollStreak = v; },
      freshShot: () => this.freshShot(), log: (m) => this.log(m), emit: (e, d) => this.emit(e, d),
      remember: (f) => this.remember(f), forget: (q) => this.forget(q), learn: (l) => this.learn(l),
      ask: (q) => { this.log('❓ ' + q); this.speakImpl(q); this.emit('ask', q); return new Promise((res) => { this.pendingAsk = (a) => { this.pendingAsk = null; res(a); }; this.emit('asking', q); }); },
    };
  }

  /** an utterance/typed text while the agent waits for an answer */
  answer(t) { if (this.pendingAsk) { this.pendingAsk(t); return true; } return false; }
}

module.exports = { Agent, STOP_WORDS, norm, spoken };
