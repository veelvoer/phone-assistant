// Microphone capture with a simple voice-activity detector; sends finished utterances (16 kHz mono float32) to the main process.
let ctx = null, stream = null, proc = null, src = null, muted = false, active = false;
let floor = 0.004, speaking = false, speechFrames = 0, silenceMs = 0, pre = [], buf = [], lastLevelAt = 0;
const TARGET = 16000;
function resample(chunk, from) { if (from === TARGET) return chunk; const ratio = from / TARGET, n = Math.floor(chunk.length / ratio), out = new Float32Array(n); for (let i = 0; i < n; i++) { const a = Math.floor(i * ratio), b = Math.min(chunk.length, Math.floor((i + 1) * ratio)); let s = 0; for (let j = a; j < b; j++) s += chunk[j]; out[i] = s / Math.max(1, b - a); } return out; }
function join(parts) { let len = 0; for (const p of parts) len += p.length; const o = new Float32Array(len); let off = 0; for (const p of parts) { o.set(p, off); off += p.length; } return o; }
async function start() {
  if (active) return; active = true; speaking = false; buf = []; pre = []; silenceMs = 0;
  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true } });
    ctx = new AudioContext(); src = ctx.createMediaStreamSource(stream); proc = ctx.createScriptProcessor(4096, 1, 1);
    proc.onaudioprocess = (e) => {
      if (!active) return; const raw = e.inputBuffer.getChannelData(0); const chunk = resample(new Float32Array(raw), ctx.sampleRate); const ms = chunk.length / TARGET * 1000;
      let sum = 0; for (let i = 0; i < raw.length; i++) sum += raw[i] * raw[i]; const rms = Math.sqrt(sum / raw.length);
      const now = performance.now(); if (now - lastLevelAt > 80) { lastLevelAt = now; window.api.send('audio:level', muted ? 0 : Math.min(1, rms * 9)); }
      if (muted) { speaking = false; buf = []; pre = []; return; }
      if (!speaking) floor = floor * 0.98 + rms * 0.02;
      const thr = Math.max(0.012, floor * 3.2); const voiced = rms > thr;
      if (!speaking) { pre.push(chunk); if (pre.length > 4) pre.shift(); speechFrames = voiced ? speechFrames + 1 : 0; if (speechFrames >= 2) { speaking = true; buf = pre.slice(); silenceMs = 0; } return; }
      buf.push(chunk); silenceMs = voiced ? 0 : silenceMs + ms; const total = buf.reduce((a, c) => a + c.length, 0) / TARGET;
      if (silenceMs > 1100 || total > 28) { speaking = false; speechFrames = 0; if (total > 0.5 + 1.1) { const u = join(buf); window.api.send('audio:utterance', u.buffer); } buf = []; pre = []; }
    };
    src.connect(proc); proc.connect(ctx.destination);
  } catch (e) { active = false; window.api.send('audio:error', e && e.message ? e.message : String(e)); }
}
function stop() { active = false; try { if (proc) proc.disconnect(); if (src) src.disconnect(); if (stream) stream.getTracks().forEach((t) => t.stop()); if (ctx) ctx.close(); } catch { /* ignore */ } proc = src = stream = ctx = null; buf = []; pre = []; }
window.api.on('audio:cmd', (m) => { if (m.cmd === 'start') start(); else if (m.cmd === 'stop') stop(); else if (m.cmd === 'mute') muted = !!m.on; });

// short UI sounds (WebAudio, no files)
let ac = null;
function chime(kind) {
  try {
    ac = ac || new AudioContext(); const t0 = ac.currentTime; const seq = { start: [[660, 0], [880, 0.09]], done: [[880, 0], [1175, 0.1]], stop: [[880, 0], [587, 0.1]], error: [[330, 0], [247, 0.12]] }[kind] || [[660, 0]];
    for (const [f, dt] of seq) { const o = ac.createOscillator(), g = ac.createGain(); o.type = 'sine'; o.frequency.value = f; g.gain.setValueAtTime(0.0001, t0 + dt); g.gain.exponentialRampToValueAtTime(0.18, t0 + dt + 0.015); g.gain.exponentialRampToValueAtTime(0.0001, t0 + dt + 0.18); o.connect(g); g.connect(ac.destination); o.start(t0 + dt); o.stop(t0 + dt + 0.2); }
  } catch { /* no audio */ }
}
window.api.on('audio:chime', chime);
