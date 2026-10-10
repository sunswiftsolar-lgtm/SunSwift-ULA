import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const root=new URL('../',import.meta.url),read=path=>fs.readFileSync(new URL(path,root),'utf8');
const ui=read('app/src/main/assets/native-photos.js');
const code=ui.match(/async function captureConsumerPhoto\(type,file\)\{[\s\S]*?(?=    setInterval)/)[0];
let acknowledge,requests=[],polled=0;
const sandbox={applyPermissions(){},requirePhotoRecord:()=>({consumerNo:'SYNTHETIC'}),geoPhotoStatus(){},notify(){},
 PHOTO_LABELS:{panel1:'Panel 1',panel2:'Panel 2'},pollPhonePhotos(){polled++},
 UlaNative:{request(kind,payload){requests.push({kind,payload});return new Promise(resolve=>acknowledge=resolve)}},
 cloudCall(){throw Error('Capture must not call server')},currentPosition(){throw Error('Capture must not await GPS')}};
vm.createContext(sandbox);vm.runInContext('let photoBusy=false;'+code,sandbox);
const first=sandbox.captureConsumerPhoto('panel1',{});
assert.equal(requests.length,1);assert.equal(requests[0].kind,'capturePhoto');
assert.equal(vm.runInContext('photoBusy',sandbox),true);
acknowledge({queued:true});await first;
assert.equal(vm.runInContext('photoBusy',sandbox),false);
const second=sandbox.captureConsumerPhoto('panel2',{});assert.equal(requests.length,2);
acknowledge({queued:true});await second;assert.equal(polled,2);
const queue=read('app/src/main/java/com/sunswift/ula/PhotoQueue.kt'),worker=read('app/src/main/java/com/sunswift/ula/PhotoUploadWorker.kt');
assert.ok(queue.indexOf('write(itemFile(id, "raw"), raw)')<queue.indexOf('\n        cancelDraft()'));
assert.match(queue,/noBackupFilesDir/);assert.match(queue,/AES\/GCM\/NoPadding/);assert.match(queue,/AtomicFile/);
assert.match(queue,/NetworkType.CONNECTED/);assert.match(queue,/BackoffPolicy.EXPONENTIAL/);
assert.match(worker,/api.resumeSession\(token\)/);assert.match(worker,/firebaseMobile != mobile/);
assert.match(worker,/listConsumerImages/);assert.match(worker,/Result.retry\(\)/);
assert.ok(!read('app/src/main/AndroidManifest.xml').includes('ACCESS_BACKGROUND_LOCATION'));
const service=read('app/src/main/java/com/sunswift/ula/CaptureLocationService.kt');
assert.match(service,/FOREGROUND_SERVICE_TYPE_LOCATION/);
assert.match(service,/15 \* 60_000L/);assert.match(service,/stopSelf\(\)/);
assert.match(queue,/fix\.optLong\("fixTime"\) - capturedMillis/);
console.log('PASS: next capture enabled after disk acknowledgement, no network/GPS wait in capture UI; encrypted atomic storage, network-constrained retries, same-user server revalidation, read-back recovery and no background-location permission.');
