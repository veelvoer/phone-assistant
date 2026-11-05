'use strict';
const { fork } = require('child_process');
const path = require('path');
const { EventEmitter } = require('events');
const models = require('./models');

/** Local speech recognition (Whisper via transformers.js). */
class Stt extends EventEmitter {
  constructor(config, cacheDir) { super(); this.config = config; this.cacheDir = cacheDir; this.worker = null; this.pending = new Map(); this.seq = 0; this.ready = false; }
  /** Make sure the model files are on disk, then start the recogniser process. */
  prepare() {
    if (this.preparing) return this.preparing;
    const model = this.config.get('whisperModel');
    this.preparing = models.ensure(model, this.cacheDir, (v) => this.emit('progress', v)).then(() => { this.preparing = null; this.emit('progress', 1); }, (e) => { this.preparing = null; this.emit('error', e); throw e; });
    return this.preparing;
  }

  /** The recogniser holds ~1.5 GB, so it only lives while it is being used: it is stopped again after a quiet spell. */
  touch() { clearTimeout(this.idle); this.idle = setTimeout(() => this.stop(), 90000); if (this.idle.unref) this.idle.unref(); }

  start() {
    this.touch();
    if (this.worker) return;
    if (!models.isReady(this.config.get('whisperModel'), this.cacheDir)) { this.prepare().then(() => this.start()).catch(() => {}); return; }
    const script = path.join(__dirname, 'proc.js');   // inside the asar: Electron-as-Node reads it, and finds node_modules next to it
    this.worker = fork(script, [], { execPath: process.execPath, env: { ...process.env, ELECTRON_RUN_AS_NODE: '1', ASSISTANT_STT_CACHE: this.cacheDir, ASSISTANT_STT_OFFLINE: '1' }, serialization: 'advanced', stdio: ['ignore', 'inherit', 'inherit', 'ipc'] });
    this.worker.on('message', (m) => {
      if (m.type === 'progress') this.emit('progress', m.value);
      else if (m.type === 'ready') { this.ready = true; this.emit('ready'); }
      else if (m.type === 'result' || m.type === 'error') { const p = this.pending.get(m.id); if (p) { this.pending.delete(m.id); m.type === 'result' ? p.res(m.text) : p.rej(new Error(m.error)); } }
    });
    this.worker.on('error', (e) => { this.emit('error', e); this.worker = null; this.ready = false; });
    const me = this.worker;
    this.worker.on('exit', () => { if (this.worker === me) { this.worker = null; this.ready = false; } for (const p of this.pending.values()) p.rej(new Error('speech engine stopped')); this.pending.clear(); });
    this.worker.send({ type: 'load', model: this.config.get('whisperModel') });
  }
  language() { const l = this.config.get('lang'); return l === 'nl' ? 'dutch' : l === 'en' ? 'english' : undefined; }
  async transcribe(float32) {
    if (!models.isReady(this.config.get('whisperModel'), this.cacheDir)) await this.prepare();
    this.start(); this.touch();
    return new Promise((res, rej) => { const id = ++this.seq; this.pending.set(id, { res, rej }); this.worker.send({ type: 'transcribe', id, audio: float32, model: this.config.get('whisperModel'), language: this.language() }); });
  }
  stop() { clearTimeout(this.idle); if (this.worker) { const w = this.worker; this.worker = null; this.ready = false; w.kill(); } }
}

// replace words that are almost a known name ("Yan" -> "Jan")
function lev(a, b) { const d = Array.from({ length: a.length + 1 }, (_, i) => [i, ...Array(b.length).fill(0)]); for (let j = 1; j <= b.length; j++) d[0][j] = j; for (let i = 1; i <= a.length; i++) for (let j = 1; j <= b.length; j++) d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1)); return d[a.length][b.length]; }
function fixNames(text, names) {
  const known = names.filter((n) => n.length >= 3);
  return text.replace(/[\p{L}'-]+/gu, (w) => { const lw = w.toLowerCase(); if (lw.length < 3) return w; for (const n of known) { const ln = n.toLowerCase(); if (ln === lw) return n; const max = ln.length >= 7 ? 2 : ln.length >= 4 ? 1 : 0; if (max && Math.abs(ln.length - lw.length) <= max && lev(ln, lw) <= max) return n; } return w; });
}
module.exports = { Stt, fixNames };
