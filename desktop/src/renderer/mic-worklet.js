// Runs on the audio thread, so no microphone buffers are dropped while the page is busy (dropouts made speech unrecognisable).
class Mic extends AudioWorkletProcessor {
  constructor() { super(); this.buf = new Float32Array(2048); this.n = 0; }
  process(inputs) {
    const ch = inputs[0] && inputs[0][0]; if (!ch) return true;
    for (let i = 0; i < ch.length; i++) { this.buf[this.n++] = ch[i]; if (this.n === this.buf.length) { const out = this.buf; this.port.postMessage(out, [out.buffer]); this.buf = new Float32Array(2048); this.n = 0; } }
    return true;
  }
}
registerProcessor('mic', Mic);
