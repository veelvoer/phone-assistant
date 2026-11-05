// Audio window: microphone capture with a voice-activity detector (finished utterances go to speech recognition as 16 kHz mono float32),
// plus playback of the assistant's voice and the little UI chimes.
let ctx = null, stream = null, proc = null, src = null, muted = false, active = false;
let floor = 0.004, speaking = false, onFrames = 0, silenceMs = 0, speechMs = 0, pre = [], buf = [], lastLevelAt = 0;
const TARGET = 16000;

function resample(chunk, from) { if (from === TARGET) return chunk; const ratio = from / TARGET, n = Math.floor(chunk.length / ratio), out = new Float32Array(n); for (let i = 0; i < n; i++) { const a = Math.floor(i * ratio), b = Math.min(chunk.length, Math.floor((i + 1) * ratio)); let s = 0; for (let j = a; j < b; j++) s += chunk[j]; out[i] = s / Math.max(1, b - a); } return out; }
function join(parts) { let len = 0; for (const p of parts) len += p.length; const o = new Float32Array(len); let off = 0; for (const p of parts) { o.set(p, off); off += p.length; } return o; }

async function start() {
  if (active) return; active = true; speaking = false; buf = []; pre = []; silenceMs = 0; speechMs = 0; onFrames = 0;
  try {
    // no echo cancellation: the microphone is muted while the assistant talks, and the filter makes soft speech vanish
    stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: false, noiseSuppression: true, autoGainControl: true } });
    try { ctx = new AudioContext({ sampleRate: TARGET }); } catch { ctx = new AudioContext(); }   // 16 kHz straight from the browser: proper low-pass resampling
    if (ctx.state === 'suspended') await ctx.resume();
    src = ctx.createMediaStreamSource(stream); proc = ctx.createScriptProcessor(2048, 1, 1);
    proc.onaudioprocess = (e) => {
      if (!active) return; const raw = e.inputBuffer.getChannelData(0); const chunk = resample(new Float32Array(raw), ctx.sampleRate); const ms = chunk.length / TARGET * 1000;
      let sum = 0; for (let i = 0; i < raw.length; i++) sum += raw[i] * raw[i]; const rms = Math.sqrt(sum / raw.length);
      const now = performance.now(); if (now - lastLevelAt > 80) { lastLevelAt = now; window.api.send('audio:level', muted ? 0 : Math.min(1, rms * 9)); }
      if (muted) { speaking = false; buf = []; pre = []; onFrames = 0; return; }
      if (!speaking) floor = Math.max(0.0008, Math.min(0.03, floor * 0.97 + rms * 0.03));
      const on = Math.max(0.006, floor * 3.0), off = Math.max(0.0035, floor * 1.8);   // hysteresis: soft word endings do not count as silence
      if (!speaking) {
        pre.push(chunk); if (pre.length > 6) pre.shift(); onFrames = rms > on ? onFrames + 1 : 0;
        if (onFrames >= 2) { speaking = true; buf = pre.slice(); silenceMs = 0; speechMs = 0; }
        return;
      }
      buf.push(chunk); if (rms > off) { silenceMs = 0; speechMs += ms; } else silenceMs += ms;
      const total = buf.reduce((a, c) => a + c.length, 0) / TARGET;
      if (silenceMs > 900 || total > 28) {
        speaking = false; onFrames = 0;
        if (speechMs > 350) { const u = join(buf); window.api.send('audio:utterance', u.buffer); }
        buf = []; pre = [];
      }
    };
    src.connect(proc); proc.connect(ctx.destination);
  } catch (e) { active = false; window.api.send('audio:error', e && e.message ? e.message : String(e)); }
}
function stop() { active = false; try { if (proc) proc.disconnect(); if (src) src.disconnect(); if (stream) stream.getTracks().forEach((t) => t.stop()); if (ctx) ctx.close(); } catch { /* ignore */ } proc = src = stream = ctx = null; buf = []; pre = []; }

// ---------- playback ----------
let pc = null, playing = null;
function playerCtx() { if (!pc || pc.state === 'closed') pc = new AudioContext(); return pc; }
function play(m) {
  try {
    const c = playerCtx(); if (c.state === 'suspended') c.resume();
    const pcm = new Int16Array(m.pcm.buffer, m.pcm.byteOffset, Math.floor(m.pcm.byteLength / 2)); const f = new Float32Array(pcm.length); for (let i = 0; i < pcm.length; i++) f[i] = pcm[i] / 32768;
    const b = c.createBuffer(1, f.length, m.rate); b.copyToChannel(f, 0);
    const s = c.createBufferSource(); s.buffer = b; const g = c.createGain(); g.gain.value = m.gain || 1;
    const lim = c.createDynamicsCompressor(); lim.threshold.value = -6; lim.knee.value = 6; lim.ratio.value = 12; lim.attack.value = 0.003; lim.release.value = 0.1;   // loud but not clipped
    s.connect(g); g.connect(lim); lim.connect(c.destination);
    s.onended = () => { if (playing && playing.id === m.id) playing = null; window.api.send('audio:played', m.id); };
    playing = { id: m.id, s }; s.start();
  } catch { window.api.send('audio:played', m.id); }
}
function stopPlay() { try { if (playing) { const p = playing; playing = null; p.s.onended = null; p.s.stop(); window.api.send('audio:played', p.id); } } catch { /* ignore */ } }

// ---------- short UI sounds ----------
let ac = null;
function chime(kind) {
  try {
    ac = ac || new AudioContext(); const t0 = ac.currentTime; const seq = { start: [[660, 0], [880, 0.09]], done: [[880, 0], [1175, 0.1]], stop: [[880, 0], [587, 0.1]], error: [[330, 0], [247, 0.12]] }[kind] || [[660, 0]];
    for (const [f, dt] of seq) { const o = ac.createOscillator(), g = ac.createGain(); o.type = 'sine'; o.frequency.value = f; g.gain.setValueAtTime(0.0001, t0 + dt); g.gain.exponentialRampToValueAtTime(0.18, t0 + dt + 0.015); g.gain.exponentialRampToValueAtTime(0.0001, t0 + dt + 0.18); o.connect(g); g.connect(ac.destination); o.start(t0 + dt); o.stop(t0 + dt + 0.2); }
  } catch { /* no audio */ }
}

window.api.on('audio:cmd', (m) => { if (m.cmd === 'start') start(); else if (m.cmd === 'stop') stop(); else if (m.cmd === 'mute') muted = !!m.on; else if (m.cmd === 'chime') chime(m.kind); else if (m.cmd === 'stopplay') stopPlay(); });
window.api.on('audio:play', play);
window.api.on('audio:chime', chime);
