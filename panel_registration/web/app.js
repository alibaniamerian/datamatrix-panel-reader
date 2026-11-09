'use strict';
const $=id=>document.getElementById(id);
const state={panel:null,capture:null,registration:null,anchors:[],image:null,scale:1,planRotation:0,busy:false,readResults:{}};
let lastPhoneState='';
let exposureLoaded=false;
let torchEnabled=false;
$('toggleTorch').onclick=()=>action(async()=>{const button=$('toggleTorch');button.disabled=true;try{const result=await api('/api/torch',{enabled:!torchEnabled});torchEnabled=result.exposure.torch_enabled;renderTorch(result.exposure);message(torchEnabled?'Phone light is on.':'Phone light is off.','success');}finally{button.disabled=false;}});
function renderTorch(exposure){torchEnabled=!!exposure.torch_enabled;$('toggleTorch').textContent=torchEnabled?'Turn light off':'Turn light on';$('toggleTorch').setAttribute('aria-pressed',String(torchEnabled));$('toggleTorch').disabled=exposure.torch_available===false;$('torchStatus').textContent=exposure.torch_available===false?'No light available for this camera.':torchEnabled?'Light is on':'Light is off';}
function shutterHint(){const ms=Number($('shutterMs').value);$('shutterFraction').textContent=ms>0?(ms/1000).toPrecision(4)+' seconds (about 1/'+(1000/ms).toFixed(2)+' s)':'';}
$('shutterMs').oninput=shutterHint;
$('resetExposure').onclick=()=>{$('cameraIso').value=100;$('shutterMs').value=112.5;$('retryIso50').checked=true;shutterHint();};
$('applyExposure').onclick=()=>action(async()=>{const iso=Number($('cameraIso').value),exposure_ms=Number($('shutterMs').value);if(!Number.isInteger(iso)||iso<1||!Number.isFinite(exposure_ms)||exposure_ms<=0)throw Error('Enter a valid ISO and shutter time.');$('applyExposure').disabled=true;try{await api('/api/exposure',{iso,exposure_ms,retry_iso50:$('retryIso50').checked});message('Exposure applied to phone. Read values and alignment are retained.','success');await device();}finally{$('applyExposure').disabled=false;}});
shutterHint();
const fa=n=>Number(n).toLocaleString('en-US');
function message(text,kind=''){ $('message').textContent=text;$('message').className=kind; }
async function api(path,body){const response=await fetch(path,body===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});const result=await response.json();if(!response.ok)throw Error(result.error||'Request failed.');return result;}
async function action(fn){if(state.busy)return;state.busy=true;controls();try{await fn();}catch(e){message(e.message,'error');}finally{state.busy=false;controls();}}
function invalidate(){state.registration=null;$('review').checked=false;$('review').disabled=true;controls();drawPhoto();}
function controls(){const complete=state.anchors.length===4;$('capture').disabled=state.busy;$('capture').textContent=state.busy?'Working...':'Take photo from phone';$('calculate').disabled=state.busy||!complete;$('undo').disabled=state.busy||!state.anchors.length;$('reset').disabled=state.busy||!state.anchors.length;$('apply').disabled=state.busy||!state.registration||!$('review').checked;$('pointCount').textContent=fa(state.anchors.length)+' of 4 points';$('referenceTitle').textContent=complete?'All four centers selected':state.panel?'Reference '+fa(state.anchors.length+1)+' · '+state.panel.anchor_ids[state.anchors.length]:'Select the component center';renderReferences();drawPlan();}
function renderReferences(){if(!state.panel)return;$('anchorList').replaceChildren();state.panel.anchor_ids.forEach((name,i)=>{const li=document.createElement('li');li.className=i<state.anchors.length?'done':i===state.anchors.length?'active':'';const badge=document.createElement('b');badge.textContent=fa(i+1);const code=document.createElement('code');code.textContent=name;const text=document.createElement('span');text.textContent=i<state.anchors.length?'Selected':i===state.anchors.length?'Click on the photo':'';li.append(badge,code,text);if(i<state.anchors.length){li.style.cursor='pointer';li.onclick=()=>selectAnchor(i);} $('anchorList').append(li);});}
function planPoint(part){let x=(part.x_mm-53.1)/81,y=(72.975-part.y_mm)/108;for(let i=0;i<state.planRotation;i++){const t=x;x=-y;y=t;}return [145+x*198,165+y*250];}
function drawPlan(){if(!state.panel)return;const c=$('plan'),ctx=c.getContext('2d');ctx.clearRect(0,0,c.width,c.height);for(const part of state.panel.parts){const [x,y]=planPoint(part),index=state.panel.anchor_ids.indexOf(part.designator),active=index===state.anchors.length;ctx.fillStyle=active?'#087f82':index>=0?'#dbeef0':'#e8eef0';ctx.strokeStyle=active?'#087f82':'#c3d3d9';ctx.lineWidth=active?2:1;ctx.fillRect(x-15,y-11,30,22);ctx.strokeRect(x-15,y-11,30,22);if(index>=0){ctx.fillStyle=active?'white':'#176f74';ctx.font='bold 13px sans-serif';ctx.textAlign='center';ctx.fillText(fa(index+1),x,y+5);}else{ctx.beginPath();ctx.arc(x,y,2,0,Math.PI*2);ctx.fillStyle='#91abb4';ctx.fill();}}}
function orientedSize(){const {width:w,height:h,rotation_degrees:r}=state.capture;return r%180?[h,w]:[w,h];}
function rawToView(x,y){const {width:w,height:h,rotation_degrees:r}=state.capture;if(r===90)return [h-y,x];if(r===180)return [w-x,h-y];if(r===270)return [y,w-x];return [x,y];}
function viewToRaw(x,y){const {width:w,height:h,rotation_degrees:r}=state.capture;if(r===90)return [y,h-x];if(r===180)return [w-x,h-y];if(r===270)return [w-y,x];return [x,y];}
function applyZoom(){if(!state.image)return;const [w,h]=orientedSize();$('photo').style.width=w*state.scale+'px';$('photo').style.height=h*state.scale+'px';$('zoomValue').textContent=fa(Math.round(state.scale*100))+'%';}
function fit(){if(!state.image)return;const [w,h]=orientedSize(),v=$('viewport');state.scale=Math.min((v.clientWidth-40)/w,(v.clientHeight-40)/h);applyZoom();}
function drawPhoto(){if(!state.image)return;const canvas=$('photo'),ctx=canvas.getContext('2d'),[w,h]=orientedSize();canvas.width=w;canvas.height=h;ctx.save();const r=state.capture.rotation_degrees;if(r===90){ctx.translate(w,0);ctx.rotate(Math.PI/2);}else if(r===180){ctx.translate(w,h);ctx.rotate(Math.PI);}else if(r===270){ctx.translate(0,h);ctx.rotate(-Math.PI/2);}ctx.drawImage(state.image,0,0);ctx.restore();const line=2/Math.max(.2,state.scale),font=12/Math.max(.35,state.scale);if(state.registration){for(const part of (state.registration.all_parts||state.registration.parts)){ctx.setLineDash(part.body_fit&&!part.body_fit.detected?[6/state.scale,4/state.scale]:[]);const points=(part.body_quad_normalized||part.quad_normalized).map(([x,y])=>rawToView(x*state.capture.width,y*state.capture.height));ctx.beginPath();points.forEach(([x,y],i)=>i?ctx.lineTo(x,y):ctx.moveTo(x,y));ctx.closePath();ctx.strokeStyle='#28d6c2';ctx.lineWidth=line;ctx.stroke();ctx.setLineDash([]);const [x,y]=rawToView(...part.center_px);ctx.beginPath();ctx.moveTo(x-7/state.scale,y);ctx.lineTo(x+7/state.scale,y);ctx.moveTo(x,y-7/state.scale);ctx.lineTo(x,y+7/state.scale);ctx.stroke();ctx.font=font+'px monospace';ctx.textAlign='center';ctx.fillStyle='#fff';ctx.shadowColor='#000';ctx.shadowBlur=5;ctx.fillText(part.designator,x,y-12/state.scale);ctx.shadowBlur=0;}}
state.anchors.forEach((a,i)=>{const [x,y]=rawToView(a.x,a.y),length=a.marker_length||80,height=length*6/11,angle=(a.marker_angle||0)*Math.PI/180;ctx.save();ctx.translate(x,y);ctx.rotate(angle);ctx.fillStyle='rgba(237,145,77,.12)';ctx.fillRect(-length/2,-height/2,length,height);ctx.strokeStyle=i===anchorEdit?'#ffe36b':'#ed914d';ctx.lineWidth=line;ctx.strokeRect(-length/2,-height/2,length,height);ctx.beginPath();ctx.moveTo(-4/state.scale,0);ctx.lineTo(4/state.scale,0);ctx.moveTo(0,-4/state.scale);ctx.lineTo(0,4/state.scale);ctx.stroke();ctx.restore();ctx.fillStyle='#ffe36b';ctx.font='bold '+font+'px sans-serif';ctx.textAlign='center';ctx.shadowColor='#000';ctx.shadowBlur=4;ctx.fillText(fa(i+1),x,y-height/2-8/state.scale);ctx.shadowBlur=0;});
if(anchorHover&&!anchorDrag&&!state.busy&&state.anchors.length<4){const [x,y]=anchorHover,length=Number($('anchorLength').value)||80,height=length*6/11,angle=(Number($('anchorAngle').value)||0)*Math.PI/180;ctx.save();ctx.translate(x,y);ctx.rotate(angle);ctx.strokeStyle='#ffe36b';ctx.lineWidth=line;ctx.strokeRect(-length/2,-height/2,length,height);ctx.restore();}
$('photo').style.cursor=anchorHover&&!anchorDrag&&state.anchors.length<4?'none':anchorDrag?'grabbing':'grab';
drawReadResults(ctx,font);applyZoom();}
async function loadImage(capture){state.capture=capture;state.readResults={};state.image=await new Promise((resolve,reject)=>{const img=new Image();img.onload=()=>resolve(img);img.onerror=()=>reject(Error('Could not load the photo.'));img.src='/api/capture.png?id='+encodeURIComponent(capture.request_id);});$('empty').hidden=true;$('photo').hidden=false;$('imageMeta').textContent=capture.width+' × '+capture.height+' · YUV / PNG';drawPhoto();fit();}
let anchorEdit=-1,anchorDrag=null,anchorHover=null;
function selectAnchor(index){anchorEdit=index;const a=state.anchors[index];if(a){$('anchorLength').value=a.marker_length||80;$('anchorAngle').value=a.marker_angle||0;}$('anchorEditing').textContent=a?'Editing reference '+(index+1)+' - '+a.designator:'Size and angle for the next reference';drawPhoto();}
for(const id of ['anchorLength','anchorAngle'])$(id).oninput=()=>{const length=Number($('anchorLength').value),angle=Number($('anchorAngle').value);if(!Number.isFinite(length)||length<10||length>1000||!Number.isFinite(angle)||angle< -180||angle>180)return;const a=state.anchors[anchorEdit];if(a){a.marker_length=length;a.marker_angle=angle;drawPhoto();}};
function photoPoint(event){const rect=$('photo').getBoundingClientRect(),[w,h]=orientedSize();return [(event.clientX-rect.left)*w/rect.width,(event.clientY-rect.top)*h/rect.height];}
$('photo').addEventListener('pointerdown',event=>{
 if(state.busy||!state.image||event.button!==0)return;
 const [vx,vy]=photoPoint(event);let index=-1;
 for(let i=state.anchors.length-1;i>=0;i--){const a=state.anchors[i],[cx,cy]=rawToView(a.x,a.y),angle=-(a.marker_angle||0)*Math.PI/180,dx=vx-cx,dy=vy-cy,l=a.marker_length||80,pad=5/state.scale;if(Math.abs(dx*Math.cos(angle)-dy*Math.sin(angle))<=l/2+pad&&Math.abs(dx*Math.sin(angle)+dy*Math.cos(angle))<=l*3/11+pad){index=i;break;}}
 if(index<0){if(state.anchors.length>=4)return;const [x,y]=viewToRaw(vx,vy);if(x<0||y<0||x>state.capture.width||y>state.capture.height)return;index=state.anchors.length;state.anchors.push({designator:state.panel.anchor_ids[index],x,y,marker_length:Number($('anchorLength').value)||80,marker_angle:Number($('anchorAngle').value)||0});invalidate();message(state.anchors.length===4?'Four rectangles placed. Adjust them, then click Show all 20 components.':'Place the next reference rectangle.');}
 selectAnchor(index);const [cx,cy]=rawToView(state.anchors[index].x,state.anchors[index].y);anchorDrag={index,dx:vx-cx,dy:vy-cy,pointer:event.pointerId};$('photo').setPointerCapture(event.pointerId);event.preventDefault();
});
$('photo').addEventListener('pointermove',event=>{if(state.busy||!state.image)return;if(!anchorDrag){anchorHover=photoPoint(event);drawPhoto();return;}if(event.pointerId!==anchorDrag.pointer)return;const [vx,vy]=photoPoint(event),[x,y]=viewToRaw(vx-anchorDrag.dx,vy-anchorDrag.dy),a=state.anchors[anchorDrag.index];if(!a)return;a.x=Math.max(0,Math.min(state.capture.width,x));a.y=Math.max(0,Math.min(state.capture.height,y));invalidate();});
function endAnchorDrag(event){if(!anchorDrag||event.pointerId!==anchorDrag.pointer)return;anchorDrag=null;if($('photo').hasPointerCapture(event.pointerId))$('photo').releasePointerCapture(event.pointerId);}
$('photo').addEventListener('pointerleave',()=>{anchorHover=null;drawPhoto();});
$('photo').addEventListener('pointerup',endAnchorDrag);$('photo').addEventListener('pointercancel',endAnchorDrag);
$('capture').onclick=()=>action(async()=>{message('Taking a fresh frame from the phone...');const result=await api('/api/capture',{});state.anchors=[];selectAnchor(-1);invalidate();await loadImage(result.capture);message('Photo ready. Select reference 1 on the image.','success');});
$('calculate').onclick=()=>action(async()=>{const result=await api('/api/register',{capture_id:state.capture.request_id,anchors:state.anchors,roi_width_mm:Number($('roiWidth').value),roi_height_mm:Number($('roiHeight').value)});state.registration=result.registration;$('review').disabled=false;$('review').checked=false;drawPhoto();message('All 20 positions are shown. Check the middle components too.','success');});
$('apply').textContent='Send to phone and start reading';
$('review').parentElement.previousElementSibling.textContent='Cyan outlines show ferrite bodies; dashed outlines are estimates. Read values appear in green below each component.';
$('apply').onclick=()=>action(async()=>{await api('/api/apply',{calibration_id:state.registration.calibration_id,capture_id:state.capture.request_id});message(state.registration.body_outline_only?'Outline preview is active.':'Reading started. Successful values will appear below the components.','success');await device();});
$('clear').onclick=()=>action(async()=>{await api('/api/clear',{});message('Alignment cleared on the phone.','success');await device();});
$('review').onchange=controls;$('roiWidth').oninput=invalidate;$('roiHeight').oninput=invalidate;
$('undo').onclick=()=>{state.anchors.pop();selectAnchor(-1);invalidate();controls();drawPhoto();};$('reset').onclick=()=>{state.anchors=[];selectAnchor(-1);invalidate();controls();drawPhoto();};$('rotatePlan').onclick=()=>{state.planRotation=(state.planRotation+1)%4;drawPlan();};
$('zoomIn').onclick=()=>{state.scale=Math.min(5,state.scale*1.3);drawPhoto();};$('zoomOut').onclick=()=>{state.scale=Math.max(.1,state.scale/1.3);drawPhoto();};$('fit').onclick=()=>{fit();drawPhoto();};
let polling=false;
async function device(){
 if(polling)return;polling=true;
 try{
  const result=await api('/api/device');
  const exposure=result.app?.exposure;
  if(exposure){
   if(!state.busy)renderTorch(exposure);
   if(!exposureLoaded&&exposure.iso){$('cameraIso').value=exposure.iso;$('shutterMs').value=exposure.exposure_ms;$('retryIso50').checked=exposure.retry_iso50;exposureLoaded=true;shutterHint();}
   $('exposureActual').textContent='Requested: ISO '+(exposure.iso??'?')+' / '+(exposure.exposure_ms??'?')+' ms. Active: ISO '+(exposure.actual_iso??exposure.active_iso??'?')+' / '+(exposure.actual_exposure_ms??'?')+' ms';
   if(exposure.iso_min){$('cameraIso').min=exposure.iso_min;$('cameraIso').max=exposure.iso_max;$('shutterMs').min=exposure.exposure_min_ms;$('shutterMs').max=exposure.exposure_max_ms;$('exposureRange').textContent='Camera limits: ISO '+exposure.iso_min+'–'+exposure.iso_max+'; shutter '+exposure.exposure_min_ms+'–'+exposure.exposure_max_ms+' ms.';}
  }
  $('deviceDot').className=result.connected?'ok':'';
  $('deviceText').textContent=result.connected?(result.app.active?'Phone connected - alignment active':'Phone connected - USB'):'Phone or app unavailable';
  if(result.results?.capture_id===state.capture?.request_id){state.readResults={...result.results.values};drawPhoto();}
  if(result.app?.state==='moved')message('Panel moved. Take a fresh photo to align it again.','error');
 }catch(e){$('deviceText').textContent='Local service unavailable';}finally{polling=false;}
}
function drawReadResults(ctx,font){
 const parts=state.registration?(state.registration.all_parts||state.registration.parts):[];
 let count=0;
 for(const part of parts){
  const value=state.readResults[part.designator];if(typeof value!=='string')continue;count++;
  const points=(part.body_quad_normalized||part.quad_normalized).map(([x,y])=>rawToView(x*state.capture.width,y*state.capture.height));
  const x=points.reduce((sum,p)=>sum+p[0],0)/4,bottom=Math.max(...points.map(p=>p[1]));
  const size=Math.max(font,14/Math.max(.25,state.scale));
  ctx.save();ctx.font='bold '+size+'px monospace';ctx.textAlign='center';ctx.textBaseline='top';
  const lines=value.split(/\r?\n/);let y=bottom+5/state.scale;
  for(const line of lines){const width=ctx.measureText(line).width,pad=3/state.scale;ctx.fillStyle='#092d21';ctx.fillRect(x-width/2-pad,y-pad,width+2*pad,size+2*pad);ctx.fillStyle='#68ff84';ctx.fillText(line,x,y);y+=size*1.25;}
  ctx.restore();
 }
 $('readCount').textContent=count+' / '+(state.panel?.count||20)+' read';
}
async function init(){try{const result=await api('/api/state');state.panel=result.panel;$('fileInfo').textContent=state.panel.source_file+' · '+state.panel.count+' CORE1 · '+state.panel.columns+' × '+state.panel.rows+' · mm';if(result.capture){await loadImage(result.capture);message('Saved reference loaded. Take a new photo for a new alignment.');}if(result.registration&&result.capture&&result.registration.capture_id===result.capture.request_id){state.registration=result.registration;$('roiWidth').value=result.registration.roi_width_mm;$('roiHeight').value=result.registration.roi_height_mm;state.anchors=result.registration.anchors.map(a=>({...a}));$('review').disabled=false;$('review').checked=false;drawPhoto();}controls();await device();}catch(e){message(e.message,'error');}}
init();setInterval(()=>{if(!state.busy)device();},2000);
