/* Only the bundled HTTPS asset origin can communicate with the native host. */
(() => {
  const syncViewport = () => {
    if (innerHeight > 0) document.documentElement.style.setProperty('--ula-viewport-height', innerHeight+'px');
  };
  syncViewport();
  window.addEventListener('resize', syncViewport);
  document.addEventListener('DOMContentLoaded', syncViewport);
  let nextId = 0;
  const pending = new Map();
  window.UlaNative = {
    request(kind, payload = {}) {
      return new Promise((resolve, reject) => {
        if (!window.ulaHost) { reject(Error('The secure Android connection is unavailable.')); return; }
        const id = String(++nextId);
        const timer = setTimeout(() => { pending.delete(id); reject(Error('The request timed out. Check the cloud before retrying.')); }, kind==='capturePhoto'?900000:120000);
        pending.set(id, {resolve,reject,timer});
        ulaHost.postMessage(JSON.stringify({id,kind,payload}));
      });
    }
  };
  window.ulaHost && (ulaHost.onmessage = event => {
    const reply = JSON.parse(event.data), call = pending.get(reply.id);
    if (!call) return;
    clearTimeout(call.timer); pending.delete(reply.id);
    reply.ok ? call.resolve(reply.result) : call.reject(Error(reply.error || 'ULA request failed.'));
  });
  document.addEventListener('click', event => {
    syncViewport();
    const anchor = event.target.closest?.('a[download]');
    if (!anchor || !/^(blob:|data:)/.test(anchor.href)) return;
    event.preventDefault();
    (async () => {
      const blob = await (await fetch(anchor.href)).blob();
      const dataUrl = await new Promise((resolve,reject) => {
        const reader=new FileReader(); reader.onload=()=>resolve(reader.result); reader.onerror=reject; reader.readAsDataURL(blob);
      });
      await UlaNative.request('download',{name:anchor.download || 'ULA-report',dataUrl});
    })().catch(error => alert(error.message || 'The file could not be saved.'));
  }, true);
})();
