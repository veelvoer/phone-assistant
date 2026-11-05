'use strict';
const fs = require('fs');
const path = require('path');

const DEFAULTS = {
  provider: 'claude', apiKey: '', groqKey: '', model: 'claude-haiku-5-5', smartPlan: true,
  hearthUrl: '', hearthKey: '', hearthModel: 'haiku',
  lang: 'nl', speak: true, loud: 1, voiceName: '', ttsVolume: 100, ttsSpeed: 100, whisperModel: 'medium', sttMigrated: 0,
  facts: '', lessons: '', names: '', aliases: '',
  hotkey: 'Ctrl+Alt+Space', hotkeyBound: false, accent: '',
  balance: null, spentSince: 0, spentTotal: 0, spentToday: 0, day: '',
  costCap: 0.3, confirmDestructive: true, overlay: true,
  assistantName: 'Assistant', tone: 'friendly', replyLength: 'short', sounds: true,
  quietOn: false, quietFrom: '22:00', quietTo: '08:00', dailyLimit: 0,
  // overlay look
  glowOn: true, glowWidth: 2, glowIntensity: 80, glowSpeed: 2, glowMode: 'full', glowSoft: 50, glowRadius: 46, accent2: '',
  cardOn: true, cardPos: 'bottom', cardSize: 'normal', cardOpacity: 92, cardRadius: 28, cardStyle: 'solid', cardText: 17, cardOffset: 36, showTitle: true, anim: 'slide',
  showBars: true, waveStyle: 'bars', waveCount: 4, showProgress: true, autoHide: 5,
};

class Config {
  constructor(file) {
    this.file = file; this.data = { ...DEFAULTS };
    let fresh = true; try { Object.assign(this.data, JSON.parse(fs.readFileSync(file, 'utf8'))); fresh = false; } catch { /* first run */ }
    // speech recognition: the old default (Small) was too error-prone; use the best model the CPU can run comfortably
    if (fresh || (this.data.sttMigrated || 0) < 3) { this.data.whisperModel = require('os').cpus().length >= 6 ? 'high' : 'medium'; this.data.sttMigrated = 3; }
    this.listeners = new Set();
  }
  get(k) { return this.data[k]; }
  all() { return { ...this.data }; }
  set(patch) {
    Object.assign(this.data, patch);
    try { fs.mkdirSync(path.dirname(this.file), { recursive: true }); fs.writeFileSync(this.file + '.tmp', JSON.stringify(this.data, null, 1), { mode: 0o600 }); fs.renameSync(this.file + '.tmp', this.file); } catch { /* best effort */ }
    for (const l of this.listeners) l(patch);
  }
  onChange(f) { this.listeners.add(f); }
  lessons() { return String(this.data.lessons || '').split('\n').map((s) => s.trim()).filter(Boolean); }
  facts() { return String(this.data.facts || '').split('\n').map((s) => s.trim()).filter(Boolean); }
  names() {
    const raw = String(this.data.names || '').split(/[,\n]/).map((s) => s.trim()).filter(Boolean);
    const fromFacts = this.facts().flatMap((f) => f.match(/\p{Lu}[\p{L}'-]{2,}/gu) || []);
    return [...new Set([...raw, ...fromFacts])].slice(0, 60);
  }
  aliases() { return String(this.data.aliases || '').split('\n').filter(Boolean); }
}

module.exports = { Config, DEFAULTS };
