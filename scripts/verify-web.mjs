import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';

const root = path.resolve(import.meta.dirname, '..');
const asset = fs.readFileSync(path.join(root, 'app/src/main/assets/ula-45.7.html'), 'utf8');
const manifest = JSON.parse(fs.readFileSync(path.join(root, 'reference/manifest.json'), 'utf8'));
assert.equal(crypto.createHash('sha256').update(asset).digest('hex'), manifest.assetSha256);
let scripts = 0;
for (const match of asset.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)) {
  if (/\btype=["']application\/json/.test(match[1]) || !match[2].trim()) continue;
  new vm.Script(match[2], {filename: `ula-45.7-script-${++scripts}.js`});
}
assert.ok(scripts >= 3, 'Expected the complete UI and embedded report libraries');
assert.ok(!asset.includes('src="https://www.gstatic.com/firebasejs/'), 'Native Firebase must own authentication');
assert.ok(asset.includes("UlaNative.request('api',{method,args})"));
assert.ok(asset.includes("Android 1.5.0 · Web 45.7"));
assert.ok(asset.includes('serial number was not confirmed'), 'Photo serial confirmation must remain required');
const originalPath = path.join(root, 'reference/Index-45.7.html');
if (fs.existsSync(originalPath)) {
  const original = fs.readFileSync(originalPath, 'utf8');
  const controls = html => [...html.matchAll(/<(?:input|select|textarea)\b[^>]*\bname=["']([^"']+)["']/g)].map(m => m[1]).sort();
  assert.deepEqual(controls(asset), controls(original), 'All latest web form controls must be preserved');
}
const helper = fs.readFileSync(path.join(root, 'backend/AndroidApi.gs'), 'utf8');
const methods = [...helper.matchAll(/^\s+(\w+):\s*\1,?$/gm)].map(m => m[1]);
assert.equal(methods.length, 27);
const sandbox = { validationError_: text => { throw Error(text); } };
for (const method of methods) sandbox[method] = (...args) => ({method, args});
vm.createContext(sandbox);
vm.runInContext(helper, sandbox);
assert.equal(sandbox.mobileAndroidCall_('native-session', 'listRecords', []).args[0], 'native-session');
assert.throws(() => sandbox.mobileAndroidCall_('', '__proto__', []), /Unknown/);
assert.throws(() => sandbox.mobileAndroidCall_('', 'constructor', []), /Unknown/);
assert.throws(() => sandbox.mobileAndroidCall_('', 'listRecords', {}), /Invalid Android request/);
assert.throws(() => sandbox.mobileAndroidCall_('', 'listRecords', Array(7)), /Invalid Android request/);

const sent = [], callbacks = new Map();
const bridge = fs.readFileSync(path.join(root, 'app/src/main/assets/native-adapter.js'), 'utf8');
const host = {postMessage: value => sent.push(JSON.parse(value))};
const browser = {window: {ulaHost: host}, ulaHost: host,
  document: {addEventListener() {}}, setTimeout(fn) {callbacks.set(callbacks.size + 1, fn); return callbacks.size;},
  clearTimeout(id) {callbacks.delete(id);}};
vm.createContext(browser); vm.runInContext(bridge, browser);
const resolved = browser.window.UlaNative.request('session');
host.onmessage({data: JSON.stringify({id: sent[0].id, ok: true, result: {user: {role: 'ADMIN'}}})});
assert.equal((await resolved).user.role, 'ADMIN');
const denied = browser.window.UlaNative.request('api', {method: 'listUsers'});
host.onmessage({data: JSON.stringify({id: sent[1].id, ok: false, error: 'Admin access required'})});
await assert.rejects(denied, /Admin access/);
const timedOut = browser.window.UlaNative.request('api');
[...callbacks.values()][0](); await assert.rejects(timedOut, /timed out/);
console.log(`PASS: ${scripts} scripts parse; web controls preserved; 27-method allowlist and bridge success/error/timeout checks.`);
