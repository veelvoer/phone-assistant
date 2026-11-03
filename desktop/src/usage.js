'use strict';
// $ per 1M tokens (input, output), first-party API rates
const PRICES = [['haiku-5', 0.10, 0.50], ['sonnet-5', 2, 10], ['opus-5-5', 4, 20], ['opus-5', 5, 25], ['fable', 10, 50], ['mythos', 10, 50], ['opus-4', 5, 25], ['sonnet-4', 3, 15], ['haiku-4', 1, 5]];
const price = (model) => { const p = PRICES.find((x) => String(model).includes(x[0])); return p ? [p[1], p[2]] : [0.10, 0.50]; };

class Usage {
  constructor(config) { this.config = config; this.begin(); }
  begin() { this.taskIn = 0; this.taskOut = 0; this.taskCost = 0; this.calls = 0; }
  record(model, usage, free = false) {
    if (!usage) return;
    const inp = usage.input_tokens || 0, out = usage.output_tokens || 0, cr = usage.cache_read_input_tokens || 0, cw = usage.cache_creation_input_tokens || 0;
    const [pi, po] = price(model);
    const cost = free ? 0 : (inp * pi + cr * pi * 0.1 + cw * pi * 1.25 + out * po) / 1e6;
    this.taskIn += inp + cr + cw; this.taskOut += out; this.taskCost += cost; this.calls++;
    if (cost <= 0) return;
    const day = new Date().toISOString().slice(0, 10); const c = this.config;
    const today = c.get('day') === day ? c.get('spentToday') : 0;
    c.set({ day, spentToday: today + cost, spentTotal: c.get('spentTotal') + cost, spentSince: c.get('spentSince') + cost });
  }
  summary() {
    const c = this.config, day = new Date().toISOString().slice(0, 10);
    return { today: c.get('day') === day ? c.get('spentToday') : 0, total: c.get('spentTotal'), left: c.get('balance') == null ? null : c.get('balance') - c.get('spentSince'), task: { in: this.taskIn, out: this.taskOut, cost: this.taskCost, calls: this.calls } };
  }
  setBalance(v) { this.config.set({ balance: v, spentSince: 0 }); }
}
const money = (x) => (x < 0.01 ? '$' + x.toFixed(4) : '$' + x.toFixed(2));
const k = (n) => (n >= 1000 ? (n / 1000).toFixed(1) + 'k' : String(n));
module.exports = { Usage, price, money, k };
