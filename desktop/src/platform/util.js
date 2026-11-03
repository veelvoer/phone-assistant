'use strict';
const { execFile, spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

/** Run a program, resolve {code, stdout, stderr}. Never rejects. */
function run(cmd, args = [], opts = {}) {
  return new Promise((resolve) => {
    let done = false;
    const child = execFile(cmd, args, { encoding: opts.binary ? 'buffer' : 'utf8', maxBuffer: 64 * 1024 * 1024, timeout: opts.timeout || 30000, cwd: opts.cwd, env: opts.env || process.env, windowsHide: true }, (err, stdout, stderr) => {
      if (done) return; done = true;
      resolve({ code: err ? (typeof err.code === 'number' ? err.code : 1) : 0, stdout, stderr: String(stderr || ''), error: err && !err.code ? String(err.message || err) : (err && err.killed ? 'timed out' : '') });
    });
    if (opts.input != null) { child.stdin.on('error', () => {}); child.stdin.end(opts.input); }
  });
}

const whichCache = new Map();
function which(bin) {
  if (whichCache.has(bin)) return whichCache.get(bin);
  let found = '';
  for (const d of String(process.env.PATH || '').split(path.delimiter)) {
    for (const ext of process.platform === 'win32' ? ['.exe', '.cmd', '.bat', ''] : ['']) {
      const p = path.join(d, bin + ext);
      try { fs.accessSync(p, fs.constants.X_OK); found = p; break; } catch { /* next */ }
    }
    if (found) break;
  }
  whichCache.set(bin, found);
  return found;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

module.exports = { run, which, sleep, spawn };
