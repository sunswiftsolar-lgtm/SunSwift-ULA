// Loopback-only, synthetic-data QA. Never contacts the ULA cloud or changes records.
import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const asset = await fs.readFile(path.join(root, 'app/src/main/assets/ula-web.html'), 'utf8');
const manifest = JSON.parse(await fs.readFile(path.join(root, 'reference/manifest.json'), 'utf8'));
const output = path.join(root, 'test-results/interface');
await fs.mkdir(output, {recursive:true});
const roles = new Set(['ADMIN','EDITOR','INSTALLER']);
const downloads = [];
const server = http.createServer(async (req,res) => {
  try {
    if (req.headers.host !== '127.0.0.1:8876') {res.writeHead(403).end();return;}
    const url = new URL(req.url, 'http://127.0.0.1:8876');
    if (req.method === 'POST' && url.pathname === '/download' && req.headers.origin === 'http://127.0.0.1:8876') {
      let raw='';for await(const chunk of req){raw+=chunk;if(raw.length>24*1024*1024)throw Error('Download too large');}
      const value=JSON.parse(raw);
      const name=String(value.name).replace(/[^A-Za-z0-9_.-]/g,'_');
      if(!/\.(pdf|xlsx|csv)$/i.test(name))throw Error('Unsupported test artifact');
      const encoded=String(value.dataUrl).match(/^data:[^,]+;base64,([A-Za-z0-9+/=\r\n]+)$/);
      if(!encoded)throw Error('Invalid test download');
      const bytes=Buffer.from(encoded[1],'base64');
      await fs.writeFile(path.join(output,name),bytes);
      downloads.push({name,bytes:bytes.length});
      res.writeHead(200,{'Content-Type':'application/json'}).end('{"saved":true}');return;
    }
    if(req.method==='GET'&&url.pathname==='/files'){
      res.writeHead(200,{'Content-Type':'application/json'}).end(JSON.stringify(downloads));return;
    }
    if(req.method!=='GET'||url.pathname!=='/'){res.writeHead(404).end();return;}
    const role=roles.has(url.searchParams.get('role'))?url.searchParams.get('role'):'ADMIN';
    const user={name:'SYNTHETIC '+role,mobile:'+910000000000',role,permissions:{admin:role==='ADMIN',edit:role!=='INSTALLER',installationEdit:role==='INSTALLER',export:role!=='INSTALLER',view:true,billSubmit:role==='ADMIN',uploadStatus:role==='ADMIN'}};
    const now=new Date().toISOString(),day=now.slice(0,10);
    const record=Object.fromEntries(manifest.sourceControls.filter(n=>!n.startsWith('photo')).map(n=>[n,'']));
    Object.assign(record,{id:'synthetic-record',version:1,consumerNo:'TEST00000001',consumerName:'SYNTHETIC CONSUMER',referenceNo:'99999999999999',mobileNo:'0000000000',divisionName:'JEYPORE',subDivisionName:'DEMO SUB DIVISION',sectionName:'DEMO SECTION',panchayatName:'DEMO PANCHAYAT',villageName:'DEMO VILLAGE',registrationDate:day,surveyDate:day,surveyDoneBy:'DEMO SURVEYOR',paidAmount:1875,paymentDate:day,paymentReceivedFromConsumer:500,paymentReceiveDate:day,installationDate:day,installer1:'DEMO INSTALLER',installer4:'DEMO FOURTH INSTALLER',installer1SetAt:now,installer4SetAt:now,createdAt:now,updatedAt:now,createdBy:'SYNTHETIC ADMIN',updatedBy:'SYNTHETIC ADMIN'});
    const payload={records:[record],installers:['DEMO INSTALLER','DEMO FOURTH INSTALLER'],locationDirectory:[{divisionName:record.divisionName,subDivisionName:record.subDivisionName,sectionName:record.sectionName,panchayatName:record.panchayatName,villageNames:[record.villageName],installers:['DEMO INSTALLER','DEMO FOURTH INSTALLER']}],transportation:[],session:user};
    const bootstrap=`<script>(()=>{const user=${JSON.stringify(user)},payload=${JSON.stringify(payload)};window.ulaHost={postMessage(raw){const request=JSON.parse(raw);(async()=>{let result;if(request.kind==='session')result={sessionToken:'SYNTHETIC-NOT-A-REAL-TOKEN',expiresAt:Date.now()+3600000,user};else if(request.kind==='download'){const response=await fetch('/download',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(request.payload)});if(!response.ok)throw Error('Test download failed');result=await response.json();}else if(request.kind==='api'){const method=request.payload.method;if(method==='listRecords')result=payload;else if(method==='listRecentActivity')result={activity:[]};else if(method==='listConsumerImages')result={images:[]};else if(method==='listUsers')result={users:[],installers:payload.installers,locationDirectory:payload.locationDirectory,transportation:[]};else throw Error('Read-only synthetic test: operation disabled ('+method+')');}else throw Error('Read-only test operation disabled');return {id:request.id,ok:true,result};})().catch(error=>({id:request.id,ok:false,error:error.message})).then(value=>window.ulaHost.onmessage({data:JSON.stringify(value)}));}};})();</script>`;
    res.writeHead(200,{'Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store'}).end(asset.replace('<head>','<head>'+bootstrap));
  }catch(error){res.writeHead(400,{'Content-Type':'text/plain'}).end(error.message);}
});
server.listen(8876,'127.0.0.1',()=>console.log('Synthetic, read-only UI test: http://127.0.0.1:8876/?role=ADMIN'));
