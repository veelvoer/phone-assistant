// node test/platform-smoke.js : checks what works on this machine (no clicks are made)
const { create } = require('../src/platform');
(async () => {
  const p = await create({}).init();
  console.log(p.describe(), 'uinput:', p.canUinput);
  console.log('problems:', p.problems());
  const t = Date.now(); const s = await p.screenshot();
  console.log('screenshot', s.png.length, 'bytes', JSON.stringify(s.monitor), Date.now() - t, 'ms');
  console.log('active', await p.activeWindow());
  console.log('windows', (await p.listWindows()).slice(0, 5));
  await p.move(500, 400); console.log('moved to', await require('../src/platform/util').run('hyprctl', ['cursorpos']).then((r) => r.stdout.trim()));
  console.log('desktop entry for firefox:', await p.findDesktop('firefox'));
  console.log('clipboard len', (await p.getClipboard()).length);
  p.dispose();
})().catch((e) => { console.error('FAIL', e); process.exit(1); });
