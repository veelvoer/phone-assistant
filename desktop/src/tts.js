'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const { EventEmitter } = require('events');
const { run, which, spawn } = require('./platform/util');

const PIPER_VERSION = '2023.11.14-2';
const VOICE_BASE = 'https://huggingface.co/rhasspy/piper-voices/resolve/main/';
const VOICES = {
  'nl_BE-nathalie-medium': { label: 'Nathalie · Belgian Dutch, female', path: 'nl/nl_BE/nathalie/medium/', lang: 'nl' },
  'nl_BE-rdh-medium': { label: 'Rdh · Belgian Dutch, male', path: 'nl/nl_BE/rdh/medium/', lang: 'nl' },
  'nl_NL-pim-medium': { label: 'Pim · Dutch, male', path: 'nl/nl_NL/pim/medium/', lang: 'nl' },
  'nl_NL-ronnie-medium': { label: 'Ronnie · Dutch, male', path: 'nl/nl_NL/ronnie/medium/', lang: 'nl' },
  'nl_NL-mls-medium': { label: 'MLS · Dutch, female', path: 'nl/nl_NL/mls/medium/', lang: 'nl' },
  'en_US-lessac-medium': { label: 'Lessac · US English, female', path: 'en/en_US/lessac/medium/', lang: 'en' },
  'en_US-amy-medium': { label: 'Amy · US English, female', path: 'en/en_US/amy/medium/', lang: 'en' },
  'en_GB-alba-medium': { label: 'Alba · British English, female', path: 'en/en_GB/alba/medium/', lang: 'en' },
};
const DEFAULT_VOICE = { nl: 'nl_BE-nathalie-medium', en: 'en_US-lessac-medium' };

function pickAsset(platform = process.platform, arch = process.arch) {
  if (platform === 'win32') return { file: 'piper_windows_amd64.zip', bin: 'piper.exe' };
  if (platform === 'linux') return { file: arch === 'arm64' ? 'piper_linux_aarch64.tar.gz' : 'piper_linux_x86_64.tar.gz', bin: 'piper' };
  return null;
}

/** Natural offline speech with Piper (downloaded once into the app's folder), played by the app itself so volume and gain are ours.
 *  Falls back to whatever the system has (espeak-ng, speech-dispatcher, Windows SAPI). */
class Tts extends EventEmitter {
  constructor(config, dir) { super(); this.config = config; this.dir = dir; this.proc = null; this.busy = null; this.failed = ''; this.pcmCache = new Map(); }

  lang() { return this.config.get('lang') === 'en' ? 'en' : 'nl'; }
  voiceId() { const v = this.config.get('voiceName'); return v && VOICES[v] && VOICES[v].lang === this.lang() ? v : DEFAULT_VOICE[this.lang()]; }
  voices() { return Object.entries(VOICES).filter(([, v]) => v.lang === this.lang()).map(([id, v]) => ({ id, label: v.label })); }
  piperDir() { return path.join(this.dir, 'piper'); }
  piperBin() { const a = pickAsset(); return a ? path.join(this.piperDir(), 'piper', a.bin) : ''; }
  voiceFile(id) { return path.join(this.dir, 'voices', id + '.onnx'); }
  ready() { return !!this.piperBin() && fs.existsSync(this.piperBin()) && fs.existsSync(this.voiceFile(this.voiceId())) && fs.existsSync(this.voiceFile(this.voiceId()) + '.json'); }
  fallback() { return process.platform === 'win32' ? 'sapi' : which('espeak-ng') ? 'espeak-ng' : which('espeak') ? 'espeak' : which('spd-say') ? 'spd-say' : ''; }
  available() { return !!pickAsset() || !!this.fallback(); }
  describe() {
    if (this.ready()) return 'Piper · ' + (VOICES[this.voiceId()] || {}).label;
    if (this.busy) return 'Piper: downloading the voice…';
    if (this.failed && this.fallback()) return `${this.fallback()} (Piper download failed: ${this.failed})`;
    if (pickAsset()) return 'Piper: downloads on first use (about 90 MB)';
    return this.fallback() || 'none';
  }

  async download(url, dest, label, fetchImpl = fetch) {
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    const r = await fetchImpl(url, { redirect: 'follow' }); if (!r.ok) throw new Error(`${label}: HTTP ${r.status}`);
    const total = Number(r.headers.get('content-length')) || 0; let done = 0; const tmp = dest + '.part'; const out = fs.createWriteStream(tmp); const reader = r.body.getReader();
    await new Promise((resolve, reject) => { out.on('error', reject); out.on('finish', resolve); (async () => { for (;;) { const { value, done: d } = await reader.read(); if (d) break; done += value.length; out.write(Buffer.from(value)); if (total) this.emit('progress', { label, value: done / total }); } out.end(); })().catch(reject); });
    if (total && done !== total) { try { fs.unlinkSync(tmp); } catch { /* ignore */ } throw new Error(label + ': incomplete download'); }
    fs.renameSync(tmp, dest);
  }

