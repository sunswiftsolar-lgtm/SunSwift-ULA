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
for (const style of asset.matchAll(/<style>([\s\S]*?)<\/style>/g)) {
  assert.ok(!/\b\d+(?:\.\d+)?(?:dvh|vh)\b/.test(style[1]), 'Android height limits must use measured viewport pixels');
}
const originalPath = path.join(root, 'reference/Index-45.7.html');
const controls = html => [...html.matchAll(/<(?:input|select|textarea)\b[^>]*\bname=["']([^"']+)["']/g)].map(m => m[1]).sort();
assert.ok(manifest.sourceControls?.length > 0, 'The reference control list must be included for CI');
assert.deepEqual(controls(asset), manifest.sourceControls, 'All latest web form controls must be preserved on every build');
if (fs.existsSync(originalPath)) {
  const original = fs.readFileSync(originalPath, 'utf8');
  assert.deepEqual(controls(original), manifest.sourceControls, 'The control manifest must match the unmodified source');
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
const viewportProperties = new Map(), windowEvents = new Map();
const browser = {window: {ulaHost: host, addEventListener(name, fn) {windowEvents.set(name,fn);}}, ulaHost: host, innerHeight:728,
  document: {documentElement:{style:{setProperty(name,value) {viewportProperties.set(name,value);}}},addEventListener() {}}, setTimeout(fn) {callbacks.set(callbacks.size + 1, fn); return callbacks.size;},
  clearTimeout(id) {callbacks.delete(id);}};
vm.createContext(browser); vm.runInContext(bridge, browser);
assert.equal(viewportProperties.get('--ula-viewport-height'), '728px');
browser.innerHeight=0; windowEvents.get('resize')();
assert.equal(viewportProperties.get('--ula-viewport-height'), '728px', 'Transient zero layout must not collapse dialogs');
browser.innerHeight=840; windowEvents.get('resize')();
assert.equal(viewportProperties.get('--ula-viewport-height'), '840px', 'Rotation must update the layout');
const resolved = browser.window.UlaNative.request('session');
host.onmessage({data: JSON.stringify({id: sent[0].id, ok: true, result: {user: {role: 'ADMIN'}}})});
assert.equal((await resolved).user.role, 'ADMIN');
const denied = browser.window.UlaNative.request('api', {method: 'listUsers'});
host.onmessage({data: JSON.stringify({id: sent[1].id, ok: false, error: 'Admin access required'})});
await assert.rejects(denied, /Admin access/);
const timedOut = browser.window.UlaNative.request('api');
[...callbacks.values()][0](); await assert.rejects(timedOut, /timed out/);

// A model download must not remove the mandatory human serial check.
const confirmationCode = asset.match(/async function confirmPhotoSerial\(file,type\)\{[\s\S]*?(?=    async function captureConsumerPhoto)/)?.[0];
assert.ok(confirmationCode, 'Expected the native photo serial adapter');
let scanResult = {candidates: [], source: 'model-unavailable'}, answer = 'new12345', promptMessage = '';
const photoSandbox = {
  PHOTO_SERIAL_FIELDS: {panel1:'panelNo1'}, PHOTO_LABELS:{panel1:'Panel 1'},
  form:{elements:{panelNo1:{value:'OLD12345'}}},
  createImageBitmap: async () => ({width:800,height:600,close(){}}),
  document:{createElement:() => ({getContext:() => ({drawImage(){}}),toDataURL:() => 'data:image/jpeg;base64,test'})},
  UlaNative:{request:async () => scanResult},
  prompt:message => {promptMessage=message; return answer;}
};
vm.createContext(photoSandbox); vm.runInContext(confirmationCode, photoSandbox);
assert.equal(await photoSandbox.confirmPhotoSerial({},'panel1'), 'NEW12345');
assert.match(promptMessage, /Automatic scanning is not ready/);
answer=null; await assert.rejects(photoSandbox.confirmPhotoSerial({},'panel1'), /not confirmed/);
answer='bad'; await assert.rejects(photoSandbox.confirmPhotoSerial({},'panel1'), /valid serial/);
scanResult={candidates:['DETECTED123'],source:'barcode'}; answer='DETECTED123';
assert.equal(await photoSandbox.confirmPhotoSerial({},'panel1'), 'DETECTED123');
assert.match(promptMessage, /Check and correct/);
assert.equal(await photoSandbox.confirmPhotoSerial({},'plant'), '');
console.log(`PASS: ${scripts} scripts parse; web controls preserved; 27-method allowlist, bridge success/error/timeout, viewport and required serial confirmation checks.`);
