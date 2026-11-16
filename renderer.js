const $ = (id) => document.getElementById(id);
let exp = 0;
function render(s) {
  $('pcname').textContent = s.pcName;
  $('addr').textContent = 'Address: ' + (s.ips[0] || 'no network') + ':' + s.port;
  const ul = $('devs'); ul.replaceChildren();
  if (!s.devices.length) { const li = document.createElement('li'); li.textContent = 'No phone paired yet.'; ul.append(li); }
  for (const d of s.devices) {
    const li = document.createElement('li');
    const dot = document.createElement('span'); dot.className = 'dot' + (d.online ? ' on' : '');
    const name = document.createElement('span'); name.className = 'grow'; name.textContent = d.name + (d.online ? ' · online' : ' · offline');
    const b = document.createElement('button'); b.className = 'small'; b.textContent = 'Forget';
    b.onclick = () => window.rc.forget(d.id);
    li.append(dot, name, b); ul.append(li);
  }
}
$('pairBtn').onclick = async () => {
  const r = await window.rc.pair();
  $('qr').src = r.qr; $('pin').textContent = r.pin; $('pairBox').hidden = false; exp = r.exp;
};
setInterval(() => {
  if (!exp) return;
  const s = Math.round((exp - Date.now()) / 1000);
  $('left').textContent = s > 0 ? 'Code expires in ' + s + ' s' : 'Code expired. Click "Pair new phone" again.';
  if (s <= 0) { $('pairBox').hidden = true; exp = 0; }
}, 1000);
window.rc.onState(render);
window.rc.onError((m) => { $('err').hidden = false; $('err').textContent = m; });
window.rc.state().then(render);
