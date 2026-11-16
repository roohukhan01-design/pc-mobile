'use strict';
const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');
const QRCode = require('qrcode');
const { Hub } = require('./server');

let win, hub;
if (!app.requestSingleInstanceLock()) app.quit();
app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });
app.whenReady().then(() => {
  hub = new Hub(app.getPath('userData'));
  hub.on('error', (m) => win?.webContents.send('err', m));
  hub.start();
  win = new BrowserWindow({
    width: 1000, height: 680, minWidth: 820, minHeight: 560, backgroundColor: '#070b1e',
    title: 'Roohu Connect', autoHideMenuBar: true,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: true },
  });
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  hub.on('change', () => win.webContents.send('state', hub.state()));
  ipcMain.handle('state', () => hub.state());
  ipcMain.handle('forget', (_e, id) => hub.forget(String(id)));
  ipcMain.handle('pair', async () => {
    const p = hub.newPairing();
    const qr = await QRCode.toDataURL(JSON.stringify(p.payload), { width: 300, margin: 2 });
    return { pin: p.pin, exp: p.exp, qr };
  });
});
app.on('window-all-closed', () => { hub?.stop(); app.quit(); });
