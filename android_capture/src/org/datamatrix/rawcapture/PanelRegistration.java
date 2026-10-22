package org.datamatrix.rawcapture;

import android.graphics.*;
import android.media.Image;
import android.os.SystemClock;
import android.view.View;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local USB file protocol. All commands are data; no remote paths or code are executed. */
final class PanelRegistration {
    final MainActivity owner;
    final String sessionId=java.util.UUID.randomUUID().toString();
    final ExecutorService io=Executors.newSingleThreadExecutor();
    final AtomicBoolean saving=new AtomicBoolean();
    volatile JSONObject configuration;
    volatile boolean holding;
    JSONObject captured;
    int[] reference;
    int[] latestMotion;
    double lastMotionScore;
    long lastPoll;
    String lastRequest="";
    UnreadCycle readLoop;JSONArray loopParts;int loopTicket;boolean advancingLoop;
    final java.util.Map<String,String> decodedLabels=new java.util.concurrent.ConcurrentHashMap<>();
    String resultsCaptureId="";
    int bodyFailures;
    void stopAutoLoop(){loopTicket++;readLoop=null;loopParts=null;}
    void startAutoLoop(JSONObject config){
        try{stopAutoLoop();loopParts=config.optJSONArray("all_parts");if(loopParts==null)loopParts=config.getJSONArray("parts");readLoop=new UnreadCycle(loopParts.length());
            resultsCaptureId=config.optString("capture_id");
            JSONObject prior=config.optJSONObject("read_results");if(prior!=null)for(int i=0;i<loopParts.length();i++){String name=loopParts.getJSONObject(i).getString("designator");if(prior.has(name)&&!prior.optString(name).isEmpty()){readLoop.read[i]=true;decodedLabels.put(name,prior.getString(name));}}
            advanceAutoLoop();}
        catch(Exception e){stopAutoLoop();owner.boundaryStatus.setText("Review unavailable: "+e.getMessage());}
    }
    void advanceAutoLoop(){
        if(readLoop==null||configuration==null||owner.closing)return;
        int next=readLoop.next(SystemClock.elapsedRealtime());int ticket=++loopTicket;
        bodyFailures=0;
        if(next<0){owner.liveEpoch++;owner.liveEnabled=false;owner.setDisplayZoom(1f);owner.boundaryStatus.setText("All components read");reply(lastRequest,true,"all_read",null);return;}
        try{
            JSONObject part=loopParts.getJSONObject(next);JSONArray c=part.getJSONArray("center_normalized");
            advancingLoop=true;owner.setDisplayZoom(1f);
            double[] v=owner.mapping.toView(c.getDouble(0)*owner.previewSize.getWidth(),c.getDouble(1)*owner.previewSize.getHeight());
            owner.tap((float)v[0],(float)v[1]);
            owner.boundaryStatus.setText("Reading "+part.getString("designator")+" - "+readLoop.remaining()+" remaining");
            android.util.Log.i("PanelReadLoop","visit "+part.getString("designator")+" remaining="+readLoop.remaining()+" deadline="+readLoop.deadline);
            reply(lastRequest,true,"auto_reading",null);
            // No timeout: keep analyzing this component until a valid decode.
        }catch(Exception e){stopAutoLoop();owner.boundaryStatus.setText("Review error: "+e.getMessage());}
        finally{advancingLoop=false;}
    }
    void onDecoded(String name,String text){
        if(configuration==null||name.isEmpty()||text.isEmpty())return;
        boolean changed=!text.equals(decodedLabels.put(name,text));owner.panelMark.invalidate();
        if(readLoop==null){if(changed)reply(lastRequest,true,"read_success",null);return;}
        if(readLoop==null||loopParts==null||readLoop.current<0)return;
        JSONObject current=loopParts.optJSONObject(readLoop.current);
        if(current==null||!name.equals(current.optString("designator")))return;
        if(readLoop.success(readLoop.current)){decodedLabels.put(name,text);owner.panelMark.invalidate();android.util.Log.i("PanelReadLoop","read "+name+" text="+text);advanceAutoLoop();}
    }
    void onAttemptFinished(String name,int epoch,boolean codeSearched,boolean gridBuilt){
        if(epoch!=owner.liveEpoch||owner.closing||!owner.liveEnabled||readLoop==null||loopParts==null||readLoop.current<0)return;
        if(!name.equals(loopParts.optJSONObject(readLoop.current).optString("designator")))return;
        boolean skip;
        if(codeSearched){bodyFailures=0;skip=readLoop.failed(readLoop.current);android.util.Log.i("PanelReadLoop","code boundary revision failed "+name+" revision="+readLoop.failedAttempts+" reason="+(gridBuilt?"decode_failed":"grid_not_built"));}
        else{skip=++bodyFailures>=3;android.util.Log.i("PanelReadLoop","body refinement failed "+name+" count="+bodyFailures);}
        owner.boundaryMark.clear();owner.boundaryStatus.setText("Refining code boundary again: "+name);
        if(skip)advanceAutoLoop();
    }