  /** Download Piper and the chosen voice if they are missing. Resolves true when ready. */
  ensure() {
    if (this.ready()) return Promise.resolve(true);
    if (this.busy) return this.busy;
    const asset = pickAsset(); if (!asset) return Promise.resolve(false);
    this.busy = (async () => {
      try {
        this.failed = '';
        if (!fs.existsSync(this.piperBin())) {
          const arc = path.join(this.dir, asset.file);
          await this.download(`https://github.com/rhasspy/piper/releases/download/${PIPER_VERSION}/${asset.file}`, arc, 'Piper');
          fs.rmSync(this.piperDir(), { recursive: true, force: true }); fs.mkdirSync(this.piperDir(), { recursive: true });
          const x = asset.file.endsWith('.zip') ? await run('powershell.exe', ['-NoProfile', '-Command', `Expand-Archive -Force -LiteralPath '${arc}' -DestinationPath '${this.piperDir()}'`], { timeout: 120000 }) : await run('tar', ['-xzf', arc, '-C', this.piperDir()], { timeout: 120000 });
          try { fs.unlinkSync(arc); } catch { /* ignore */ }
          if (x.code !== 0 || !fs.existsSync(this.piperBin())) throw new Error('could not unpack Piper');
          if (process.platform !== 'win32') try { fs.chmodSync(this.piperBin(), 0o755); } catch { /* ignore */ }
        }
        const id = this.voiceId(), v = VOICES[id];
        for (const ext of ['.onnx', '.onnx.json']) if (!fs.existsSync(this.voiceFile(id) + (ext === '.onnx' ? '' : '.json'))) await this.download(VOICE_BASE + v.path + id + ext, this.voiceFile(id) + (ext === '.onnx' ? '' : '.json'), v.label);
        this.emit('ready');
        return true;
      } catch (e) { this.failed = String(e.message || e).slice(0, 120); this.emit('error', e); return false; } finally { this.busy = null; }
    })();
    return this.busy;
  }

  stop() { try { if (this.proc) this.proc.kill(); } catch { /* ignore */ } this.proc = null; }

  /** text -> { pcm: Buffer (s16le mono), rate } */
  synth(text) {
    const id = this.voiceId(); let rate = 22050;
    try { rate = JSON.parse(fs.readFileSync(this.voiceFile(id) + '.json', 'utf8')).audio.sample_rate || 22050; } catch { /* default */ }
    const speed = Math.max(0.6, Math.min(1.6, (Number(this.config.get('ttsSpeed')) || 100) / 100));
    return new Promise((resolve, reject) => {
      const env = { ...process.env, LD_LIBRARY_PATH: `${path.dirname(this.piperBin())}${process.env.LD_LIBRARY_PATH ? ':' + process.env.LD_LIBRARY_PATH : ''}` };
      const child = spawn(this.piperBin(), ['--model', this.voiceFile(id), '--output_raw', '--length_scale', String(1 / speed)], { cwd: path.dirname(this.piperBin()), env, stdio: ['pipe', 'pipe', 'ignore'], windowsHide: true });
      this.proc = child; const chunks = [];
      child.stdout.on('data', (d) => chunks.push(d)); child.on('error', reject);
      child.on('close', () => { if (this.proc === child) this.proc = null; const pcm = Buffer.concat(chunks); pcm.length > 2000 ? resolve({ pcm, rate }) : reject(new Error('Piper produced no audio')); });
      child.stdin.on('error', () => {}); child.stdin.end(text + '\n');
    });
  }

  clean(text) {
    const nl = this.lang() === 'nl';
    let t = String(text).replace(/[*_`#>]+/g, ' ').replace(/https?:\/\/\S+/g, nl ? ' een link ' : ' a link ').replace(/[\p{Extended_Pictographic}‍️]/gu, '');
    const m = nl ? [[/\bGB\b/g, 'gigabyte'], [/\bMB\b/g, 'megabyte'], [/km\/u/g, 'kilometer per uur'], [/°C/g, ' graden Celsius'], [/%/g, ' procent'], [/€\s?(\d+)/g, '$1 euro'], [/€/g, ' euro '], [/Wi-?Fi/gi, 'wifi'], [/&/g, ' en '], [/~/g, ' ongeveer ']]
      : [[/\bGB\b/g, 'gigabytes'], [/\bMB\b/g, 'megabytes'], [/km\/h/g, 'kilometers per hour'], [/°C/g, ' degrees Celsius'], [/%/g, ' percent'], [/€/g, ' euros '], [/&/g, ' and '], [/~/g, ' about ']];
    for (const [a, b] of m) t = t.replace(a, b);
    return t.replace(/\s+/g, ' ').trim().slice(0, 900);
  }

  /** Speak through Piper (play(pcm, rate) plays it), or the system voice when Piper is not available. */
  async speak(text, play) {
    this.stop();
    const t = this.clean(text); if (!t) return;
    if (await this.ensure().catch(() => false)) {
      try { const { pcm, rate } = await this.synth(t); await play(pcm, rate); return; } catch (e) { this.failed = String(e.message || e).slice(0, 100); this.emit('error', e); }
    }
    await this.speakSystem(t);
  }

  speakSystem(t) {
    const nl = this.lang() === 'nl';
    return new Promise((resolve) => {
      let child; const fb = this.fallback();
      if (fb === 'sapi') {
        const script = `Add-Type -AssemblyName System.Speech; $s = New-Object System.Speech.Synthesis.SpeechSynthesizer; $s.Volume = 100; try { $v = $s.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -like '${nl ? 'nl' : 'en'}*' } | Select-Object -First 1; if ($v) { $s.SelectVoice($v.VoiceInfo.Name) } } catch {}; $s.Speak([Console]::In.ReadToEnd())`;
        child = spawn('powershell', ['-NoProfile', '-Command', script], { stdio: ['pipe', 'ignore', 'ignore'], windowsHide: true }); child.stdin.end(t);
      } else if (fb === 'espeak-ng' || fb === 'espeak') child = spawn(which(fb), ['-v', nl ? 'nl' : 'en', '-s', '165', '-a', '190', t], { stdio: 'ignore' });
      else if (fb === 'spd-say') child = spawn('spd-say', ['-w', '-l', nl ? 'nl' : 'en', t], { stdio: 'ignore' });
      else return resolve();
      this.proc = child; child.on('exit', () => { if (this.proc === child) this.proc = null; resolve(); }); child.on('error', () => resolve());
    });
  }
}
module.exports = { Tts, VOICES, DEFAULT_VOICE, pickAsset };
