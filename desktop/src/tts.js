'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const { run, which, spawn } = require('./platform/util');

/** Text-to-speech through what the system has: Windows SAPI, piper, espeak-ng, speech-dispatcher. */
class Tts {
  constructor(config) { this.config = config; this.proc = null; }
  available() { return process.platform === 'win32' || !!(this.piper() || which('espeak-ng') || which('espeak') || which('spd-say')); }
  describe() { if (process.platform === 'win32') return 'Windows speech (SAPI)'; if (this.piper()) return 'Piper'; if (which('espeak-ng')) return 'espeak-ng (robotic: install piper for a natural voice)'; if (which('spd-say')) return 'speech-dispatcher'; return 'none (install piper or espeak-ng)'; }
  piper() {
    if (!which('piper')) return null;
    const dirs = [path.join(os.homedir(), '.local/share/piper'), path.join(os.homedir(), '.local/share/piper-voices'), '/usr/share/piper-voices'];
    const lang = this.config.get('lang') === 'en' ? 'en' : 'nl'; let any = null;
    for (const d of dirs) { try { for (const f of fs.readdirSync(d)) { if (!f.endsWith('.onnx')) continue; if (f.startsWith(lang)) return path.join(d, f); any = any || path.join(d, f); } } catch { /* next */ } }
    return any;
  }
  stop() { try { if (this.proc) this.proc.kill(); } catch { /* ignore */ } this.proc = null; }

  speak(text) {
    this.stop();
    const t = String(text).replace(/[*_`#>]+/g, ' ').replace(/\s+/g, ' ').trim(); if (!t) return Promise.resolve();
    const nl = this.config.get('lang') !== 'en';
    return new Promise((resolve) => {
      let child;
      if (process.platform === 'win32') {
        const script = `Add-Type -AssemblyName System.Speech; $s = New-Object System.Speech.Synthesis.SpeechSynthesizer; $s.Volume = 100; try { $v = $s.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -like '${nl ? 'nl' : 'en'}*' } | Select-Object -First 1; if ($v) { $s.SelectVoice($v.VoiceInfo.Name) } } catch {}; $s.Speak([Console]::In.ReadToEnd())`;
        child = spawn('powershell', ['-NoProfile', '-Command', script], { stdio: ['pipe', 'ignore', 'ignore'], windowsHide: true }); child.stdin.end(t);
      } else if (this.piper() && which('paplay') || (this.piper() && which('aplay') )) {
        const player = which('paplay') ? ['paplay', '--raw', '--rate=22050', '--format=s16le', '--channels=1'] : ['aplay', '-q', '-r', '22050', '-f', 'S16_LE', '-t', 'raw'];
        child = spawn('sh', ['-c', `${JSON.stringify(which('piper'))} --model ${JSON.stringify(this.piper())} --output-raw | ${player.map((x) => JSON.stringify(x)).join(' ')}`], { stdio: ['pipe', 'ignore', 'ignore'] }); child.stdin.end(t);
      } else if (which('espeak-ng') || which('espeak')) {
        child = spawn(which('espeak-ng') || which('espeak'), ['-v', nl ? 'nl' : 'en', '-s', '165', '-a', '190', t], { stdio: 'ignore' });
      } else if (which('spd-say')) {
        child = spawn('spd-say', ['-w', '-l', nl ? 'nl' : 'en', t], { stdio: 'ignore' });
      } else return resolve();
      this.proc = child; child.on('exit', () => { if (this.proc === child) this.proc = null; resolve(); }); child.on('error', () => resolve());
    });
  }
}
module.exports = { Tts };
