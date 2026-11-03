// node test/stt-test.js [model] : transcribes a sample (downloads the model once)
const fs = require('fs'); const { Stt, fixNames } = require('../src/stt');
const cfg = { get: (k) => ({ whisperModel: process.argv[2] || 'base', lang: 'en' })[k] };
(async () => {
  const buf = fs.readFileSync('/tmp/s1.f32'); const f32 = new Float32Array(buf.buffer, buf.byteOffset, buf.length / 4);
  const stt = new Stt(cfg, '/tmp/hf-cache'); const t = Date.now();
  const text = await stt.transcribe(f32); console.log('text:', text, '|', Date.now() - t, 'ms'); stt.stop();
  console.log(fixNames('stuur een bericht naar Yan en Sane', ['Jan', 'Sanne']));
})().catch((e) => { console.error(e); process.exit(1); });
