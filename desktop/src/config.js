'use strict';
const fs = require('fs');
const path = require('path');

const DEFAULTS = {
  provider: 'claude', apiKey: '', model: 'claude-haiku-5-5', smartPlan: true,
  hearthUrl: '', hearthKey: '', hearthModel: 'haiku',
  lang: 'nl', speak: true, loud: 1, voiceName: '', whisperModel: 'small',
  facts: '', names: '', aliases: '',
  hotkey: 'CommandOrControl+Alt+Space', accent: '',
  balance: null, spentSince: 0, spentTotal: 0, spentToday: 0, day: '',
  costCap: 0.3, confirmDestructive: true, overlay: true,
};

class Config {
  constructor(file) {
    this.file = file; this.data = { ...DEFAULTS };
    try { Object.assign(this.data, JSON.parse(fs.readFileSync(file, 'utf8'))); } catch { /* first run */ }
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
  facts() { return String(this.data.facts || '').split('\n').map((s) => s.trim()).filter(Boolean); }
  names() {
    const raw = String(this.data.names || '').split(/[,\n]/).map((s) => s.trim()).filter(Boolean);
    const fromFacts = this.facts().flatMap((f) => f.match(/\p{Lu}[\p{L}'-]{2,}/gu) || []);
    return [...new Set([...raw, ...fromFacts])].slice(0, 60);
  }
  aliases() { return String(this.data.aliases || '').split('\n').filter(Boolean); }
}

module.exports = { Config, DEFAULTS };
