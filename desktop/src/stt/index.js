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
  /** Groq's free hosted Whisper large-v3 (with a vocabulary hint). Returns null when it cannot be used, so the local model takes over. */
  async cloud(float32) {
    const key = String(this.config.get('groqKey') || '').trim(); if (!key) return null;
    try {
      const n = float32.length, wav = Buffer.alloc(44 + n * 2);
      wav.write('RIFF', 0); wav.writeUInt32LE(36 + n * 2, 4); wav.write('WAVEfmt ', 8); wav.writeUInt32LE(16, 16); wav.writeUInt16LE(1, 20); wav.writeUInt16LE(1, 22); wav.writeUInt32LE(16000, 24); wav.writeUInt32LE(32000, 28); wav.writeUInt16LE(2, 32); wav.writeUInt16LE(16, 34); wav.write('data', 36); wav.writeUInt32LE(n * 2, 40);
      for (let i = 0; i < n; i++) wav.writeInt16LE(Math.round(Math.max(-1, Math.min(1, float32[i])) * 32767), 44 + i * 2);
      const form = new FormData(); form.append('file', new Blob([wav], { type: 'audio/wav' }), 'speech.wav'); form.append('model', 'whisper-large-v3'); form.append('temperature', '0'); form.append('response_format', 'json');
      const l = this.config.get('lang'); if (l === 'nl' || l === 'en') form.append('language', l);
      const vocab = [...(this.config.names ? this.config.names() : []), ...TERMS].slice(0, 60).join(', ');
      form.append('prompt', (l === 'en' ? 'Computer commands. ' : 'Computeropdrachten in het Nederlands, met Engelse woorden. ') + vocab + '.');
      const r = await fetch('https://api.groq.com/openai/v1/audio/transcriptions', { method: 'POST', headers: { Authorization: 'Bearer ' + key }, body: form, signal: AbortSignal.timeout(20000) });
      if (!r.ok) { this.emit('error', new Error('online speech recognition: HTTP ' + r.status + (r.status === 429 ? ' (free limit reached, using the local model)' : ''))); return null; }
      return String((await r.json()).text || '').trim();
    } catch (e) { this.emit('error', new Error('online speech recognition: ' + e.message)); return null; }
  }
  async transcribe(float32) {
    const online = await this.cloud(float32); if (online !== null) return online;
    if (!models.isReady(this.config.get('whisperModel'), this.cacheDir)) await this.prepare();
    this.start(); this.touch();
    return new Promise((res, rej) => { const id = ++this.seq; this.pending.set(id, { res, rej }); this.worker.send({ type: 'transcribe', id, audio: float32, model: this.config.get('whisperModel'), language: process.env.STT_LANG === 'auto' ? undefined : this.language() }); });
  }
  stop() { clearTimeout(this.idle); if (this.worker) { const w = this.worker; this.worker = null; this.ready = false; w.kill(); } }
}

// replace words that are almost a known name ("Yan" -> "Jan")
function lev(a, b) { const d = Array.from({ length: a.length + 1 }, (_, i) => [i, ...Array(b.length).fill(0)]); for (let j = 1; j <= b.length; j++) d[0][j] = j; for (let i = 1; i <= a.length; i++) for (let j = 1; j <= b.length; j++) d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1)); return d[a.length][b.length]; }
// English words and app names that Dutch-mode recognition turns into nonsense
const TERMS = ['Firefox', 'Chrome', 'Chromium', 'Spotify', 'Discord', 'Telegram', 'WhatsApp', 'Snapchat', 'Instagram', 'YouTube', 'Gmail', 'GitHub', 'Steam', 'Terminal', 'Workspace', 'Screenshot', 'Browser', 'Download', 'Downloads', 'Settings', 'Bluetooth', 'WiFi', 'Hyprland', 'Linux', 'Windows', 'Visual Studio Code', 'Claude', 'Smartschool', 'Google', 'Netflix', 'Twitch', 'Reddit', 'Wikipedia', 'Outlook', 'Notepad', 'Calculator', 'Slack', 'Zoom', 'Teams', 'Tab', 'Desktop', 'Folder', 'Password', 'Update', 'Shortcut', 'Dark mode', 'Fullscreen'];
const ALIASES = [[/\bfire\W*works?\b/gi, 'Firefox'], [/\bfire\W*fox\b/gi, 'Firefox'], [/\bwork\W*space\b/gi, 'workspace'], [/\bscreen\W*shot\b/gi, 'screenshot'], [/\bspot\W*if(y|ie)\b/gi, 'Spotify'], [/\bwhats\W*app\b/gi, 'WhatsApp'], [/\bsnap\W*chat\b/gi, 'Snapchat'], [/\bgit\W*hub\b/gi, 'GitHub'], [/\bhyper\W*land\b/gi, 'Hyprland'], [/\byou\W*tube\b/gi, 'YouTube'], [/\bwi\W*fi\b/gi, 'wifi']];
function fixNames(text, names) {
  const known = [...names, ...TERMS.filter((n) => n.length >= 7)].filter((n) => n.length >= 3);   // short built-in words only match exactly (aliases below)
  let t = text; for (const [re, to] of ALIASES) t = t.replace(re, to);
  return t.replace(/[\p{L}'-]+/gu, (w) => { const lw = w.toLowerCase(); if (lw.length < 3) return w; for (const n of known) { const ln = n.toLowerCase(); if (ln === lw) return n; const max = ln.length >= 7 ? 2 : ln.length >= 4 ? 1 : 0; if (max && Math.abs(ln.length - lw.length) <= max && lev(ln, lw) <= max) return n; } return w; });
}
module.exports = { Stt, fixNames };
