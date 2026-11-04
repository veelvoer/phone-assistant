// Agent loop test with a mock API and a mock platform (no network, no desktop).
const assert = require('assert');
const os = require('os'); const path = require('path'); const fs = require('fs');
const { Agent } = require('../src/agent'); const { Config } = require('../src/config'); const { Usage } = require('../src/usage');
const zlib = require('zlib');
function png(w, h) { // minimal valid PNG header + payload (only IHDR is read)
  const sig = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]); const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2;
  const chunk = (t, d) => { const l = Buffer.alloc(4); l.writeUInt32BE(d.length); return Buffer.concat([l, Buffer.from(t), d, Buffer.alloc(4)]); };
  return Buffer.concat([sig, chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(Buffer.alloc(10))), chunk('IEND', Buffer.alloc(0))]);
}
const calls = []; const clicks = []; const shells = [];
const platform = {
  describe: () => 'Mock OS', problems: () => [], toGlobal: (x, y) => ({ x, y }),
  screenshot: async () => ({ png: png(1920, 1080), monitor: { name: 'M', pw: 1920, ph: 1080 } }),
  activeWindow: async () => ({ class: 'firefox', title: 'Test' }), listWindows: async () => [{ class: 'firefox', title: 'Test' }],
  click: async (x, y, o) => { clicks.push([x, y, o]); }, move: async () => {}, typeText: async () => {}, key: async () => {},
  shell: async (c) => { shells.push(c); return { code: 0, stdout: 'hello\n', stderr: '' }; }, getClipboard: async () => '', setClipboard: async () => {},
};
const script = [
  (b) => { assert.ok(b.messages[0].content[1] && b.messages[0].content[1].type === 'image'); return { content: [{ type: 'tool_use', id: 't1', name: 'plan', input: { screen_now: 'desktop', goal: 'say hello', done_when: 'output hello', items: [{ text: 'run echo', status: 'doing' }] } }] }; },
  (b) => ({ content: [{ type: 'text', text: 'Run it.' }, { type: 'tool_use', id: 't2', name: 'shell', input: { command: 'echo hello', why: 'test' } }, { type: 'tool_use', id: 't3', name: 'click', input: { x: 960, y: 540 } }] }),
  (b) => { const last = b.messages[b.messages.length - 1]; assert.ok(Array.isArray(last.content) && last.content.length === 2); assert.ok(JSON.stringify(last.content).includes('hello')); return { content: [{ type: 'tool_use', id: 't4', name: 'finish', input: { message: 'Klaar, het zegt hallo.', evidence: '' } }] }; },
  (b) => ({ content: [{ type: 'tool_use', id: 't5', name: 'plan', input: { goal: 'say hello', done_when: 'x', items: [{ text: 'run echo', status: 'done' }] } }, { type: 'tool_use', id: 't6', name: 'finish', input: { message: 'Klaar, het zegt hallo.', evidence: 'stdout hello' } }] }),
];
let n = 0;
const fetchImpl = async (url, opt) => { const body = JSON.parse(opt.body); calls.push(body); const r = script[n++](body); return { ok: true, status: 200, text: async () => JSON.stringify({ ...r, usage: { input_tokens: 1000, output_tokens: 50, cache_read_input_tokens: 4000 } }) }; };
// renderer scripts: syntax + no top-level binding that clashes with the preload's window.api (this once silently killed the whole UI)
for (const f of fs.readdirSync(path.join(__dirname, '../src/renderer')).filter((x) => x.endsWith('.js'))) {
  const src = fs.readFileSync(path.join(__dirname, '../src/renderer', f), 'utf8'); new Function(src);
  assert.ok(!/^(const|let|var|class)\s+api\b/m.test(src), f + ' declares a global "api"');
}
(async () => {
  const cfg = new Config(path.join(os.tmpdir(), 'as-test-' + process.pid + '.json')); cfg.set({ apiKey: 'sk-test', facts: 'mom = Anna Peeters' });
  const usage = new Usage(cfg); const spoke = []; const logs = [];
  const a = new Agent({ config: cfg, platform, usage, fetchImpl, speak: (t) => spoke.push(t) }); a.on('log', (m) => logs.push(m));
  await a.run('zeg hallo met de shell'); if (!shells.length) console.log(logs.join('\n'));
  assert.deepStrictEqual(shells, ['echo hello']); assert.strictEqual(clicks.length, 1); assert.deepStrictEqual(clicks[0].slice(0, 2), [960, 540]);
  assert.strictEqual(spoke[0], 'Klaar, het zegt hallo.'); assert.ok(calls[0].model === 'claude-sonnet-5-5', 'plan by sonnet'); assert.ok(calls[1].model === 'claude-haiku-5-5');
  assert.ok(calls[0].messages[0].content[0].text.includes('mom = Anna Peeters'));
  assert.ok(logs.some((l) => l.startsWith('💰')) && usage.taskCost > 0);
  assert.ok(JSON.stringify(calls[2].messages).includes('"type":"image"'), 'screenshot after GUI action');
  // second command sees notes
  n = 3; script.push((b) => { assert.ok(b.messages[0].content[0].text.includes('Earlier commands this session')); return { content: [{ type: 'tool_use', id: 'u1', name: 'plan', input: { goal: 'g', done_when: 'd', items: [] } }, { type: 'tool_use', id: 'u2', name: 'finish', input: { message: 'ok', evidence: 'e' } }] }; });
  n = 4; await a.run('nee, doe het opnieuw');
  // shortcut
  const mem = await a.run('onthoud dat mijn broer Lars heet'); assert.ok(cfg.facts().some((f) => f.includes('Lars')));
  // dangerous shell blocked
  const { buildTools } = require('../src/tools'); const t = buildTools({ ...a.makeCtx(), config: cfg }); const sh = t.find((x) => x.name === 'shell');
  assert.ok((await sh.run({ command: 'rm -rf ~/x' })).startsWith('blocked')); assert.ok((await sh.run({ command: 'ls' })).includes('exit 0'));
  // learning from corrections
  assert.ok(a.learn("'Jan' = Jan Peeters, niet Jan Claes").startsWith('learned')); assert.ok(cfg.lessons().length === 1);
  a.learn("'Jan' = Jan Peeters, niet Jan Claes (Smartschool)"); assert.strictEqual(cfg.lessons().length, 1, 'refined lesson replaces the old one');
  n = 5; script.push((b) => { const t0 = b.messages[0].content[0].text; assert.ok(t0.includes('Lessons from earlier corrections') && t0.includes('Jan Peeters')); assert.ok(t0.includes('CORRECTING your previous work')); return { content: [{ type: 'tool_use', id: 'v1', name: 'plan', input: { goal: 'g', done_when: 'd', items: [] } }, { type: 'tool_use', id: 'v2', name: 'finish', input: { message: 'ok', evidence: 'e' } }] }; });
  await a.run('nee, ik bedoelde Jan Claes');
  assert.ok(a.forget('Jan Peeters').includes('forgot') && cfg.lessons().length === 0);
  const { explain } = require('../src/errors');
  assert.ok(/API-sleutel/.test(explain('API 401: invalid x-api-key', true))); assert.ok(/credit/.test(explain('API 400: Your credit balance is too low', false))); assert.ok(/internet/.test(explain('NETWORK: fetch failed', true)));
  // network failure is retried and then reported, not a crash
  let tries = 0; const flaky = new Agent({ config: cfg, platform, usage, fetchImpl: async () => { tries++; throw new Error('fetch failed'); }, speak: () => {} });
  const said = []; flaky.on('result', (m) => said.push(m)); const realSleep = global.setTimeout; await flaky.run('doe iets'); assert.strictEqual(tries, 3); assert.ok(/internet/.test(said[0] || ''), 'network error is explained: ' + said[0]);
  console.log('agent tests passed');
})().catch((e) => { console.error('TEST FAILED', e); process.exit(1); });
