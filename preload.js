const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('rc', {
  state: () => ipcRenderer.invoke('state'),
  pair: () => ipcRenderer.invoke('pair'),
  forget: (id) => ipcRenderer.invoke('forget', id),
  onState: (cb) => ipcRenderer.on('state', (_e, s) => cb(s)),
  onError: (cb) => ipcRenderer.on('err', (_e, m) => cb(m)),
});