    PanelRegistration(MainActivity owner){this.owner=owner;reply("",true,"ready",null);}
    File file(String name){return new File(owner.getFilesDir(),name);}
    void atomicJson(String name,JSONObject value)throws Exception{
        File temp=file(name+".tmp");owner.writeJson(temp,value);
        if(!temp.renameTo(file(name)))throw new IOException("Cannot publish "+name);
    }
    void reply(String id,boolean ok,String state,String error){try{
        JSONObject value=new JSONObject().put("request_id",id).put("ok",ok).put("state",state)
            .put("session_id",sessionId).put("active",configuration!=null).put("holding",holding)
            .put("capture_id",captured==null?"":captured.optString("request_id"))
            .put("elapsed_ms",SystemClock.elapsedRealtime());
        value.put("motion_metric","median_local_normalized_correlation").put("motion_score",lastMotionScore);
        value.put("auto_loop",readLoop!=null).put("unread_count",readLoop==null?JSONObject.NULL:readLoop.remaining());
        value.put("results_capture_id",resultsCaptureId).put("read_results",new JSONObject(decodedLabels));
        if(configuration!=null){JSONArray active=configuration.optJSONArray("parts");if(active!=null&&active.length()>0)value.put("selected_component",active.getJSONObject(0).optString("designator"));value.put("manual_selection",configuration.optBoolean("manual_selection",true)).put("reading_enabled",owner.liveEnabled);}
        if(error!=null)value.put("error",error);
        atomicJson("registration_status.json",value);
    }catch(Exception e){android.util.Log.e("PanelRegistration","status",e);}}
    JSONObject read(File path)throws Exception{
        if(path.length()>200000)throw new IOException("Command too large");
        try(FileInputStream input=new FileInputStream(path);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1)out.write(buffer,0,n);
            return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
        }
    }
    void reset(boolean hold){configuration=null;holding=hold;owner.liveEpoch++;owner.cancelCycleFocus();owner.ui.post(()->{stopAutoLoop();decodedLabels.clear();owner.boundaryMark.clearAll();owner.panelMark.invalidate();});}
    void poll(Image image,int[] signature){
        Image.Plane motionPlane=image.getPlanes()[0];
        latestMotion=PanelMotion.signature(motionPlane.getBuffer(),image.getWidth(),image.getHeight(),motionPlane.getRowStride(),motionPlane.getPixelStride());
        lastMotionScore=reference==null?0:PanelMotion.difference(reference,latestMotion);
        if(configuration!=null&&!configuration.optBoolean("manual_alignment",true)&&!owner.cycleFocusWaiting&&image.getTimestamp()>owner.cycleFreshAfterNs&&lastMotionScore>PanelMotion.OCCLUSION_LIMIT){
            android.util.Log.i("PanelRegistration","motion score="+lastMotionScore);
            reset(true);reply(lastRequest,true,"moved",null);
            owner.ui.post(()->owner.boundaryStatus.setText("Panel moved - take a new registration photo"));
        }
        long now=SystemClock.elapsedRealtime();if(now-lastPoll<250||saving.get())return;lastPoll=now;
        File command=file("registration_command.json");if(!command.exists())return;
        String id="";
        try{
            JSONObject request=read(command);id=request.getString("request_id");
            if(!id.matches("[a-f0-9]{32}"))throw new IOException("Invalid request id");
            if(!command.delete())throw new IOException("Cannot consume command");lastRequest=id;
            String operation=request.getString("operation");
            if(operation.equals("capture")){capture(id,image,signature);return;}
            if(operation.equals("inspect")){snapshot(id,image,signature,true);return;}
            if(operation.equals("analysis")){
                JSONObject options=request.getJSONObject("payload");boolean raw=options.getBoolean("raw");int channels=options.getInt("channels");
                if(channels<1||channels>15||raw&&owner.yuvMode)throw new IOException("Unsupported analysis selection");
                final String selectionId=id;
                owner.ui.post(()->{owner.selectAnalysisSource(raw);owner.channelMask=channels;owner.liveEpoch++;owner.boundaryMark.clear();owner.refreshChannelButtons();reply(selectionId,true,"analysis_selected",null);});return;
            }
            if(operation.equals("clear")){reset(false);reference=null;reply(id,true,"cleared",null);owner.ui.post(()->owner.boundaryStatus.setText("Free localization"));return;}
            if(!operation.equals("apply"))throw new IOException("Unknown operation");
            JSONObject config=request.getJSONObject("payload");
            config.put("manual_alignment",config.optBoolean("manual_alignment",true));
            restoreReference(config.getString("capture_id"));
            if(config.getInt("protocol_version")!=1||config.getInt("width")!=image.getWidth()||config.getInt("height")!=image.getHeight()||!owner.cameraId.equals(config.getString("camera_id"))||!"YUV".equals(config.getString("source")))throw new IOException("Camera geometry changed - take a new photo");
            lastMotionScore=PanelMotion.difference(reference,latestMotion);
            if(!config.optBoolean("manual_alignment")&&lastMotionScore>PanelMotion.OCCLUSION_LIMIT)throw new IOException("Reference view is obscured or different - take a new photo");
            JSONArray parts=config.getJSONArray("parts");if(parts.length()==0||parts.length()>500)throw new IOException("Invalid panel data");
            for(int i=0;i<parts.length();i++){
                JSONObject part=parts.getJSONObject(i);JSONArray center=part.getJSONArray("center_normalized"),q=part.getJSONArray("quad_normalized");
                if(q.length()!=4||center.length()!=2)throw new IOException("Invalid panel coordinates");
                for(int j=0;j<2;j++){double v=center.getDouble(j);if(!Double.isFinite(v)||v<0||v>1)throw new IOException("Center outside image");}
                for(int j=0;j<4;j++)for(int k=0;k<2;k++){double v=q.getJSONArray(j).getDouble(k);if(!Double.isFinite(v)||v<-.5||v>1.5)throw new IOException("Invalid search region");}
            }
            JSONArray available=config.optJSONArray("all_parts");if(available==null)available=parts;
            if(config.optBoolean("auto_cycle",false)){
                // Opt-in: keep every component and let CodeFocusScheduler visit them in turn (no touch needed).
                config.put("all_parts",available).put("parts",new JSONArray().put(available.getJSONObject(0))).put("manual_selection",true);
                android.util.Log.i("CodeFocus","auto cycle over "+available.length()+" components");
            }else{
            String preferred=owner.getSharedPreferences("panel_selection",0).getString("component",parts.getJSONObject(0).optString("designator"));
            for(int i=0;i<available.length();i++)if(preferred.equals(available.getJSONObject(i).optString("designator"))){config.put("all_parts",available).put("parts",new JSONArray().put(available.getJSONObject(i)));break;}
            config.put("manual_selection",true);
            }
            reset(false);configuration=config;owner.liveEnabled=!config.optBoolean("body_outline_only",false);
            reply(id,true,"applied",null);
            owner.ui.post(()->{resultsCaptureId=config.optString("capture_id");owner.refreshSourceButtons();owner.panelMark.invalidate();owner.boundaryStatus.setText("Panel registered: "+parts.length()+" component locations");});
            if(config.optBoolean("auto_cycle",false))owner.ui.post(()->startAutoLoop(config));
        }catch(Exception e){if(command.exists())command.delete();reply(id,false,"error",e.getMessage());}
    }
    void capture(String id,Image image,int[] signature)throws Exception{
        snapshot(id,image,signature,false);
    }
    void snapshot(String id,Image image,int[] signature,boolean inspect)throws Exception{
        if(!inspect){reset(true);captured=null;reference=latestMotion.clone();}
        final String prefix=inspect?"registration_inspect":"registration_frame";
        Image.Plane plane=image.getPlanes()[0];int width=image.getWidth(),height=image.getHeight();
        byte[] y=YuvData.pack(plane.getBuffer(),width,height,plane.getRowStride(),plane.getPixelStride());
        int rotation=(owner.characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION)-owner.getWindowManager().getDefaultDisplay().getRotation()*90+360)%360;
        JSONObject metadata=new JSONObject().put("request_id",id).put("session_id",sessionId).put("width",width).put("height",height)
            .put("camera_id",owner.cameraId).put("rotation_degrees",rotation).put("source","YUV_Y8")
            .put("image_timestamp_ns",image.getTimestamp()).put("scene_signature",new JSONArray(signature))
            .put("motion_signature",new JSONArray(latestMotion))
            .put("pixel_processing","Packed native Y samples; lossless grayscale PNG; no resize or display zoom");
        saving.set(true);final int captureEpoch=owner.liveEpoch;
        io.execute(()->{try{
            File temp=file(prefix+".png.tmp");try(OutputStream out=new FileOutputStream(temp)){YuvData.grayPng(out,y,width,height);}
            if(owner.closing||owner.liveEpoch!=captureEpoch)throw new IOException("Camera state changed - take a new photo");
            if(!temp.renameTo(file(prefix+".png")))throw new IOException("Cannot publish camera image");
            atomicJson(prefix+".json",metadata);if(!inspect)captured=metadata;reply(id,true,inspect?"inspected":"captured",null);
            if(!inspect)owner.ui.post(()->owner.boundaryStatus.setText("Photo ready - align four reference parts in the browser"));
        }catch(Exception e){reply(id,false,"error",e.getMessage());}finally{saving.set(false);}});
    }
    void restoreReference(String id)throws Exception{
        if(captured!=null&&id.equals(captured.optString("request_id"))&&reference!=null)return;
        JSONObject metadata=read(file("registration_frame.json"));
        if(!id.equals(metadata.getString("request_id")))throw new IOException("Reference photo changed - take a new photo");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        Bitmap bitmap=BitmapFactory.decodeFile(file("registration_frame.png").getAbsolutePath(),options);
        if(bitmap==null)throw new IOException("Reference photo is unavailable");
        try{
            int w=metadata.getInt("width"),h=metadata.getInt("height");
            if(bitmap.getWidth()!=w||bitmap.getHeight()!=h)throw new IOException("Reference photo geometry mismatch");
            int[] pixels=new int[w*h];bitmap.getPixels(pixels,0,w,0,0,w,h);byte[] y=new byte[pixels.length];
            for(int i=0;i<y.length;i++)y[i]=(byte)(pixels[i]&255);
            reference=PanelMotion.signature(java.nio.ByteBuffer.wrap(y),w,h,w,1);captured=metadata;
        }finally{bitmap.recycle();}
    }
    void poseMoved(int epoch){
        if(configuration!=null&&configuration.optBoolean("manual_alignment",true))return;
        if(epoch!=owner.liveEpoch||configuration==null)return;
        reset(true);reply(lastRequest,true,"moved",null);
        owner.ui.post(()->owner.boundaryStatus.setText("Panel position changed - take a new registration photo"));
    }
    void cameraClosed(){
        boolean needsPhoto=captured!=null||configuration!=null||holding||saving.get();
        configuration=null;captured=null;reference=null;
        if(needsPhoto){holding=true;reply(lastRequest,true,"camera_closed",null);}
    }
    ArrayList<float[]> quads(JSONObject config)throws JSONException{
        ArrayList<float[]> result=new ArrayList<>();JSONArray parts=config.getJSONArray("parts");
        for(int i=0;i<parts.length();i++){JSONObject part=parts.getJSONObject(i);JSONArray q=part.optJSONArray("focus_quad_normalized");if(q==null)q=part.getJSONArray("quad_normalized");float[] p=new float[8];for(int j=0;j<4;j++){p[2*j]=(float)q.getJSONArray(j).getDouble(0);p[2*j+1]=(float)q.getJSONArray(j).getDouble(1);}result.add(p);}return result;
    }
    double[] selectAt(double x,double y){
        try{
            JSONObject current=configuration;if(current==null)return null;
            JSONArray all=current.optJSONArray("all_parts");if(all==null)all=current.getJSONArray("parts");
            double[][] body=new double[all.length()][],coarse=new double[all.length()][];
            for(int i=0;i<all.length();i++){
                JSONObject part=all.getJSONObject(i);
                for(int kind=0;kind<2;kind++){JSONArray q=part.optJSONArray(kind==0?"body_quad_normalized":"quad_normalized");if(q==null)continue;double[] points=new double[8];for(int j=0;j<4;j++){points[j*2]=q.getJSONArray(j).getDouble(0);points[j*2+1]=q.getJSONArray(j).getDouble(1);}if(kind==0)body[i]=points;else coarse[i]=points;}
            }
            int index=ComponentSelection.pick(x,y,body);if(index<0)index=ComponentSelection.pick(x,y,coarse);
            if(index<0){owner.boundaryStatus.setText("Tap inside a component frame");return null;}
            if(!advancingLoop){
                if(current.optBoolean("auto_cycle",false)){
                    if(readLoop==null){loopParts=all;readLoop=new UnreadCycle(all.length());for(int i=0;i<all.length();i++)readLoop.read[i]=decodedLabels.containsKey(all.getJSONObject(i).optString("designator"));}
                    readLoop.current=index;readLoop.read[index]=false;readLoop.failedAttempts=0;bodyFailures=0;loopTicket++;
                }else stopAutoLoop();
            }
            JSONObject selected=all.getJSONObject(index);String name=selected.getString("designator");
            JSONObject next=new JSONObject(current.toString()).put("all_parts",all).put("parts",new JSONArray().put(selected)).put("manual_selection",true).put("body_outline_only",false);
            configuration=next;owner.liveEnabled=true;
            owner.getSharedPreferences("panel_selection",0).edit().putString("component",name).apply();
            owner.panelMark.invalidate();owner.boundaryStatus.setText("Reading "+name+" - tap another component to switch");
            reply(lastRequest,true,"component_selected",null);
            android.util.Log.i("CodeFocus","manual component="+name);
            JSONArray center=selected.getJSONArray("center_normalized");return new double[]{center.getDouble(0),center.getDouble(1)};
        }catch(Exception e){android.util.Log.e("PanelRegistration","touch selection",e);return null;}
    }
    void close(){io.shutdownNow();}

    static final class Mark extends View {
        final MainActivity owner;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Mark(MainActivity owner){super(owner);this.owner=owner;paint.setColor(Color.CYAN);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1);}
        @Override protected void onDraw(Canvas canvas){
            if(owner.panelRegistration==null||owner.mapping==null||owner.previewSize==null)return;
            JSONObject config=owner.panelRegistration.configuration;if(config==null)return;
            JSONArray active=config.optJSONArray("parts");JSONArray parts=config.optJSONArray("all_parts");if(parts==null)parts=active;if(parts==null)return;
            String selected=active!=null&&active.length()>0?active.optJSONObject(0).optString("designator"):"";
            for(int i=0;i<parts.length();i++){JSONObject part=parts.optJSONObject(i);if(part==null)continue;
                boolean chosen=selected.equals(part.optString("designator"));paint.setColor(chosen?Color.GREEN:Color.CYAN);paint.setStrokeWidth((chosen?2:1)*getResources().getDisplayMetrics().density);
                JSONArray quad=part.optJSONArray("body_quad_normalized");if(quad==null)quad=part.optJSONArray("quad_normalized");
                if(chosen&&owner.selectedBodyOutline!=null&&selected.equals(owner.selectedBodyId))quad=owner.selectedBodyOutline;
                JSONObject fit=part.optJSONObject("body_fit");paint.setPathEffect(fit!=null&&!fit.optBoolean("detected")?new DashPathEffect(new float[]{6,5},0):null);
                if(quad!=null&&quad.length()==4){Path path=new Path();for(int j=0;j<4;j++){JSONArray corner=quad.optJSONArray(j);if(corner==null)continue;double[] v=owner.mapping.toView(corner.optDouble(0)*owner.previewSize.getWidth(),corner.optDouble(1)*owner.previewSize.getHeight());if(j==0)path.moveTo((float)v[0],(float)v[1]);else path.lineTo((float)v[0],(float)v[1]);}path.close();canvas.drawPath(path,paint);}
                String text=owner.panelRegistration.decodedLabels.get(part.optString("designator"));
                if(text!=null&&quad!=null){double tx=0,ty=-Double.MAX_VALUE;for(int j=0;j<4;j++){JSONArray c=quad.optJSONArray(j);double[] v=owner.mapping.toView(c.optDouble(0)*owner.previewSize.getWidth(),c.optDouble(1)*owner.previewSize.getHeight());tx+=v[0]/4;ty=Math.max(ty,v[1]);}paint.setPathEffect(null);paint.setStyle(Paint.Style.FILL);paint.setColor(Color.GREEN);paint.setTextSize(16*getResources().getDisplayMetrics().scaledDensity);paint.setTextAlign(Paint.Align.CENTER);canvas.drawText(text,(float)tx,(float)ty+18*getResources().getDisplayMetrics().density,paint);paint.setStyle(Paint.Style.STROKE);}
                JSONArray center=part.optJSONArray("center_normalized");if(center==null)continue;
                double[] p=owner.mapping.toView(center.optDouble(0)*owner.previewSize.getWidth(),center.optDouble(1)*owner.previewSize.getHeight());float x=(float)p[0],y=(float)p[1];canvas.drawCircle(x,y,5,paint);canvas.drawLine(x-8,y,x+8,y,paint);canvas.drawLine(x,y-8,x,y+8,paint);
            }
        }
    }
}
