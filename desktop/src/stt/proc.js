'use strict';
// Runs Whisper (transformers.js + onnxruntime) in its own process (Electron-as-Node): onnxruntime must not share the process with Chromium.
const workerData = { cacheDir: process.env.ASSISTANT_STT_CACHE };
const parentPort = { postMessage: (m) => process.send(m), on: (_e, f) => process.on('message', f) };
let asr = null, loadedId = '';
const MODELS = { low: 'onnx-community/whisper-base', base: 'onnx-community/whisper-base', medium: 'onnx-community/whisper-small', small: 'onnx-community/whisper-small', high: 'onnx-community/whisper-large-v3-turbo', tiny: 'onnx-community/whisper-tiny' };
const DTYPE = { high: { encoder_model: 'q4', decoder_model_merged: 'q4' } };   // the big model is only practical quantised to 4 bit (~760 MB)

const fs = require('fs'); const path = require('path');
async function load(model, retry) {
  const id = MODELS[model] || MODELS.small;
  if (asr && loadedId === id) return;
  try { await loadOnce(id, model); } catch (e) {
    // an interrupted download leaves a broken file: delete the model folder and fetch it again once
    if (!retry && /protobuf|corrupt|invalid|parsing failed|unexpected end/i.test(String(e && e.message))) { try { fs.rmSync(path.join(workerData.cacheDir, id), { recursive: true, force: true }); } catch { /* ignore */ } asr = null; throw new Error('the speech model was damaged and has been removed; it will be downloaded again (' + String(e.message).slice(0, 80) + ')'); }
    throw e;
  }
}

async function loadOnce(id, model) {
  const tf = await import('@huggingface/transformers');
  tf.env.cacheDir = workerData.cacheDir; tf.env.allowLocalModels = true; if (process.env.ASSISTANT_STT_OFFLINE) tf.env.allowRemoteModels = false;
  const files = {};
  asr = await tf.pipeline('automatic-speech-recognition', id, { dtype: DTYPE[model] || 'q8', device: 'cpu', progress_callback: (p) => {
    if (p.status === 'progress' && p.total) { files[p.file] = p.loaded / p.total; const v = Object.values(files); parentPort.postMessage({ type: 'progress', value: v.reduce((a, b) => a + b, 0) / Math.max(1, v.length) }); }
  } });
  loadedId = id;
}

parentPort.on('message', async (m) => {
  try {
    if (m.type === 'load') { await load(m.model); parentPort.postMessage({ type: 'ready' }); return; }
    if (m.type === 'transcribe') {
      await load(m.model);
      const audio = m.audio instanceof Float32Array ? m.audio : new Float32Array(m.audio.buffer ? m.audio.buffer.slice(m.audio.byteOffset || 0, (m.audio.byteOffset || 0) + (m.audio.byteLength || m.audio.length)) : m.audio);
      let peak = 0; for (let i = 0; i < audio.length; i++) { const v = Math.abs(audio[i]); if (v > peak) peak = v; }
      if (peak > 0.004 && peak < 0.8) { const k = Math.min(25, 0.9 / peak); for (let i = 0; i < audio.length; i++) audio[i] *= k; }   // quiet microphones: bring the level up
      const opts = { task: 'transcribe', chunk_length_s: 30, num_beams: 4 }; if (m.language) opts.language = m.language;
      const out = await asr(audio, opts);
      parentPort.postMessage({ type: 'result', id: m.id, text: String(out.text || '').trim() });
    }
  } catch (e) { parentPort.postMessage({ type: 'error', id: m.id, error: String(e && e.message || e) }); }
});
