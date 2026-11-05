'use strict';
// Fetches the speech-model files ourselves (resumable, size-checked) into the folder transformers.js reads from.
// transformers.js' own downloader gave up half-way through the big files, leaving a model that fails to load.
const fs = require('fs');
const path = require('path');

const MODELS = {
  low: { repo: 'onnx-community/whisper-base', suffix: 'quantized' }, medium: { repo: 'onnx-community/whisper-small', suffix: 'quantized' }, high: { repo: 'onnx-community/whisper-large-v3-turbo', suffix: 'q4' },
  base: { repo: 'onnx-community/whisper-base', suffix: 'quantized' }, small: { repo: 'onnx-community/whisper-small', suffix: 'quantized' }, tiny: { repo: 'onnx-community/whisper-tiny', suffix: 'quantized' },
};
const SMALL_FILES = ['config.json', 'generation_config.json', 'preprocessor_config.json', 'tokenizer.json', 'tokenizer_config.json', 'vocab.json', 'merges.txt', 'normalizer.json', 'added_tokens.json', 'special_tokens_map.json'];
const REQUIRED = new Set(['config.json', 'generation_config.json', 'preprocessor_config.json', 'tokenizer.json', 'tokenizer_config.json']);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function filesFor(spec) { return [...SMALL_FILES, `onnx/encoder_model_${spec.suffix}.onnx`, `onnx/decoder_model_merged_${spec.suffix}.onnx`]; }
const url = (repo, f) => `https://huggingface.co/${repo}/resolve/main/${f}`;

async function remoteSize(u, fetchImpl) { const r = await fetchImpl(u, { method: 'HEAD', redirect: 'follow' }); if (r.status === 404) return -1; if (!r.ok) throw new Error('HTTP ' + r.status); return Number(r.headers.get('x-linked-size') || r.headers.get('content-length')) || 0; }

async function fetchFile(u, dest, size, onBytes, fetchImpl) {
  fs.mkdirSync(path.dirname(dest), { recursive: true }); const part = dest + '.part'; let lastErr;
  for (let attempt = 0; attempt < 8; attempt++) {
    try {
      const have = fs.existsSync(part) ? fs.statSync(part).size : 0;
      if (size && have === size) break;
      const r = await fetchImpl(u, { redirect: 'follow', headers: have ? { Range: `bytes=${have}-` } : {} });
      if (r.status === 416) break; if (!(r.status === 200 || r.status === 206)) throw new Error('HTTP ' + r.status);
      const resumed = r.status === 206; const out = fs.createWriteStream(part, { flags: resumed ? 'a' : 'w' }); if (!resumed && have) onBytes(-have);
      const reader = r.body.getReader();
      await new Promise((resolve, reject) => { out.on('error', reject); out.on('finish', resolve); (async () => { for (;;) { const { value, done } = await reader.read(); if (done) break; out.write(Buffer.from(value)); onBytes(value.length); } out.end(); })().catch((e) => { out.end(); reject(e); }); });
      if (!size || fs.statSync(part).size === size) break;
      throw new Error('incomplete');
    } catch (e) { lastErr = e; await sleep(1000 * (attempt + 1)); }
  }
  if (size && (!fs.existsSync(part) || fs.statSync(part).size !== size)) throw new Error(`download of ${path.basename(dest)} failed${lastErr ? ': ' + lastErr.message : ''}`);
  fs.renameSync(part, dest);
}

/** true when every file of the model is on disk with the right size */
function isReady(model, cacheDir) {
  const spec = MODELS[model] || MODELS.medium; const marker = path.join(cacheDir, spec.repo, `.complete-${spec.suffix}`);
  return fs.existsSync(marker);
}

async function ensure(model, cacheDir, onProgress = () => {}, fetchImpl = fetch) {
  const spec = MODELS[model] || MODELS.medium; const root = path.join(cacheDir, spec.repo); const marker = path.join(root, `.complete-${spec.suffix}`);
  if (fs.existsSync(marker)) return;
  const list = []; let total = 0;
  for (const f of filesFor(spec)) { const u = url(spec.repo, f); let size; try { size = await remoteSize(u, fetchImpl); } catch (e) { if (REQUIRED.has(f) || f.endsWith('.onnx')) throw new Error(`${f}: ${e.message}`); size = -1; } if (size < 0) { if (REQUIRED.has(f) || f.endsWith('.onnx')) throw new Error(`${f}: not found`); continue; } list.push({ f, u, size }); total += size; }
  let done = 0; for (const { f, u, size } of list) {
    const dest = path.join(root, f); if (fs.existsSync(dest) && (!size || fs.statSync(dest).size === size)) { done += size; onProgress(total ? done / total : 0); continue; }
    await fetchFile(u, dest, size, (n) => { done += n; onProgress(total ? Math.max(0, Math.min(1, done / total)) : 0); }, fetchImpl);
  }
  fs.writeFileSync(marker, new Date().toISOString());
}

module.exports = { ensure, isReady, MODELS, filesFor };
