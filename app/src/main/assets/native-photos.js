    // Native durable capture: the UI waits only for a disk save, never GPS/geocoding/upload.
    let phonePhotoItems=[],phonePhotoPollBusy=false;
    const phonePhotoPanel=document.createElement('div');phonePhotoPanel.id='phonePhotoQueue';phonePhotoPanel.className='note';
    document.querySelector('#geoPhotos').appendChild(phonePhotoPanel);
    function showPhonePhotoQueue(){
      const pending=phonePhotoItems.filter(item=>!['uploaded','superseded'].includes(item.status));
      const current=phonePhotoItems.filter(item=>item.consumerNo===form.elements.consumerNo.value);
      const latest={};current.forEach(item=>latest[item.imageType]=item);
      Object.values(latest).forEach(item=>{
        const status=document.querySelector(`[data-photo-status="${item.imageType}"]`);
        if(status)status.textContent=item.message;
        if(item.status==='uploaded'&&item.image)consumerPhotos[item.imageType]=item.image;
      });
      phonePhotoPanel.innerHTML=`<b>Photos saved safely on this phone</b><br>${pending.length} waiting for GPS, connection or upload. You can take the next photo immediately after saving.<br>Originals stay on this phone after upload. Clearing app data or uninstalling removes local copies.<br><button type="button" class="btn" id="retryPhonePhotos">Retry uploads</button>`;
      $('retryPhonePhotos').onclick=()=>UlaNative.request('retryPhotos').then(()=>pollPhonePhotos()).catch(error=>notify(error.message,true));
      for(const item of current.slice(-12)){
        const row=document.createElement('div');row.textContent=`${PHOTO_LABELS[item.imageType]} · ${fmtDateTime(item.capturedAt)} · ${item.message}`;
        const view=document.createElement('button');view.type='button';view.className='btn';view.textContent='View phone photo';
        view.onclick=async()=>{try{const local=await UlaNative.request('phonePhoto',{id:item.id});$('photoViewerTitle').textContent=PHOTO_LABELS[item.imageType]+' · phone copy';$('photoViewerSubtitle').textContent=item.message;$('photoViewerImage').src=local.dataUrl;$('photoViewerMeta').textContent='Saved on this phone · '+fmtDateTime(item.capturedAt);viewedPhoto=null;$('photoViewer').showModal()}catch(error){notify(error.message,true)}};row.appendChild(view);
        const field=PHOTO_SERIAL_FIELDS[item.imageType];
        if(field){const scan=document.createElement('button');scan.type='button';scan.className='btn';scan.textContent='Read serial';scan.onclick=async()=>{try{const result=await UlaNative.request('phonePhoto',{id:item.id,scan:true});const candidate=(result.candidates||[]).find(value=>/^[A-Z0-9][A-Z0-9._\/-]{4,79}$/i.test(String(value)));if(!candidate){notify('No serial detected. Enter it manually in the form.');return}const serial=prompt('Review and correct the detected serial before saving:',candidate);if(serial===null)return;if(!/^[A-Z0-9][A-Z0-9._\/-]{4,79}$/i.test(serial.trim()))throw Error('Enter a valid serial number.');if(form.elements.consumerNo.value!==item.consumerNo)throw Error('Reopen this consumer before applying its serial.');form.elements[field].value=serial.trim().toUpperCase();validateEquipmentFields(records,true);notify('Serial confirmed. Use Update record to save it.')}catch(error){notify(error.message,true)}};row.appendChild(scan)}
        phonePhotoPanel.appendChild(row);
      }
    }
    async function pollPhonePhotos(){
      if(phonePhotoPollBusy||!currentSession)return;
      phonePhotoPollBusy=true;
      try{
        const result=await UlaNative.request('photoQueue');phonePhotoItems=result.items||[];showPhonePhotoQueue();
        if(result.unfinishedCapture){
          const button=document.createElement('button');button.type='button';button.className='btn';button.textContent='Recover unfinished camera photo';
          button.onclick=()=>{if(confirm('Recover the photo from the interrupted camera session? Confirm this is a photo you intended to save.'))UlaNative.request('recoverCapture').then(()=>pollPhonePhotos()).catch(error=>notify(error.message,true))};phonePhotoPanel.appendChild(button);
        }
      }catch(error){phonePhotoPanel.textContent='Phone photo queue unavailable: '+error.message}
      finally{phonePhotoPollBusy=false}
    }
    async function captureConsumerPhoto(type,file){
      if(photoBusy)return;
      photoBusy=true;applyPermissions();
      try{
        const record=requirePhotoRecord(type);
        geoPhotoStatus('Take a photo. It will save on this phone before background processing.');
        await UlaNative.request('capturePhoto',{imageType:type,record});
        geoPhotoStatus('Photo saved safely on phone. Take the next photo; GPS and upload continue separately.');
        notify(PHOTO_LABELS[type]+' saved on phone · uploading automatically when ready');
      }catch(error){geoPhotoStatus(error.message||'Photo was not confirmed.',true);notify(error.message||'Photo was not confirmed.',true)}
      finally{photoBusy=false;applyPermissions();void pollPhonePhotos()}
    }
    setInterval(()=>{if(document.visibilityState==='visible')void pollPhonePhotos()},3000);
    document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible')void pollPhonePhotos()});

