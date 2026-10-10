import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const root = path.resolve(import.meta.dirname, '..');
const release = JSON.parse(fs.readFileSync(path.join(root, 'reference/web-release.json'), 'utf8'));
const {webVersion, androidVersion, versionCode} = release;
if (!/^\d+(?:\.\d+)+$/.test(webVersion) || !/^\d+\.\d+\.\d+$/.test(androidVersion) || !Number.isSafeInteger(versionCode) || versionCode < 1) throw Error('Invalid pinned release configuration.');
const wrapper = fs.readFileSync(path.join(root, `reference/web-${webVersion}-response.html`), 'utf8');
const literal = wrapper.match(/goog\.script\.init\(("(?:\\.|[^"\\])*")/);
if (!literal) throw new Error('The downloaded page does not contain an Apps Script HTML payload.');
const config = JSON.parse(JSON.parse(literal[1].replace(/\\x([0-9a-f]{2})/gi, '\\u00$1')));
const source = config.userHtml;
if (!source?.includes(`versionBadge.textContent='Version ${webVersion}'`)) throw new Error(`Expected deployed web version ${webVersion}.`);
fs.writeFileSync(path.join(root, `reference/Index-${webVersion}.html`), source);

function replaceOnce(text, expression, replacement) {
  const matches = text.match(new RegExp(expression.source, 'g'));
  if (matches?.length !== 1) throw new Error(`Expected one adapter insertion point: ${expression}`);
  return text.replace(expression, replacement);
}

let adapted = source.replace(/\s*<script src="https:\/\/www\.gstatic\.com\/firebasejs\/[^"\n]+"><\/script>/g, '');
adapted = replaceOnce(adapted, /    function cloudCall\(method,\.\.\.args\)\{[\s\S]*?(?=    function setCloudStatus)/,
  `    function cloudCall(method,...args){return UlaNative.request('api',{method,args}).catch(error=>{error.userMessage=error.message.replace(/^\\w+:\\s*/,'');throw error})}\n`);
adapted = replaceOnce(adapted, /    async function initAuth\(\)\{[\s\S]*?(?=    function directoryChips)/,
  `    async function initAuth(){const session=await UlaNative.request('session');applyAuthenticated(session);$('logoutBtn').addEventListener('click',()=>signOut(false));return true}\n`);
adapted = replaceOnce(adapted, /    async function signOut\(expired=false\)\{[\s\S]*?(?=    function wireAuthEvents)/,
  `    async function signOut(expired=false){clearStoredSession();await UlaNative.request('logout',{expired})}\n`);
adapted = adapted.replace("localStorage.setItem(AUTH_STORAGE_KEY,JSON.stringify(session));", '// The native app owns the session; do not duplicate credentials in web storage.');
adapted = adapted.replace(`versionBadge.textContent='Version ${webVersion}'`, `versionBadge.textContent='Android ${androidVersion} · Web ${webVersion}'`);
adapted = replaceOnce(adapted, /    async function confirmPhotoSerial\(file,type\)\{[\s\S]*?(?=    async function captureConsumerPhoto)/,
  `    async function confirmPhotoSerial(file,type){
      if(!PHOTO_SERIAL_FIELDS[type])return '';
      let bitmap;try{
        bitmap=await createImageBitmap(file);const canvas=document.createElement('canvas');
        const scale=Math.min(1,1600/Math.max(bitmap.width,bitmap.height));canvas.width=Math.round(bitmap.width*scale);canvas.height=Math.round(bitmap.height*scale);canvas.getContext('2d').drawImage(bitmap,0,0,canvas.width,canvas.height);
        const scanned=await UlaNative.request('scan',{dataUrl:canvas.toDataURL('image/jpeg',.85),type});
        const candidate=(scanned.candidates||[]).map(value=>String(value).trim().toUpperCase()).find(value=>/^[A-Z0-9][A-Z0-9._\\/-]{4,79}$/.test(value));
        if(!candidate)return '';
        const confirmed=prompt(PHOTO_LABELS[type]+' serial detected. Check and correct it against the picture before saving:',candidate);
        if(confirmed===null)throw Error('Image upload cancelled because the serial number was not confirmed.');
        const serial=confirmed.trim().toUpperCase();if(!/^[A-Z0-9][A-Z0-9._\\/-]{4,79}$/.test(serial))throw Error('Enter a valid serial number or recapture the image.');return serial;
      }catch(error){if(error.message?.includes('cancelled')||error.message?.includes('valid serial'))throw error;return ''}
      finally{bitmap?.close?.()}
    }\n`);
const bridge = fs.readFileSync(path.join(root, 'app/src/main/assets/native-adapter.js'), 'utf8');
const mobileCss = fs.readFileSync(path.join(root, 'app/src/main/assets/native-mobile.css'), 'utf8');
adapted = adapted.replace('</head>', `<style>${mobileCss}</style><script>${bridge}</script></head>`);
// Some embedded Android WebViews measure vh/dvh as zero at first layout. Use
// the real JavaScript viewport height, keeping each original proportion intact.
adapted = adapted.replace(/<style>([\s\S]*?)<\/style>/g, (tag, css) =>
  `<style>${css.replace(/\b(\d+(?:\.\d+)?)(?:dvh|vh)\b/g, (_, value) =>
    `calc(var(--ula-viewport-height, 600px) * ${Number(value) / 100})`)}</style>`);
const assets = path.join(root, 'app/src/main/assets');
fs.mkdirSync(assets, {recursive:true});
// Canonical line endings make the generated Git asset reproducible. The raw
// downloaded source and its hash remain unchanged for reference verification.
adapted = adapted.replace(/\r\n/g, '\n');
fs.writeFileSync(path.join(assets, 'ula-web.html'), adapted);
const sourceControls = [...source.matchAll(/<(?:input|select|textarea)\b[^>]*\bname=["']([^"']+)["']/g)].map(match => match[1]).sort();
const manifest = {webVersion,androidVersion,versionCode,assetFile:'ula-web.html',sourceSha256:crypto.createHash('sha256').update(source).digest('hex'),assetSha256:crypto.createHash('sha256').update(adapted).digest('hex'),sourceControls,deploymentId:config.deploymentId};
fs.writeFileSync(path.join(root, 'reference/manifest.json'), JSON.stringify(manifest,null,2)+'\n');
console.log(JSON.stringify({sourceBytes:Buffer.byteLength(source),assetBytes:Buffer.byteLength(adapted),...manifest},null,2));
