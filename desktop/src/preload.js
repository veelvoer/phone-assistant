'use strict';
const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('api', {
  invoke: (ch, ...a) => ipcRenderer.invoke(ch, ...a),
  send: (ch, ...a) => ipcRenderer.send(ch, ...a),
  on: (ch, f) => { const h = (_e, ...a) => f(...a); ipcRenderer.on(ch, h); return () => ipcRenderer.removeListener(ch, h); },
});
