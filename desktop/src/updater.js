'use strict';
// One-click self update from GitHub releases (tag "desktop-vX.Y.Z"): download, swap, restart.
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawn } = require('child_process');

const vnum = (v) => String(v).replace(/^\D+/, '').split('.').map((x) => parseInt(x, 10) || 0);
const newer = (a, b) => { const x = vnum(a), y = vnum(b); for (let i = 0; i < 3; i++) { if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) > (y[i] || 0); } return false; };

/** newest published desktop release */
function pickRelease(list) { return (list || []).filter((x) => /^desktop-v\d/.test(x.tag_name) && !x.draft).sort((a, b) => (newer(a.tag_name, b.tag_name) ? -1 : newer(b.tag_name, a.tag_name) ? 1 : 0))[0] || null; }

/** which file to download for this install. kind: how this copy is installed. */
function pickAsset(assets, platform, env = process.env) {
  const names = (assets || []).map((a) => a.name);
  const find = (re) => (assets || []).find((a) => re.test(a.name));
  if (platform === 'win32') {
    if (env.PORTABLE_EXECUTABLE_FILE) return { asset: find(/^Assistant\.\d[\d.]*\.exe$/), kind: 'win-portable' };
    return { asset: find(/Setup.*\.exe$/), kind: 'win-setup' };
  }
  if (platform === 'linux') return { asset: find(/\.AppImage$/), kind: env.APPIMAGE ? 'linux-appimage' : 'linux-dir' };
  return { asset: null, kind: 'unsupported', names };
}

async function download(url, dest, onProgress, fetchImpl = fetch) {
  const r = await fetchImpl(url, { redirect: 'follow' }); if (!r.ok) throw new Error('download failed: HTTP ' + r.status);
  const total = Number(r.headers.get('content-length')) || 0; let done = 0; const out = fs.createWriteStream(dest);
  const reader = r.body.getReader();
  await new Promise((resolve, reject) => {
    out.on('error', reject); out.on('finish', resolve);
    (async () => { for (;;) { const { value, done: d } = await reader.read(); if (d) break; done += value.length; out.write(Buffer.from(value)); if (onProgress && total) onProgress(done / total); } out.end(); })().catch(reject);
  });
  if (total && done !== total) throw new Error('download incomplete');
}

const q = (s) => `'${String(s).replace(/'/g, `'\\''`)}'`;   // POSIX single-quote
const wq = (s) => `"${String(s).replace(/"/g, '""')}"`;

/** shell script (POSIX) that waits for pid, swaps the files and starts the new version */
function linuxScripts({ kind, pid, newFile, target, installDir, extractedDir }) {
  const launchEnv = 'env -u ELECTRON_RUN_AS_NODE';
  if (kind === 'linux-appimage') return `while kill -0 ${pid} 2>/dev/null; do sleep 0.3; done; mv -f ${q(newFile)} ${q(target)} && chmod +x ${q(target)}; (${launchEnv} ${q(target)} --no-sandbox >/dev/null 2>&1 &)`;
  return `while kill -0 ${pid} 2>/dev/null; do sleep 0.3; done; rm -rf ${q(installDir + '.old')}; mv ${q(installDir)} ${q(installDir + '.old')} && mv ${q(extractedDir)} ${q(installDir)} && rm -rf ${q(installDir + '.old')}; (${launchEnv} ${q(path.posix.join(installDir, path.posix.basename(target)))} --no-sandbox >/dev/null 2>&1 &)`;
}

function windowsCommand({ kind, newFile, target }) {
  if (kind === 'win-portable') return `ping -n 4 127.0.0.1 >nul & move /y ${wq(newFile)} ${wq(target)} & start "" ${wq(target)}`;
  return `${wq(newFile)} /S & start "" ${wq(target)}`;   // NSIS silent install, then start the installed app
}

/** Download + install + restart. Returns when the restart has been scheduled (caller quits the app). */
async function install({ rel, app, onState, fetchImpl = fetch }) {
  const { asset, kind } = pickAsset(rel.assets, process.platform);
  if (!asset) throw new Error('no download for this system in the release');
  if (!app.isPackaged) throw new Error('running from source: update with git pull');
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'assistant-update-'));
  const file = path.join(tmp, asset.name);
  onState({ phase: 'downloading', pct: 0 });
  await download(asset.browser_download_url, file, (p) => onState({ phase: 'downloading', pct: p }), fetchImpl);
  onState({ phase: 'installing', pct: 1 });
  if (process.platform === 'win32') {
    const target = kind === 'win-portable' ? process.env.PORTABLE_EXECUTABLE_FILE : process.execPath;
    spawn('cmd.exe', ['/c', windowsCommand({ kind, newFile: file, target })], { detached: true, stdio: 'ignore', windowsHide: true }).unref(); return;
  }
  fs.chmodSync(file, 0o755);
  let script;
  if (kind === 'linux-appimage') {
    const target = process.env.APPIMAGE; const next = target + '.new';
    try { fs.copyFileSync(file, next); fs.chmodSync(next, 0o755); } catch { throw new Error('cannot write next to ' + target); }
    script = linuxScripts({ kind, pid: process.pid, newFile: next, target });
  } else {
    const installDir = path.dirname(process.execPath);
    try { fs.accessSync(path.dirname(installDir), fs.constants.W_OK); fs.accessSync(installDir, fs.constants.W_OK); } catch { throw new Error('the install folder is not writable (' + installDir + ')'); }
    onState({ phase: 'installing', pct: 1 });
    const ex = await new Promise((res) => { const c = spawn(file, ['--appimage-extract'], { cwd: tmp, stdio: 'ignore' }); c.on('exit', (code) => res(code)); c.on('error', () => res(1)); });
    const extractedDir = path.join(tmp, 'squashfs-root');
    if (ex !== 0 || !fs.existsSync(path.join(extractedDir, path.basename(process.execPath)))) throw new Error('could not unpack the update');
    script = linuxScripts({ kind, pid: process.pid, installDir, extractedDir, target: process.execPath });
  }
  spawn('sh', ['-c', script], { detached: true, stdio: 'ignore' }).unref();
}

module.exports = { newer, pickRelease, pickAsset, download, linuxScripts, windowsCommand, install };
