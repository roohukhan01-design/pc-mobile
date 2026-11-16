'use strict';
const https = require('https');
const crypto = require('crypto');
const os = require('os');
const fs = require('fs');
const path = require('path');
const EventEmitter = require('events');
const { WebSocketServer } = require('ws');
const selfsigned = require('selfsigned');
const { Bonjour } = require('bonjour-service');

const PORT = Number(process.env.ROOHU_PORT) || 47890;
const TOKEN_DAYS = 30;
const sha = (s) => crypto.createHash('sha256').update(s).digest('hex');

class Hub extends EventEmitter {
  constructor(dir) {
    super();
    this.dir = dir;
    this.file = path.join(dir, 'trusted.json');
    this.db = this.readJson(this.file, { pcId: crypto.randomUUID(), devices: [] });
    const cf = path.join(dir, 'cert.json');
    let c = this.readJson(cf, null);
    if (!c) {
      const g = selfsigned.generate([{ name: 'commonName', value: 'Roohu Connect' }], { days: 3650, keySize: 2048, algorithm: 'sha256' });
      c = { key: g.private, cert: g.cert };
      fs.writeFileSync(cf, JSON.stringify(c), { mode: 0o600 });
    }
    this.key = c.key; this.cert = c.cert;
    this.fp = new crypto.X509Certificate(c.cert).fingerprint256.replace(/:/g, '').toLowerCase();
    this.pin = null; this.pinExp = 0; this.fails = 0;
    this.online = new Map();
    this.save();
  }
  readJson(f, d) { try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return d; } }
  save() { fs.writeFileSync(this.file, JSON.stringify(this.db), { mode: 0o600 }); }

  ips() {
    const rank = (a) => (a.startsWith('192.168.') ? 0 : a.startsWith('10.') ? 1 : /^172\.(1[6-9]|2\d|3[01])\./.test(a) ? 2 : 3);
    const out = [];
    const virt = /vethernet|virtualbox|vmware|vbox|wsl|docker|hyper-v|loopback|tailscale|zerotier/i;
    for (const [name, list] of Object.entries(os.networkInterfaces()))
      if (!virt.test(name))
        for (const i of list || []) if (i.family === 'IPv4' && !i.internal && !i.address.startsWith('169.254.')) out.push(i.address);
    return out.sort((a, b) => rank(a) - rank(b));
  }

  start() {
    const server = https.createServer({ key: this.key, cert: this.cert });
    this.server = server;
    server.on('error', (e) => this.emit('error', e.message));
    this.wss = new WebSocketServer({ server, path: '/ws', maxPayload: 1 << 20 });
    this.wss.on('connection', (ws) => this.onConn(ws));
    server.listen(PORT, '0.0.0.0');
    try {
      this.bonjour = new Bonjour();
      this.bonjour.publish({ name: os.hostname(), type: 'roohu', port: PORT, txt: { id: this.db.pcId } });
    } catch (e) { this.emit('error', 'Discovery failed: ' + e.message); }
  }
  stop() { try { this.bonjour?.unpublishAll(); this.bonjour?.destroy(); this.wss?.close(); this.server?.close(); } catch {} }

  newPairing() {
    this.pin = String(crypto.randomInt(0, 1000000)).padStart(6, '0');
    this.pinExp = Date.now() + 120000;
    this.fails = 0;
    return { pin: this.pin, exp: this.pinExp, payload: { v: 1, h: this.ips(), p: PORT, fp: this.fp, pin: this.pin, n: os.hostname(), id: this.db.pcId } };
  }

  onConn(ws) {
    this.pending = this.pending || new Set();
    if (this.pending.size >= 8) return ws.close();
    this.pending.add(ws);
    ws.on('close', () => this.pending.delete(ws));
    const nonce = crypto.randomBytes(16).toString('hex');
    let authed = false, devId = null;
    const err = (msg) => { try { ws.send(JSON.stringify({ t: 'error', msg })); } catch {} setTimeout(() => ws.close(), 100); };
    const timer = setTimeout(() => { if (!authed) ws.close(); }, 10000);
    ws.send(JSON.stringify({ t: 'hello', nonce, pcName: os.hostname(), pcId: this.db.pcId }));

    ws.on('message', (raw) => {
      let m; try { m = JSON.parse(raw.toString()); } catch { return ws.close(); }
      if (!m || typeof m !== 'object') return ws.close();
      if (authed) return; // M1: no other messages yet
      if (typeof m.id !== 'string' || m.id.length > 64) return ws.close();

      if (m.t === 'pair') {
        if (!this.pin || Date.now() > this.pinExp) return err('No pairing code is active. Click "Pair new phone" on the PC.');
        const expect = crypto.createHmac('sha256', this.pin).update(nonce + this.fp).digest('hex');
        const ok = typeof m.proof === 'string' && m.proof.length === expect.length &&
          crypto.timingSafeEqual(Buffer.from(m.proof), Buffer.from(expect));
        if (!ok) { if (++this.fails >= 5) this.pin = null; return err('Wrong code.'); }
        this.pin = null; // one use only
        const token = crypto.randomBytes(32).toString('hex');
        const rec = { id: m.id, name: String(m.name || 'Phone').slice(0, 40), tokenHash: sha(token), expires: Date.now() + TOKEN_DAYS * 864e5, lastSeen: Date.now() };
        this.db.devices = this.db.devices.filter((d) => d.id !== m.id).concat(rec);
        this.save();
        ws.send(JSON.stringify({ t: 'paired', token, pcName: os.hostname(), pcId: this.db.pcId }));
        authed = true; devId = m.id;
      } else if (m.t === 'auth') {
        const d = this.db.devices.find((x) => x.id === m.id);
        const good = d && typeof m.token === 'string' && Date.now() < d.expires &&
          crypto.timingSafeEqual(Buffer.from(sha(m.token)), Buffer.from(d.tokenHash));
        if (!good) return err('Not trusted or session expired. Pair again.');
        d.lastSeen = Date.now(); d.expires = Date.now() + TOKEN_DAYS * 864e5; this.save();
        ws.send(JSON.stringify({ t: 'ok' }));
        authed = true; devId = m.id;
      } else return ws.close();

      if (authed) { clearTimeout(timer); this.online.get(devId)?.close(); this.online.set(devId, ws); this.emit('change'); }
    });
    ws.on('close', () => { clearTimeout(timer); if (devId && this.online.get(devId) === ws) { this.online.delete(devId); this.emit('change'); } });
    ws.on('error', () => {});
  }

  forget(id) {
    this.db.devices = this.db.devices.filter((d) => d.id !== id);
    this.save(); this.online.get(id)?.close(); this.online.delete(id); this.emit('change');
  }

  state() {
    return {
      pcName: os.hostname(), port: PORT, ips: this.ips(),
      devices: this.db.devices.map((d) => ({ id: d.id, name: d.name, online: this.online.has(d.id), lastSeen: d.lastSeen })),
    };
  }
}
module.exports = { Hub };
