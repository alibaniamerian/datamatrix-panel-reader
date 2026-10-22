'use strict';
const $=id=>document.getElementById(id);
const state={panel:null,capture:null,registration:null,anchors:[],image:null,scale:1,planRotation:0,busy:false,readResults:{}};
let lastPhoneState='';
const fa=n=>Number(n).toLocaleString('en-US');
function message(text,kind=''){ $('message').textContent=text;$('message').className=kind; }
async function api(path,body){const response=await fetch(path,body===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});const result=await response.json();if(!response.ok)throw Error(result.error||'Request failed.');return result;}
async function action(fn){if(state.busy)return;state.busy=true;controls();try{await fn();}catch(e){message(e.message,'error');}finally{state.busy=false;controls();}}
function invalidate(){state.registration=null;$('review').checked=false;$('review').disabled=true;controls();drawPhoto();}
function controls(){const complete=state.anchors.length===4;$('capture').disabled=state.busy;$('capture').textContent=state.busy?'Working...':'Take photo from phone';$('calculate').disabled=state.busy||!complete;$('undo').disabled=state.busy||!state.anchors.length;$('reset').disabled=state.busy||!state.anchors.length;$('apply').disabled=state.busy||!state.registration||!$('review').checked;$('pointCount').textContent=fa(state.anchors.length)+' of 4 points';$('referenceTitle').textContent=complete?'All four centers selected':state.panel?'Reference '+fa(state.anchors.length+1)+' · '+state.panel.anchor_ids[state.anchors.length]:'Select the component center';renderReferences();drawPlan();}
function renderReferences(){if(!state.panel)return;$('anchorList').replaceChildren();state.panel.anchor_ids.forEach((name,i)=>{const li=document.createElement('li');li.className=i<state.anchors.length?'done':i===state.anchors.length?'active':'';const badge=document.createElement('b');badge.textContent=fa(i+1);const code=document.createElement('code');code.textContent=name;const text=document.createElement('span');text.textContent=i<state.anchors.length?'Selected':i===state.anchors.length?'Click on the photo':'';li.append(badge,code,text);$('anchorList').append(li);});}
function planPoint(part){let x=(part.x_mm-53.1)/81,y=(72.975-part.y_mm)/108;for(let i=0;i<state.planRotation;i++){const t=x;x=-y;y=t;}return [145+x*198,165+y*250];}
function drawPlan(){if(!state.panel)return;const c=$('plan'),ctx=c.getContext('2d');ctx.clearRect(0,0,c.width,c.height);for(const part of state.panel.parts){const [x,y]=planPoint(part),index=state.panel.anchor_ids.indexOf(part.designator),active=index===state.anchors.length;ctx.fillStyle=active?'#087f82':index>=0?'#dbeef0':'#e8eef0';ctx.strokeStyle=active?'#087f82':'#c3d3d9';ctx.lineWidth=active?2:1;ctx.fillRect(x-15,y-11,30,22);ctx.strokeRect(x-15,y-11,30,22);if(index>=0){ctx.fillStyle=active?'white':'#176f74';ctx.font='bold 13px sans-serif';ctx.textAlign='center';ctx.fillText(fa(index+1),x,y+5);}else{ctx.beginPath();ctx.arc(x,y,2,0,Math.PI*2);ctx.fillStyle='#91abb4';ctx.fill();}}}
function orientedSize(){const {width:w,height:h,rotation_degrees:r}=state.capture;return r%180?[h,w]:[w,h];}
function rawToView(x,y){const {width:w,height:h,rotation_degrees:r}=state.capture;if(r===90)return [h-y,x];if(r===180)return [w-x,h-y];if(r===270)return [y,w-x];return [x,y];}
function viewToRaw(x,y){const {width:w,height:h,rotation_degrees:r}=state.capture;if(r===90)return [y,h-x];if(r===180)return [w-x,h-y];if(r===270)return [w-y,x];return [x,y];}
function applyZoom(){if(!state.image)return;const [w,h]=orientedSize();$('photo').style.width=w*state.scale+'px';$('photo').style.height=h*state.scale+'px';$('zoomValue').textContent=fa(Math.round(state.scale*100))+'%';}
function fit(){if(!state.image)return;const [w,h]=orientedSize(),v=$('viewport');state.scale=Math.min((v.clientWidth-40)/w,(v.clientHeight-40)/h);applyZoom();}
function drawPhoto(){if(!state.image)return;const canvas=$('photo'),ctx=canvas.getContext('2d'),[w,h]=orientedSize();canvas.width=w;canvas.height=h;ctx.save();const r=state.capture.rotation_degrees;if(r===90){ctx.translate(w,0);ctx.rotate(Math.PI/2);}else if(r===180){ctx.translate(w,h);ctx.rotate(Math.PI);}else if(r===270){ctx.translate(0,h);ctx.rotate(-Math.PI/2);}ctx.drawImage(state.image,0,0);ctx.restore();const line=2/Math.max(.2,state.scale),font=12/Math.max(.35,state.scale);if(state.registration){for(const part of (state.registration.all_parts||state.registration.parts)){ctx.setLineDash(part.body_fit&&!part.body_fit.detected?[6/state.scale,4/state.scale]:[]);const points=(part.body_quad_normalized||part.quad_normalized).map(([x,y])=>rawToView(x*state.capture.width,y*state.capture.height));ctx.beginPath();points.forEach(([x,y],i)=>i?ctx.lineTo(x,y):ctx.moveTo(x,y));ctx.closePath();ctx.strokeStyle='#28d6c2';ctx.lineWidth=line;ctx.stroke();ctx.setLineDash([]);const [x,y]=rawToView(...part.center_px);ctx.beginPath();ctx.moveTo(x-7/state.scale,y);ctx.lineTo(x+7/state.scale,y);ctx.moveTo(x,y-7/state.scale);ctx.lineTo(x,y+7/state.scale);ctx.stroke();ctx.font=font+'px monospace';ctx.textAlign='center';ctx.fillStyle='#fff';ctx.shadowColor='#000';ctx.shadowBlur=5;ctx.fillText(part.designator,x,y-12/state.scale);ctx.shadowBlur=0;}}
state.anchors.forEach((a,i)=>{const [x,y]=rawToView(a.x,a.y),radius=13/Math.max(.2,state.scale);ctx.beginPath();ctx.arc(x,y,radius,0,Math.PI*2);ctx.fillStyle='#ed914d';ctx.fill();ctx.strokeStyle='#fff';ctx.lineWidth=line;ctx.stroke();ctx.fillStyle='#172b36';ctx.font='bold '+font+'px sans-serif';ctx.textAlign='center';ctx.textBaseline='middle';ctx.fillText(fa(i+1),x,y);ctx.textBaseline='alphabetic';});drawReadResults(ctx,font);applyZoom();}
async function loadImage(capture){state.capture=capture;state.readResults={};state.image=await new Promise((resolve,reject)=>{const img=new Image();img.onload=()=>resolve(img);img.onerror=()=>reject(Error('Could not load the photo.'));img.src='/api/capture.png?id='+encodeURIComponent(capture.request_id);});$('empty').hidden=true;$('photo').hidden=false;$('imageMeta').textContent=capture.width+' × '+capture.height+' · YUV / PNG';drawPhoto();fit();}
$('photo').addEventListener('click',event=>{if(state.busy||!state.image||state.anchors.length>=4)return;const rect=$('photo').getBoundingClientRect(),[w,h]=orientedSize(),[x,y]=viewToRaw((event.clientX-rect.left)*w/rect.width,(event.clientY-rect.top)*h/rect.height);if(x<0||y<0||x>state.capture.width||y>state.capture.height)return;state.anchors.push({designator:state.panel.anchor_ids[state.anchors.length],x,y});invalidate();controls();drawPhoto();message(state.anchors.length===4?'Four anchors selected. Click Show all 20 components.':'Select the next reference shown on the diagram.');});
$('capture').onclick=()=>action(async()=>{message('Taking a fresh frame from the phone...');const result=await api('/api/capture',{});state.anchors=[];invalidate();await loadImage(result.capture);message('Photo ready. Select reference 1 on the image.','success');});
$('calculate').onclick=()=>action(async()=>{const result=await api('/api/register',{capture_id:state.capture.request_id,anchors:state.anchors,roi_width_mm:Number($('roiWidth').value),roi_height_mm:Number($('roiHeight').value)});state.registration=result.registration;$('review').disabled=false;$('review').checked=false;drawPhoto();message('All 20 positions are shown. Check the middle components too.','success');});
$('apply').textContent='Send to phone and start reading';
document.querySelector('.review').previousElementSibling.textContent='Cyan outlines show ferrite bodies; dashed outlines are estimates. Read values appear in green below each component.';
$('apply').onclick=()=>action(async()=>{await api('/api/apply',{calibration_id:state.registration.calibration_id,capture_id:state.capture.request_id});message(state.registration.body_outline_only?'Outline preview is active.':'Reading started. Successful values will appear below the components.','success');await device();});
$('clear').onclick=()=>action(async()=>{await api('/api/clear',{});message('Alignment cleared on the phone.','success');await device();});
$('review').onchange=controls;$('roiWidth').oninput=invalidate;$('roiHeight').oninput=invalidate;
$('undo').onclick=()=>{state.anchors.pop();invalidate();controls();drawPhoto();};$('reset').onclick=()=>{state.anchors=[];invalidate();controls();drawPhoto();};$('rotatePlan').onclick=()=>{state.planRotation=(state.planRotation+1)%4;drawPlan();};
$('zoomIn').onclick=()=>{state.scale=Math.min(5,state.scale*1.3);drawPhoto();};$('zoomOut').onclick=()=>{state.scale=Math.max(.1,state.scale/1.3);drawPhoto();};$('fit').onclick=()=>{fit();drawPhoto();};
let polling=false;
async function device(){
 if(polling)return;polling=true;
 try{
  const result=await api('/api/device');
  $('deviceDot').className=result.connected?'ok':'';
  $('deviceText').textContent=result.connected?(result.app.active?'Phone connected - alignment active':'Phone connected - USB'):'Phone or app unavailable';
  if(result.results?.capture_id===state.capture?.request_id){state.readResults={...state.readResults,...result.results.values};drawPhoto();}
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
