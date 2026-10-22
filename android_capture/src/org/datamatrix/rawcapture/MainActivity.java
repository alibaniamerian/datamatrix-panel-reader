package org.datamatrix.rawcapture;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.MediaStore;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.*;
import android.media.*;
import android.os.*;
import android.util.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    TextureView texture; TextView status,title; Button shutter; FrameLayout previewFrame; FocusMark mark;
    boolean yuvMode; int captureFormat=ImageFormat.RAW_SENSOR;
    String formatName(){return yuvMode?"YUV":"RAW";}
    HandlerThread thread; Handler cameraHandler; final Handler ui=new Handler(Looper.getMainLooper());
    CameraDevice camera; CameraCaptureSession session; CaptureRequest.Builder preview;
    CameraCharacteristics characteristics; ImageReader rawReader; Surface previewSurface;
    String cameraId; Size previewSize,rawSize; Rect active,crop; boolean fixedFocus;
    volatile boolean closing=true; boolean opening=false,busy=false,waitingFocus=false,rawRequested=false;
    int generation=0; long focusStartFrame=Long.MAX_VALUE; MeteringRectangle focusRegion; PreviewMapping mapping;
    final int fixedIso=100;
    final long fixedExposureNs=112500000L; long lastExposureReport=0;
    final Map<Long,Image> pendingImages=new HashMap<>();
    final Map<Long,TotalCaptureResult> pendingResults=new HashMap<>();
    Bitmap capturePreview; float lastTapX=-1,lastTapY=-1; int captureViewWidth,captureViewHeight;
    float displayZoom=1,captureDisplayZoom=1;
    double zoomCenterX=.5,zoomCenterY=.5,lastBufferX=-1,lastBufferY=-1;
    volatile double[] touchSearchRegion;
    JSONArray selectedBodyOutline;String selectedBodyId="";
    double captureZoomCenterX=.5,captureZoomCenterY=.5;
    TextView zoomLabel;
    volatile boolean rawAnalysisEnabled=false;Button yuvSourceButton,rawSourceButton;
    volatile int channelMask=1;final String[] channelNames={"FULL","R","G","B"};final java.util.ArrayList<Button> channelButtons=new java.util.ArrayList<>();
    TextView boundaryStatus; BoundaryMark boundaryMark;
    PanelRegistration panelRegistration;PanelRegistration.Mark panelMark;
    ImageReader analysisReader; Size analysisSize;
    final ExecutorService boundaryWorker=Executors.newSingleThreadExecutor();
    // Analysis-frame recording (off by default): saves exactly the frame each live analysis used.
    final ExecutorService frameSaver=Executors.newSingleThreadExecutor();
    static final int MAX_SAVED_FRAMES=300;
    volatile boolean saveFrames;final java.util.concurrent.atomic.AtomicInteger savedFrames=new java.util.concurrent.atomic.AtomicInteger();Button saveFramesButton;
    final AtomicBoolean boundaryRunning=new AtomicBoolean(false);
    volatile boolean liveEnabled=true,analysisSupported=true;
    volatile int liveEpoch=0;
    volatile long lastBoundaryStart=0;
    volatile int[] latestSceneSignature;
    int[] displayedSceneSignature;
    final Map<Long,byte[]> liveRawImages=new HashMap<>();final Map<Long,TotalCaptureResult> liveRawResults=new HashMap<>();
    boolean rawAwaiting;int rawTicket;long rawStart;int rawEpoch,rawChannels;int[] rawSignature;
    // Focus scheduling is owned by cameraHandler. Published targets are immutable.
    volatile boolean cycleFocusWaiting;
    volatile float[] cycleTarget;
    long cycleFreshAfterNs,manualFocusUntil;int cycleFocusEpoch,cycleFocusTicket;
    final CodeFocusScheduler focusScheduler=new CodeFocusScheduler();


    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets a=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(a.left,a.top,a.right,a.bottom);return insets;});
        title=new TextView(this);title.setText("DataMatrix RAW Ã‚Â· Main camera Ã‚Â· 1Ãƒâ€”");title.setTextColor(Color.WHITE);title.setPadding(20,12,20,12);root.addView(title);
        previewFrame=new FrameLayout(this);texture=new TextureView(this);mark=new FocusMark(this);
        boundaryMark=new BoundaryMark(this);panelMark=new PanelRegistration.Mark(this);panelRegistration=new PanelRegistration(this);
        previewFrame.addView(texture);previewFrame.addView(panelMark);previewFrame.addView(boundaryMark);previewFrame.addView(mark);root.addView(previewFrame,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout zoomRow=new LinearLayout(this);zoomRow.setGravity(Gravity.CENTER_VERTICAL);
        zoomLabel=new TextView(this);zoomLabel.setTextColor(Color.WHITE);zoomLabel.setText("View 1\u00d7");zoomRow.addView(zoomLabel,new LinearLayout.LayoutParams(0,-2,1));
        for(int level:new int[]{1,2,4,8}){Button button=new Button(this);button.setText(level+"\u00d7");button.setContentDescription("Preview magnification "+level+" times");button.setOnClickListener(v->setDisplayZoom(level));zoomRow.addView(button,new LinearLayout.LayoutParams(0,-2,1));}root.addView(zoomRow);
        boundaryStatus=new TextView(this);boundaryStatus.setTextColor(Color.YELLOW);boundaryStatus.setPadding(20,8,20,8);boundaryStatus.setText("Live boundary: starting (tap to pause)");root.addView(boundaryStatus);
        // Status updates must never resize the weighted camera preview or its overlays.
        boundaryStatus.setLines(1);
        boundaryStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        boundaryStatus.setOnClickListener(v->{liveEnabled=!liveEnabled;liveEpoch++;boundaryMark.clear();boundaryStatus.setText(liveEnabled?"Live boundary: waiting for frame":"Live boundary: paused (tap to resume)");});
        LinearLayout channelRow=new LinearLayout(this);
        for(int index=0;index<4;index++){final int bit=1<<index;Button button=new Button(this);button.setText(channelNames[index]);button.setTextSize(12);button.setOnClickListener(v->{int next=channelMask^bit;if(next==0)return;channelMask=next;liveEpoch++;boundaryMark.clear();refreshChannelButtons();});channelButtons.add(button);channelRow.addView(button,new LinearLayout.LayoutParams(0,(int)(40*getResources().getDisplayMetrics().density),1));}root.addView(channelRow);refreshChannelButtons();
        LinearLayout sourceRow=new LinearLayout(this);yuvSourceButton=new Button(this);rawSourceButton=new Button(this);yuvSourceButton.setText("YUV (fast)");rawSourceButton.setText("RAW (native)");for(Button button:new Button[]{yuvSourceButton,rawSourceButton}){button.setTextSize(11);sourceRow.addView(button,new LinearLayout.LayoutParams(0,(int)(36*getResources().getDisplayMetrics().density),1));}yuvSourceButton.setOnClickListener(v->selectAnalysisSource(false));rawSourceButton.setOnClickListener(v->selectAnalysisSource(true));saveFramesButton=new Button(this);saveFramesButton.setTextSize(11);saveFramesButton.setOnClickListener(v->{saveFrames=!saveFrames;if(saveFrames)savedFrames.set(0);refreshSaveButton();});sourceRow.addView(saveFramesButton,new LinearLayout.LayoutParams(0,(int)(36*getResources().getDisplayMetrics().density),1));refreshSaveButton();root.addView(sourceRow);refreshSourceButtons();

        status=new TextView(this);
        shutter=new Button(this);shutter.setEnabled(false);setContentView(root);
        shutter.setOnClickListener(v->{if(cameraHandler!=null&&!busy){capturePreview=texture.getBitmap();captureViewWidth=texture.getWidth();captureViewHeight=texture.getHeight();captureDisplayZoom=displayZoom;captureZoomCenterX=zoomCenterX;captureZoomCenterY=zoomCenterY;cameraHandler.post(this::beginCapture);}});
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
            public void onSurfaceTextureAvailable(SurfaceTexture t,int w,int h){openIfReady();}
            public void onSurfaceTextureSizeChanged(SurfaceTexture t,int w,int h){configureTransform();}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture t){return true;}
            public void onSurfaceTextureUpdated(SurfaceTexture t){}
        });
        texture.setOnTouchListener((v,event)->{if(event.getAction()==android.view.MotionEvent.ACTION_UP){tap(event.getX(),event.getY());v.performClick();}return true;});
        String fixture=getIntent().getStringExtra("boundary_test_path");
        if(fixture!=null&&(getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0){boundaryWorker.execute(()->{try{JSONObject r=BoundaryEngine.test(this,fixture);writeJson(new File(getFilesDir(),"boundary_selftest.json"),r);android.util.Log.i("LiveBoundary","SELFTEST "+r);}catch(Exception e){android.util.Log.e("LiveBoundary","selftest",e);}});}
    }
    void message(String text){ui.post(()->status.setText(text));}
    void ready(boolean enabled){ui.post(()->shutter.setEnabled(enabled));}
    @Override protected void onResume(){super.onResume();closing=false;thread=new HandlerThread("raw-camera");thread.start();cameraHandler=new Handler(thread.getLooper());openIfReady();}
    @Override protected void onPause(){closing=true;ready(false);Handler h=cameraHandler;if(h!=null){h.post(this::closeCamera);thread.quitSafely();try{thread.join();}catch(InterruptedException e){Thread.currentThread().interrupt();}}cameraHandler=null;super.onPause();}
    @Override protected void onDestroy(){if(panelRegistration!=null)panelRegistration.close();boundaryWorker.shutdownNow();super.onDestroy();}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==1&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)openIfReady();else message("Camera permission is required.");}
    void openIfReady(){if(closing||!texture.isAvailable()||cameraHandler==null)return;if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CAMERA},1);return;}cameraHandler.post(this::openCamera);}
    boolean has(int[] values,int target){if(values!=null)for(int v:values)if(v==target)return true;return false;}
    static long area(Size s){return (long)s.getWidth()*s.getHeight();}
    File captureDirectory(){File dir=new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES),"captures");if(!dir.isDirectory()&&!dir.mkdirs())throw new IllegalStateException("Cannot create capture directory");return dir;}
    void writeJson(File path,JSONObject data)throws Exception{try(Writer writer=new OutputStreamWriter(new FileOutputStream(path),"UTF-8")){writer.write(data.toString(2));}}
    JSONArray integers(int[] values){JSONArray a=new JSONArray();if(values!=null)for(int v:values)a.put(v);return a;}
    JSONArray sizes(Size[] values){JSONArray a=new JSONArray();if(values!=null)for(Size v:values)a.put(v.toString());return a;}
    void openCamera(){
        if(closing||opening||camera!=null)return;opening=true;
        try{
            CameraManager manager=(CameraManager)getSystemService(CAMERA_SERVICE);double best=Double.MAX_VALUE;JSONArray devices=new JSONArray();
            for(String id:manager.getCameraIdList()){
                CameraCharacteristics c=manager.getCameraCharacteristics(id);JSONObject info=new JSONObject();
                info.put("id",id).put("facing",c.get(CameraCharacteristics.LENS_FACING)).put("capabilities",integers(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES))).put("af_modes",integers(c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES))).put("max_af_regions",c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF));
                StreamConfigurationMap streams=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                info.put("raw_sizes",sizes(streams==null?null:streams.getOutputSizes(ImageFormat.RAW_SENSOR)));
                info.put("yuv_sizes",sizes(streams==null?null:streams.getOutputSizes(ImageFormat.YUV_420_888)));
                float[] focals=c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);android.util.SizeF sensor=c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                JSONArray fs=new JSONArray();if(focals!=null)for(float f:focals)fs.put(f);info.put("focal_lengths_mm",fs).put("sensor_size_mm",sensor==null?JSONObject.NULL:sensor.toString());devices.put(info);
                if(Objects.equals(c.get(CameraCharacteristics.LENS_FACING),CameraCharacteristics.LENS_FACING_BACK)&&focals!=null&&focals.length>0&&sensor!=null){
                    double equivalent=36.*focals[0]/sensor.getWidth();double score=Math.abs(Math.log(equivalent/26.));
                    if(score<best){best=score;cameraId=id;characteristics=c;}
                }
            }
            if(cameraId==null)throw new IllegalStateException("No main rear camera exposed.");
            JSONObject report=new JSONObject().put("model",Build.MODEL).put("android_release",Build.VERSION.RELEASE).put("sdk",Build.VERSION.SDK_INT).put("cameras",devices).put("selected_main_id",cameraId).put("selection","Rear camera nearest 26mm-equivalent field of view; no telephoto fallback.");
            writeJson(new File(getFilesDir(),"device_capabilities.json"),report);
            StreamConfigurationMap streams=characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] rawOptions=streams.getOutputSizes(ImageFormat.RAW_SENSOR);
            yuvMode=!has(characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)||rawOptions==null||rawOptions.length==0;
            captureFormat=yuvMode?ImageFormat.YUV_420_888:ImageFormat.RAW_SENSOR;ui.post(()->{rawSourceButton.setEnabled(!yuvMode);if(yuvMode&&rawAnalysisEnabled)selectAnalysisSource(false);});
            Size[] captureSizes=streams.getOutputSizes(captureFormat);
            if(captureSizes==null||captureSizes.length==0)throw new IllegalStateException("Neither RAW nor YUV output available.");
            rawSize=Collections.max(Arrays.asList(captureSizes),Comparator.comparingLong(MainActivity::area));
            ui.post(()->{title.setText("DataMatrix Ã‚Â· "+formatName()+" Ã‚Â· Main camera Ã‚Â· 1Ãƒâ€”");shutter.setText(yuvMode?"Capture YUV / PNG":"Capture RAW / DNG");});
            Size[] previews=streams.getOutputSizes(SurfaceTexture.class);double ratio=(double)rawSize.getWidth()/rawSize.getHeight();
            List<Size> allowed=new ArrayList<>();for(Size s:previews)if(s.getWidth()<=1920&&s.getHeight()<=1440&&Math.abs((double)s.getWidth()/s.getHeight()-ratio)<.02)allowed.add(s);
            if(allowed.isEmpty())throw new IllegalStateException("No compatible preview stream.");previewSize=Collections.max(allowed,Comparator.comparingLong(MainActivity::area));
            List<Size> analysisOptions=new ArrayList<>();for(Size size:streams.getOutputSizes(ImageFormat.YUV_420_888))if(size.getWidth()<=1920&&Math.abs((double)size.getWidth()/size.getHeight()-ratio)<.02)analysisOptions.add(size);
            analysisSize=analysisOptions.isEmpty()?null:Collections.max(analysisOptions,Comparator.comparingLong(MainActivity::area));
            active=characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);crop=new Rect(active);
            Float minimum=characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);fixedFocus=minimum==null||minimum==0;
            if(!fixedFocus&&!has(characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),CaptureRequest.CONTROL_AF_MODE_AUTO))throw new IllegalStateException("Single autofocus unavailable.");
            rawReader=ImageReader.newInstance(rawSize.getWidth(),rawSize.getHeight(),captureFormat,3);
            rawReader.setOnImageAvailableListener(reader->{Image image=reader.acquireNextImage();if(image==null)return;if(!busy||closing){if(!closing&&!yuvMode&&rawAwaiting)onLiveRawImage(image);else image.close();return;}pendingImages.put(image.getTimestamp(),image);savePair(image.getTimestamp());},cameraHandler);
            manager.openCamera(cameraId,new CameraDevice.StateCallback(){
                public void onOpened(CameraDevice device){opening=false;if(closing){device.close();return;}camera=device;createSession();}
                public void onDisconnected(CameraDevice device){device.close();camera=null;message("Camera disconnected.");ready(false);}
                public void onError(CameraDevice device,int error){device.close();camera=null;opening=false;message("Camera error: "+error);ready(false);}
            },cameraHandler);
        }catch(Exception e){opening=false;message("Camera unavailable: "+e.getMessage());android.util.Log.e("RawCapture","open",e);ready(false);}
    }
    void settings(CaptureRequest.Builder b,int afMode){
        b.set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);
        if(fixedExposureNs>0){
            b.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_SENSITIVITY,fixedIso);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME,fixedExposureNs);
            b.set(CaptureRequest.SENSOR_FRAME_DURATION,Math.max(33333333L,fixedExposureNs));
        }
        b.set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_OFF);
        b.set(CaptureRequest.CONTROL_AF_MODE,fixedFocus?CaptureRequest.CONTROL_AF_MODE_OFF:afMode);
        b.set(CaptureRequest.SCALER_CROP_REGION,active);b.set(CaptureRequest.CONTROL_ZOOM_RATIO,1f);
        // Keep preview buffers and AF coordinates in the same native sensor space.
        // AUTO may rotate/crop the stream independently of our TextureView matrix.
        if(Build.VERSION.SDK_INT>=31&&has(characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES),CaptureRequest.SCALER_ROTATE_AND_CROP_NONE))b.set(CaptureRequest.SCALER_ROTATE_AND_CROP,CaptureRequest.SCALER_ROTATE_AND_CROP_NONE);
        if(focusRegion!=null)b.set(CaptureRequest.CONTROL_AF_REGIONS,new MeteringRectangle[]{focusRegion});
        if(has(characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),CaptureRequest.EDGE_MODE_OFF))b.set(CaptureRequest.EDGE_MODE,CaptureRequest.EDGE_MODE_OFF);
        if(has(characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),CaptureRequest.NOISE_REDUCTION_MODE_OFF))b.set(CaptureRequest.NOISE_REDUCTION_MODE,CaptureRequest.NOISE_REDUCTION_MODE_OFF);
    }
    void createSession(){try{
        Range<Long> exposureRange=characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        Long maxFrameDuration=characteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION);
        if(exposureRange==null||!exposureRange.contains(fixedExposureNs)||maxFrameDuration!=null&&fixedExposureNs>maxFrameDuration)throw new IllegalStateException("Exposure 112.5 ms unavailable");
        Range<Integer> isoRange=characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if(isoRange==null||!isoRange.contains(fixedIso)||!has(characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR))throw new IllegalStateException("Manual ISO 100 unavailable");
        if(previewSurface!=null)previewSurface.release();
        texture.getSurfaceTexture().setDefaultBufferSize(previewSize.getWidth(),previewSize.getHeight());previewSurface=new Surface(texture.getSurfaceTexture());
        preview=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);preview.addTarget(previewSurface);continuous();
        List<Surface> outputs=new ArrayList<>(Arrays.asList(previewSurface,rawReader.getSurface()));
        if(analysisSupported&&analysisSize!=null){analysisReader=ImageReader.newInstance(analysisSize.getWidth(),analysisSize.getHeight(),ImageFormat.YUV_420_888,2);analysisReader.setOnImageAvailableListener(this::analyzePreview,cameraHandler);outputs.add(analysisReader.getSurface());preview.addTarget(analysisReader.getSurface());}
        camera.createCaptureSession(outputs,new CameraCaptureSession.StateCallback(){
            public void onConfigured(CameraCaptureSession value){if(closing){value.close();return;}session=value;try{session.setRepeatingRequest(preview.build(),previewCallback,cameraHandler);ui.post(()->configureTransform());message(formatName()+" ready Ã‚Â· tap code to focus Ã‚Â· "+rawSize);ready(true);}catch(Exception e){message(e.getMessage());}}
            public void onConfigureFailed(CameraCaptureSession value){value.close();if(analysisReader!=null){analysisReader.close();analysisReader=null;analysisSupported=false;ui.post(()->boundaryStatus.setText("Live boundary stream unsupported; capture still available"));createSession();}else{message(formatName()+" + preview combination unsupported.");ready(false);}}
        },cameraHandler);
    }catch(Exception e){message("Preview error: "+e.getMessage());}}
    void continuous(){int mode=has(characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)?CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE:CaptureRequest.CONTROL_AF_MODE_AUTO;settings(preview,mode);preview.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_IDLE);}
    void configureTransform(){if(previewSize==null||characteristics==null||texture.getWidth()==0)return;
        int rotation=getWindowManager().getDefaultDisplay().getRotation()*90;
        // Explicit native buffers: apply sensor/display rotation exactly once.
        int degrees=(characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)-rotation+360)%360;
        mapping=new PreviewMapping(texture.getWidth(),texture.getHeight(),previewSize.getWidth(),previewSize.getHeight(),degrees,displayZoom,zoomCenterX*previewSize.getWidth(),zoomCenterY*previewSize.getHeight());
        float[] dst=new float[6];double[][] points={mapping.toView(0,0),mapping.toView(previewSize.getWidth(),0),mapping.toView(0,previewSize.getHeight())};for(int i=0;i<3;i++){dst[2*i]=(float)points[i][0];dst[2*i+1]=(float)points[i][1];}
        // TextureView's camera surface already carries sensor orientation. Its
        // untransformed content is stretched to the view, not a native landscape
        // buffer. Map those oriented source corners to our fit-center corners.
        int baseRotation=characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        double rw=baseRotation%180==0?previewSize.getWidth():previewSize.getHeight();
        double rh=baseRotation%180==0?previewSize.getHeight():previewSize.getWidth();
        PreviewMapping surface=new PreviewMapping(rw,rh,previewSize.getWidth(),previewSize.getHeight(),baseRotation);
        double[][] source={surface.toView(0,0),surface.toView(previewSize.getWidth(),0),surface.toView(0,previewSize.getHeight())};
        float[] src=new float[6];for(int i=0;i<3;i++){src[2*i]=(float)(source[i][0]/rw*texture.getWidth());src[2*i+1]=(float)(source[i][1]/rh*texture.getHeight());}
        Matrix matrix=new Matrix();matrix.setPolyToPoly(src,0,dst,0,3);texture.setTransform(matrix);
        boundaryMark.invalidate();if(panelMark!=null)panelMark.invalidate();
        if(lastBufferX>=0){double[] position=mapping.toView(lastBufferX,lastBufferY);lastTapX=(float)position[0];lastTapY=(float)position[1];mark.move(lastTapX,lastTapY);}
    }
    void setDisplayZoom(float zoom){if(busy)return;liveEpoch++;boundaryMark.clear();if(zoom>1&&lastBufferX>=0&&previewSize!=null){zoomCenterX=lastBufferX/previewSize.getWidth();zoomCenterY=lastBufferY/previewSize.getHeight();}displayZoom=zoom;zoomLabel.setText("View "+(int)zoom+"\u00d7");configureTransform();}
    void tap(float x,float y){if(mapping==null||busy||session==null)return;double[] point=mapping.toBuffer(x,y);if(point==null)return;
        if(panelRegistration.configuration!=null){double[] selected=panelRegistration.selectAt(point[0]/previewSize.getWidth(),point[1]/previewSize.getHeight());if(selected==null)return;point=new double[]{selected[0]*previewSize.getWidth(),selected[1]*previewSize.getHeight()};}
        lastBufferX=point[0];lastBufferY=point[1];
        if(panelRegistration.configuration!=null){selectedBodyOutline=null;selectedBodyId="";setDisplayZoom(8f);boundaryStatus.setText("Finding ferrite, then code...");}
        double half=.15*Math.min(previewSize.getWidth(),previewSize.getHeight());
        touchSearchRegion=new double[]{Math.max(0,(point[0]-half)/previewSize.getWidth()),Math.max(0,(point[1]-half)/previewSize.getHeight()),Math.min(1,(point[0]+half)/previewSize.getWidth()),Math.min(1,(point[1]+half)/previewSize.getHeight())};
        liveEpoch++;boundaryMark.clear();
        final int touchEpoch=liveEpoch;final boolean panelTouch=panelRegistration.configuration!=null;
        if(panelTouch){focusStartFrame=Long.MAX_VALUE;cycleFocusEpoch=touchEpoch;cycleFreshAfterNs=Long.MAX_VALUE;cycleFocusWaiting=true;}
        lastTapX=x;lastTapY=y;
        Rect current=new Rect(crop);double target=(double)previewSize.getWidth()/previewSize.getHeight();double cw=current.width(),ch=current.height();if(cw/ch>target)cw=ch*target;else ch=cw/target;
        int sx=(int)(current.centerX()-cw/2+point[0]/previewSize.getWidth()*cw),sy=(int)(current.centerY()-ch/2+point[1]/previewSize.getHeight()*ch);
        int side=Math.max(40,(int)(Math.min(cw,ch)*.08));int left=Math.max(active.left,Math.min(active.right-side,sx-side/2)),top=Math.max(active.top,Math.min(active.bottom-side,sy-side/2));
        double[] focusView=mapping.toView(point[0],point[1]);mark.show((float)focusView[0],(float)focusView[1],Color.YELLOW);cameraHandler.post(()->{if(touchEpoch!=liveEpoch)return;cancelCycleFocus();manualFocusUntil=SystemClock.elapsedRealtime()+5000;if(busy||session==null)return;Integer regions=characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);if(regions==null||regions<1||fixedFocus){message("Touch focus unsupported by this lens.");return;}if(panelTouch){cycleFocusEpoch=touchEpoch;cycleFreshAfterNs=Long.MAX_VALUE;cycleFocusWaiting=true;}focusRegion=new MeteringRectangle(left,top,side,side,1000);triggerFocus(false);});
    }
    void cancelCycleFocus(){cycleFocusWaiting=false;cycleFreshAfterNs=0;cycleTarget=null;focusScheduler.clear();cycleFocusTicket++;}
    void nextCodeFocus(ArrayList<float[]> targets,int epoch,int[] signature){
        if(panelRegistration.configuration!=null&&panelRegistration.configuration.optBoolean("manual_selection",true))return;
        if(closing||!liveEnabled||epoch!=liveEpoch||session==null||busy)return;
        if(SystemClock.elapsedRealtime()<manualFocusUntil)return;
        Integer regions=characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
        if(fixedFocus||regions==null||regions<1){cancelCycleFocus();return;}
        if(motionDifference(signature,latestSceneSignature)>8){cancelCycleFocus();return;}
        if(targets.isEmpty()){
            boolean hadTarget=cycleTarget!=null;cancelCycleFocus();
            if(hadTarget)try{focusRegion=null;preview.set(CaptureRequest.CONTROL_AF_REGIONS,null);preview.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);session.capture(preview.build(),null,cameraHandler);continuous();session.setRepeatingRequest(preview.build(),previewCallback,cameraHandler);}catch(Exception e){android.util.Log.w("CodeFocus","restore continuous focus",e);}
            return;
        }
        int next=focusScheduler.next(targets);
        cycleTarget=targets.get(next);cycleFocusEpoch=epoch;
        double nx=0,ny=0;for(int i=0;i<4;i++){nx+=cycleTarget[2*i]/4.;ny+=cycleTarget[2*i+1]/4.;}
        Rect current=crop==null?active:crop;double target=(double)previewSize.getWidth()/previewSize.getHeight(),cw=current.width(),ch=current.height();if(cw/ch>target)cw=ch*target;else ch=cw/target;
        int sx=(int)(current.centerX()-cw/2+nx*cw),sy=(int)(current.centerY()-ch/2+ny*ch);
        double minX=1,minY=1,maxX=0,maxY=0;for(int i=0;i<4;i++){minX=Math.min(minX,cycleTarget[2*i]);maxX=Math.max(maxX,cycleTarget[2*i]);minY=Math.min(minY,cycleTarget[2*i+1]);maxY=Math.max(maxY,cycleTarget[2*i+1]);}
        int fw=Math.min(active.width(),Math.max(40,(int)((maxX-minX)*cw*1.4))),fh=Math.min(active.height(),Math.max(40,(int)((maxY-minY)*ch*1.4)));
        int left=Math.max(active.left,Math.min(active.right-fw,sx-fw/2)),top=Math.max(active.top,Math.min(active.bottom-fh,sy-fh/2));
        focusRegion=new MeteringRectangle(left,top,fw,fh,1000);cycleFocusWaiting=true;cycleFreshAfterNs=Long.MAX_VALUE;int ticket=++cycleFocusTicket;
        final double fx=nx,fy=ny;final int number=next+1,total=targets.size();
        ui.post(()->{if(epoch!=liveEpoch||closing)return;if(mapping!=null){double[] view=mapping.toView(fx*previewSize.getWidth(),fy*previewSize.getHeight());mark.show((float)view[0],(float)view[1],Color.YELLOW);}boundaryStatus.setText("Focusing code "+number+" / "+total);});
        android.util.Log.i("CodeFocus","start target="+next+" center="+nx+","+ny+" epoch="+epoch);
        try{triggerFocus(false);}catch(Exception e){cycleFocusWaiting=false;cycleFreshAfterNs=0;}
        cameraHandler.postDelayed(()->{if(ticket!=cycleFocusTicket||!cycleFocusWaiting)return;if(epoch!=liveEpoch||closing||!liveEnabled){cancelCycleFocus();return;}cycleFocusWaiting=false;cycleFreshAfterNs=0;android.util.Log.i("CodeFocus","timeout; continue without confirmed focus");},3000);
    }
    static double quadCenterDistance(float[] a,float[] b){double x=0,y=0;for(int i=0;i<4;i++){x+=(a[2*i]-b[2*i])/4.;y+=(a[2*i+1]-b[2*i+1])/4.;}return Math.hypot(x,y);}
    void triggerFocus(boolean captureAfter){try{
        settings(preview,CaptureRequest.CONTROL_AF_MODE_AUTO);preview.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);session.capture(preview.build(),null,cameraHandler);
        preview.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_IDLE);session.setRepeatingRequest(preview.build(),previewCallback,cameraHandler);
        focusStartFrame=Long.MAX_VALUE;waitingFocus=captureAfter;preview.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_START);session.capture(preview.build(),previewCallback,cameraHandler);preview.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_IDLE);message("FocusingÃ¢â‚¬Â¦");
    }catch(Exception e){fail("Focus failed: "+e.getMessage());}}
    final CameraCaptureSession.CaptureCallback previewCallback=new CameraCaptureSession.CaptureCallback(){
        @Override public void onCaptureStarted(CameraCaptureSession s,CaptureRequest request,long timestamp,long frame){if(Objects.equals(request.get(CaptureRequest.CONTROL_AF_TRIGGER),CaptureRequest.CONTROL_AF_TRIGGER_START))focusStartFrame=frame;}
        @Override public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest request,TotalCaptureResult result){
            if(closing||s!=session)return;
            Integer measuredIso=result.get(CaptureResult.SENSOR_SENSITIVITY);
            Long measuredExposure=result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            long exposureNow=SystemClock.elapsedRealtime();
            if(exposureNow-lastExposureReport>2000&&measuredIso!=null&&measuredExposure!=null){
                lastExposureReport=exposureNow;
                try{JSONObject info=new JSONObject().put("requested_iso",fixedIso).put("actual_iso",measuredIso).put("exposure_ns",measuredExposure).put("ae_mode",result.get(CaptureResult.CONTROL_AE_MODE)).put("sensor_timestamp_ns",result.get(CaptureResult.SENSOR_TIMESTAMP));writeJson(new File(getFilesDir(),"exposure_status.json"),info);android.util.Log.i("ExposureControl",info.toString());}catch(Exception ignored){}
            }
            Rect actual=result.get(CaptureResult.SCALER_CROP_REGION);if(actual!=null)crop=new Rect(actual);
            Integer af=result.get(CaptureResult.CONTROL_AF_STATE),lens=result.get(CaptureResult.LENS_STATE);
            if(cycleFocusWaiting&&cycleFocusEpoch==liveEpoch&&result.getFrameNumber()>=focusStartFrame&&
                (Objects.equals(af,CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED)||Objects.equals(af,CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED))&&
                (lens==null||lens!=CaptureResult.LENS_STATE_MOVING)){
                Long timestamp=result.get(CaptureResult.SENSOR_TIMESTAMP);if(timestamp!=null){cycleFreshAfterNs=timestamp+150000000L;cycleFocusWaiting=false;android.util.Log.i("CodeFocus","settled af="+af+" fresh_after_ns="+cycleFreshAfterNs);}
            }
            if(waitingFocus&&result.getFrameNumber()>=focusStartFrame&&Objects.equals(af,CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED)&&(lens==null||lens!=CaptureResult.LENS_STATE_MOVING)){waitingFocus=false;captureRaw();}
            else if(!busy&&Objects.equals(af,CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED)){message("Focus locked - ready to capture "+formatName());ui.post(()->mark.color(Color.GREEN));}
            else if(!busy&&Objects.equals(af,CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)){message("Focus not confirmed Ã‚Â· tap code again");ui.post(()->mark.color(Color.RED));}
        }
    };
    void beginCapture(){if(busy||session==null||closing)return;busy=true;rawRequested=false;ready(false);int ticket=++generation;
        if(fixedFocus)captureRaw();else triggerFocus(true);
        cameraHandler.postDelayed(()->{if(busy&&waitingFocus&&generation==ticket)fail("Focus not confirmed. Tap the code and retry.");},4000);
        cameraHandler.postDelayed(()->{if(busy&&generation==ticket)fail(formatName()+" capture timed out. Retry.");},12000);
    }
    void captureRaw(){if(rawRequested||closing)return;rawRequested=true;message("Recording one "+formatName()+" frame...");try{
        CaptureRequest.Builder request=camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);settings(request,CaptureRequest.CONTROL_AF_MODE_AUTO);request.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_IDLE);request.addTarget(rawReader.getSurface());
        session.capture(request.build(),new CameraCaptureSession.CaptureCallback(){
            @Override public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest req,TotalCaptureResult result){Long timestamp=result.get(CaptureResult.SENSOR_TIMESTAMP);if(timestamp==null){fail("Missing capture timestamp.");return;}pendingResults.put(timestamp,result);savePair(timestamp);}
            @Override public void onCaptureFailed(CameraCaptureSession s,CaptureRequest req,CaptureFailure failure){fail(formatName()+" capture failed: "+failure.getReason());}
        },cameraHandler);
    }catch(Exception e){fail("Capture failed: "+e.getMessage());}}
    void savePair(long timestamp){Image image=pendingImages.get(timestamp);TotalCaptureResult result=pendingResults.get(timestamp);if(image==null||result==null)return;
        pendingImages.remove(timestamp);pendingResults.remove(timestamp);
        if(yuvMode){saveYuv(image,result);return;}
        Uri dngUri=null; File json=null;
        try(Image raw=image;DngCreator creator=new DngCreator(characteristics,result)){
            Integer af=result.get(CaptureResult.CONTROL_AF_STATE);if(!fixedFocus&&!Objects.equals(af,CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED))throw new IllegalStateException("Focus changed before capture; frame not saved.");
            String name="raw_"+new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(new Date());File folder=captureDirectory();json=new File(folder,name+".json");
            ContentValues entry=new ContentValues();
            entry.put(MediaStore.Images.Media.DISPLAY_NAME,name+".dng");
            entry.put(MediaStore.Images.Media.MIME_TYPE,"image/x-adobe-dng");
            entry.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_DCIM+"/QR_Reader/");
            entry.put(MediaStore.Images.Media.IS_PENDING,1);
            dngUri=getContentResolver().insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),entry);
            if(dngUri==null)throw new IOException("Cannot create DNG in DCIM/QR_Reader");
            try(OutputStream stream=getContentResolver().openOutputStream(dngUri,"w")){if(stream==null)throw new IOException("Cannot open DNG output");creator.writeImage(stream,raw);}
            JSONObject metadata=new JSONObject().put("model",Build.MODEL).put("camera_id",cameraId).put("raw_size",rawSize.toString()).put("timestamp_ns",timestamp).put("image_timestamp_ns",raw.getTimestamp()).put("iso",result.get(CaptureResult.SENSOR_SENSITIVITY)).put("exposure_ns",result.get(CaptureResult.SENSOR_EXPOSURE_TIME)).put("focus_distance_diopters",result.get(CaptureResult.LENS_FOCUS_DISTANCE)).put("af_state",af).put("lens_state",result.get(CaptureResult.LENS_STATE)).put("focal_length_mm",result.get(CaptureResult.LENS_FOCAL_LENGTH)).put("zoom_ratio",result.get(CaptureResult.CONTROL_ZOOM_RATIO)).put("crop_region",String.valueOf(result.get(CaptureResult.SCALER_CROP_REGION))).put("focus_region",String.valueOf(focusRegion)).put("physical_camera_id",result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)).put("application_pixel_processing","none; RAW_SENSOR image written directly by DngCreator; device firmware processing is outside app control");
            metadata.put("focus_mapping_version",2).put("sensor_orientation",characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)).put("active_array",String.valueOf(active)).put("preview_size",previewSize.toString()).put("af_regions_result",String.valueOf(Arrays.toString(result.get(CaptureResult.CONTROL_AF_REGIONS))));
            metadata.put("focus_mapping_version",3).put("preview_display_zoom",captureDisplayZoom).put("preview_zoom_center_x_normalized",captureZoomCenterX).put("preview_zoom_center_y_normalized",captureZoomCenterY).put("preview_zoom_scope","display only; capture zoom ratio remains 1");
            if(Build.VERSION.SDK_INT>=31)metadata.put("rotate_and_crop_result",result.get(CaptureResult.SCALER_ROTATE_AND_CROP));
            metadata.put("dng_uri",dngUri.toString()).put("dng_relative_path","DCIM/QR_Reader/"+name+".dng");
            metadata.put("touch_view_x",lastTapX).put("touch_view_y",lastTapY).put("view_width",captureViewWidth).put("view_height",captureViewHeight);
            if(capturePreview!=null){File diagnostic=new File(folder,name+"_preview.png");try(OutputStream out=new FileOutputStream(diagnostic)){capturePreview.compress(Bitmap.CompressFormat.PNG,100,out);}metadata.put("preview_diagnostic",diagnostic.getName());capturePreview.recycle();capturePreview=null;}
            writeJson(json,metadata);
            ContentValues publish=new ContentValues();publish.put(MediaStore.Images.Media.IS_PENDING,0);
            if(getContentResolver().update(dngUri,publish,null,null)!=1)throw new IOException("Cannot publish DNG");
            message("Saved to DCIM/QR_Reader\n"+name+".dng");android.util.Log.i("RawCapture","SAVED "+dngUri);finishCapture();
        }catch(Exception e){if(dngUri!=null)try{getContentResolver().delete(dngUri,null,null);}catch(Exception ignored){}if(json!=null)json.delete();fail("Save failed: "+e.getMessage());android.util.Log.e("RawCapture","save",e);}
    }
    void saveYuv(Image image,TotalCaptureResult result){
        Uri uri=null;File planar=null,json=null,diagnostic=null;
        try(Image frame=image){
            Integer af=result.get(CaptureResult.CONTROL_AF_STATE);
            if(!fixedFocus&&!Objects.equals(af,CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED))throw new IllegalStateException("Focus changed before capture; frame not saved.");
            int w=frame.getWidth(),h=frame.getHeight();Image.Plane[] planes=frame.getPlanes();
            if(frame.getFormat()!=ImageFormat.YUV_420_888||planes.length!=3)throw new IOException("Expected three YUV420 planes");
            String name="yuv_"+new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(new Date());File folder=captureDirectory();
            planar=new File(folder,name+".yuv");json=new File(folder,name+".json");byte[] luma=null;JSONArray layouts=new JSONArray();long offset=0;
            try(OutputStream stream=new FileOutputStream(planar)){
                for(int i=0;i<3;i++){int pw=i==0?w:(w+1)/2,ph=i==0?h:(h+1)/2;Image.Plane p=planes[i];byte[] data=YuvData.pack(p.getBuffer(),pw,ph,p.getRowStride(),p.getPixelStride());
                    stream.write(data);if(i==0)luma=data;layouts.put(new JSONObject().put("channel",i==0?"Y":i==1?"U":"V").put("offset",offset).put("width",pw).put("height",ph).put("bytes",data.length).put("source_row_stride",p.getRowStride()).put("source_pixel_stride",p.getPixelStride()));offset+=data.length;}
            }
            ContentValues entry=new ContentValues();entry.put(MediaStore.Images.Media.DISPLAY_NAME,name+"_Y.png");entry.put(MediaStore.Images.Media.MIME_TYPE,"image/png");entry.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_DCIM+"/QR_Reader/");entry.put(MediaStore.Images.Media.IS_PENDING,1);
            uri=getContentResolver().insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),entry);if(uri==null)throw new IOException("Cannot create PNG");
            try(OutputStream stream=getContentResolver().openOutputStream(uri,"w")){if(stream==null)throw new IOException("Cannot open PNG");YuvData.grayPng(stream,luma,w,h);}
            JSONObject meta=new JSONObject().put("model",Build.MODEL).put("camera_id",cameraId).put("capture_format","YUV_420_888").put("width",w).put("height",h).put("timestamp_ns",result.get(CaptureResult.SENSOR_TIMESTAMP)).put("image_timestamp_ns",frame.getTimestamp()).put("planes",layouts).put("planar_file",planar.getName()).put("planar_layout","packed Y then U then V; 8-bit samples, no row padding; chroma ceil(width/2) x ceil(height/2)").put("luma_png_uri",uri.toString()).put("luma_png_relative_path","DCIM/QR_Reader/"+name+"_Y.png")
                .put("iso",result.get(CaptureResult.SENSOR_SENSITIVITY)).put("exposure_ns",result.get(CaptureResult.SENSOR_EXPOSURE_TIME)).put("focus_distance_diopters",result.get(CaptureResult.LENS_FOCUS_DISTANCE)).put("af_state",af).put("focus_region",String.valueOf(focusRegion)).put("af_regions_result",String.valueOf(Arrays.toString(result.get(CaptureResult.CONTROL_AF_REGIONS)))).put("zoom_ratio",result.get(CaptureResult.CONTROL_ZOOM_RATIO)).put("crop_region",String.valueOf(result.get(CaptureResult.SCALER_CROP_REGION))).put("active_array",String.valueOf(active)).put("sensor_orientation",characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)).put("preview_size",previewSize.toString()).put("focus_mapping_version",3).put("preview_display_zoom",captureDisplayZoom).put("preview_zoom_center_x_normalized",captureZoomCenterX).put("preview_zoom_center_y_normalized",captureZoomCenterY).put("touch_view_x",lastTapX).put("touch_view_y",lastTapY).put("view_width",captureViewWidth).put("view_height",captureViewHeight)
                .put("application_pixel_processing","YUV sample bytes packed unchanged; Y stored as 8-bit grayscale lossless PNG without range remapping; ISP processing already applied by device; not sensor RAW");
            if(capturePreview!=null){diagnostic=new File(folder,name+"_preview.png");try(OutputStream stream=new FileOutputStream(diagnostic)){capturePreview.compress(Bitmap.CompressFormat.PNG,100,stream);}meta.put("preview_diagnostic",diagnostic.getName());capturePreview.recycle();capturePreview=null;}
            writeJson(json,meta);ContentValues publish=new ContentValues();publish.put(MediaStore.Images.Media.IS_PENDING,0);if(getContentResolver().update(uri,publish,null,null)!=1)throw new IOException("Cannot publish PNG");
            message("Saved YUV + lossless Y PNG\nDCIM/QR_Reader/"+name+"_Y.png");android.util.Log.i("RawCapture","SAVED YUV "+uri);finishCapture();
        }catch(Exception e){if(uri!=null)try{getContentResolver().delete(uri,null,null);}catch(Exception ignored){}if(planar!=null)planar.delete();if(json!=null)json.delete();if(diagnostic!=null)diagnostic.delete();fail("YUV save failed: "+e.getMessage());android.util.Log.e("RawCapture","saveYuv",e);}
    }
    void finishCapture(){busy=false;waitingFocus=false;rawRequested=false;generation++;if(session!=null&&!closing)try{continuous();session.setRepeatingRequest(preview.build(),previewCallback,cameraHandler);}catch(Exception e){message(e.getMessage());}ready(!closing&&session!=null);}
    void fail(String reason){for(Image image:pendingImages.values())image.close();pendingImages.clear();pendingResults.clear();finishCapture();message(reason);}
    void closeCamera(){if(panelRegistration!=null)panelRegistration.cameraClosed();cancelCycleFocus();if(rawAwaiting)boundaryRunning.set(false);rawAwaiting=false;rawTicket++;liveRawImages.clear();liveRawResults.clear();liveEpoch++;ui.post(()->boundaryMark.clear());waitingFocus=false;busy=false;opening=false;generation++;for(Image image:pendingImages.values())image.close();pendingImages.clear();pendingResults.clear();if(session!=null){session.close();session=null;}if(camera!=null){camera.close();camera=null;}if(rawReader!=null){rawReader.close();rawReader=null;}if(analysisReader!=null){analysisReader.close();analysisReader=null;}if(previewSurface!=null){previewSurface.release();previewSurface=null;}}
    static int[] sceneSignature(Image.Plane plane,int width,int height){java.nio.ByteBuffer b=plane.getBuffer();int base=b.position(),rs=plane.getRowStride(),ps=plane.getPixelStride();int[] sig=new int[32*24];for(int y=0;y<24;y++)for(int x=0;x<32;x++)sig[y*32+x]=b.get(base+Math.min(height-1,(2*y+1)*height/48)*rs+Math.min(width-1,(2*x+1)*width/64)*ps)&255;return sig;}
    static double sceneDifference(int[] a,int[] b){if(a==null||b==null||a.length!=b.length)return Double.MAX_VALUE;double sum=0;for(int i=0;i<a.length;i++)sum+=Math.abs(a[i]-b[i]);return sum/a.length;}
    double motionDifference(int[] a,int[] b){if(panelRegistration!=null&&panelRegistration.configuration!=null&&panelRegistration.configuration.optBoolean("manual_alignment",true))return 0;return panelRegistration!=null&&panelRegistration.configuration!=null?PanelMotion.difference(a,b)*8./PanelMotion.LIVE_LIMIT:sceneDifference(a,b);}
    void analyzePreview(ImageReader reader){
        Image image=null;try{image=reader.acquireLatestImage();if(image==null||closing||busy)return;
            if(cycleFocusEpoch!=liveEpoch&&(cycleFocusWaiting||cycleTarget!=null))cancelCycleFocus();
            int[] lumaSignature=sceneSignature(image.getPlanes()[0],image.getWidth(),image.getHeight());
            panelRegistration.poll(image,lumaSignature);
            final int[] signature=panelRegistration.configuration!=null?panelRegistration.latestMotion:lumaSignature;latestSceneSignature=signature;
            if(!liveEnabled||panelRegistration.holding)return;
            ui.post(()->{if(displayedSceneSignature!=null&&motionDifference(displayedSceneSignature,signature)>8){boundaryMark.clear();displayedSceneSignature=null;}});
            if(rawAnalysisEnabled&&yuvMode){ui.post(()->boundaryStatus.setText("RAW analysis unavailable on this camera"));return;}
            if(cycleFocusWaiting||image.getTimestamp()<=cycleFreshAfterNs)return;
            long now=SystemClock.elapsedRealtime();if(now-lastBoundaryStart<700||!boundaryRunning.compareAndSet(false,true))return;
            if(rawAnalysisEnabled)requestLiveRaw(now,signature);
            else {try{Image.Plane yp=image.getPlanes()[0];byte[] y=YuvData.pack(yp.getBuffer(),image.getWidth(),image.getHeight(),yp.getRowStride(),yp.getPixelStride());byte[] u=null,v=null;int selection=channelMask;
                if((selection&14)!=0){Image.Plane up=image.getPlanes()[1],vp=image.getPlanes()[2];u=YuvData.pack(up.getBuffer(),image.getWidth()/2,image.getHeight()/2,up.getRowStride(),up.getPixelStride());v=YuvData.pack(vp.getBuffer(),image.getWidth()/2,image.getHeight()/2,vp.getRowStride(),vp.getPixelStride());}
                lastBoundaryStart=now;if(saveFrames)saveLiveYuv(y,image.getWidth(),image.getHeight(),image.getTimestamp(),cycleTarget);processAnalysis(null,image.getWidth(),image.getHeight(),null,y,u,v,now,signature,liveEpoch,selection,image.getTimestamp());
            }catch(Exception e){boundaryRunning.set(false);throw e;}}
        }catch(Exception e){android.util.Log.e("LiveBoundary","RAW trigger",e);}finally{if(image!=null)image.close();}
    }
    void requestLiveRaw(long now,int[] signature){
        if(session==null||camera==null){boundaryRunning.set(false);return;}
        rawAwaiting=true;rawStart=now;lastBoundaryStart=now;rawEpoch=liveEpoch;rawChannels=channelMask;rawSignature=signature;int ticket=++rawTicket;
        try{CaptureRequest.Builder request=camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            Integer af=preview.get(CaptureRequest.CONTROL_AF_MODE);settings(request,af==null?CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE:af);
            request.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_IDLE);request.addTarget(rawReader.getSurface());request.addTarget(previewSurface);
            session.capture(request.build(),new CameraCaptureSession.CaptureCallback(){
                @Override public void onCaptureCompleted(CameraCaptureSession ss,CaptureRequest req,TotalCaptureResult result){if(!rawAwaiting||ticket!=rawTicket)return;Long timestamp=result.get(CaptureResult.SENSOR_TIMESTAMP);if(timestamp==null){abortLiveRaw("Missing RAW timestamp");return;}liveRawResults.put(timestamp,result);tryLiveRawPair(timestamp);}
                @Override public void onCaptureFailed(CameraCaptureSession ss,CaptureRequest req,CaptureFailure failure){if(ticket==rawTicket)abortLiveRaw("RAW capture failed");}
            },cameraHandler);
            cameraHandler.postDelayed(()->{if(rawAwaiting&&ticket==rawTicket)abortLiveRaw("RAW timeout");},8000);
        }catch(Exception e){abortLiveRaw(e.getMessage());}
    }
    void abortLiveRaw(String message){rawAwaiting=false;liveRawImages.clear();liveRawResults.clear();boundaryRunning.set(false);ui.post(()->boundaryStatus.setText(message));}
    void onLiveRawImage(Image image){try{if(image.getWidth()!=rawSize.getWidth()||image.getHeight()!=rawSize.getHeight())throw new IllegalStateException("RAW geometry changed");Image.Plane plane=image.getPlanes()[0];if(plane.getPixelStride()!=2)throw new IllegalStateException("RAW16 pixel stride");byte[] packed=YuvData.pack(plane.getBuffer(),image.getWidth()*2,image.getHeight(),plane.getRowStride(),1);liveRawImages.put(image.getTimestamp(),packed);tryLiveRawPair(image.getTimestamp());}catch(Exception e){abortLiveRaw(e.getMessage());}finally{image.close();}}
    void tryLiveRawPair(long timestamp){byte[] data=liveRawImages.get(timestamp);TotalCaptureResult result=liveRawResults.get(timestamp);if(data==null||result==null)return;
        liveRawImages.clear();liveRawResults.clear();rawAwaiting=false;
        if(saveFrames)saveLiveRaw(data,result,timestamp,cycleTarget);
        try{JSONObject meta=rawMetadata(result,timestamp);processRawAnalysis(data,rawSize.getWidth(),rawSize.getHeight(),meta,rawStart,rawSignature,rawEpoch,rawChannels);}catch(Exception e){abortLiveRaw(e.getMessage());}
    }
    JSONObject rawMetadata(TotalCaptureResult result,long timestamp)throws Exception{
        Rect pre=characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE);Size pixels=characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        int ox,oy;if(rawSize.getWidth()==pixels.getWidth()&&rawSize.getHeight()==pixels.getHeight()){ox=0;oy=0;}else if(pre!=null&&rawSize.getWidth()==pre.width()&&rawSize.getHeight()==pre.height()){ox=pre.left;oy=pre.top;}else throw new IllegalStateException("Unknown RAW sensor origin");
        Rect region=new Rect(active);region.offset(-ox,-oy);if(region.left<0||region.top<0||region.right>rawSize.getWidth()||region.bottom>rawSize.getHeight())throw new IllegalStateException("Invalid RAW active crop");
        BlackLevelPattern black=characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);JSONArray levels=new JSONArray();for(int y=0;y<2;y++)for(int x=0;x<2;x++)levels.put(black.getOffsetForIndex((x+ox)%2,(y+oy)%2));
        Integer white=result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL);if(white==null)white=characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        return new JSONObject().put("timestamp_ns",timestamp).put("width",rawSize.getWidth()).put("height",rawSize.getHeight()).put("cfa",characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)).put("sensor_origin",new JSONArray(new int[]{ox,oy})).put("crop",new JSONArray(new int[]{region.left,region.top,region.right,region.bottom})).put("black",levels).put("black_source","static_black_level_pattern").put("white",white).put("iso",result.get(CaptureResult.SENSOR_SENSITIVITY)).put("exposure_ns",result.get(CaptureResult.SENSOR_EXPOSURE_TIME)).put("af_state",result.get(CaptureResult.CONTROL_AF_STATE));
    }
    void processRawAnalysis(byte[] rawBytes,int width,int height,JSONObject rawMetadata,long now,int[] signature,int epoch,int selectedChannels){processAnalysis(rawBytes,width,height,rawMetadata,null,null,null,now,signature,epoch,selectedChannels,rawMetadata.optLong("timestamp_ns"));}
    void processAnalysis(byte[] rawBytes,int width,int height,JSONObject rawMetadata,byte[] y,byte[] u,byte[] v,long now,int[] signature,int epoch,int selectedChannels,long sampleTimestamp){
            final boolean isRaw=rawBytes!=null;final String sourceName=isRaw?"RAW":"YUV";
            final float[] focusedTarget=cycleTarget;final ArrayList<float[]> discoveredTargets=new ArrayList<>();
            final long focusReadyAfter=cycleFreshAfterNs;
            final JSONObject panelConfig=panelRegistration.configuration;
            final boolean[] attemptState={false,false}; // fresh body/code search, reliable grid
            final double[] search=touchSearchRegion;final String searchJson=search==null?"null":java.util.Arrays.toString(search);
            boundaryWorker.execute(()->{if(closing||epoch!=liveEpoch){boundaryRunning.set(false);return;}try(com.chaquo.python.PyObject rawState=isRaw?BoundaryEngine.prepareRaw(this,rawBytes,width,height,rawMetadata):null){
                for(int channelIndex=0;channelIndex<4;channelIndex++){
                if((selectedChannels&(1<<channelIndex))==0)continue;
                if(closing||!liveEnabled||epoch!=liveEpoch||motionDifference(signature,latestSceneSignature)>8)break;
                final String channelName=channelNames[channelIndex];
                byte[] selectedY=isRaw?null:BoundaryEngine.channel(this,y,u,v,width,height,channelName);
                JSONObject frame=panelConfig!=null?(isRaw?BoundaryEngine.analyzeRawPanel(this,rawState,channelName,panelConfig,focusedTarget):BoundaryEngine.analyzePanel(this,selectedY,width,height,panelConfig,focusedTarget)):isRaw?BoundaryEngine.analyzeRaw(rawState,channelName,searchJson):BoundaryEngine.analyze(this,selectedY,width,height,searchJson);
                attemptState[0]|=frame.optString("body_status").equals("ok")&&frame.optBoolean("fresh_geometry_per_attempt");
                if(panelConfig!=null&&frame.optString("status").equals("registration_moved")){
                    writeJson(new File(getFilesDir(),"live_boundary_frame_last.json"),frame);
                    cameraHandler.post(()->panelRegistration.poseMoved(epoch));break;
                }
                if(panelConfig!=null&&frame.optJSONObject("pose")!=null&&frame.optJSONObject("pose").optString("status").equals("unverified"))ui.post(()->{if(epoch==liveEpoch)boundaryMark.clear();});
                if(panelConfig!=null&&discoveredTargets.isEmpty())discoveredTargets.addAll(panelRegistration.quads(panelConfig));
                JSONArray symbols=frame.optJSONArray("symbols");
                int focusedIndex=-1;double nearestTarget=Double.MAX_VALUE;
                boolean recordTargets=discoveredTargets.isEmpty();
                if(symbols!=null)for(int i=0;i<symbols.length();i++){
                    JSONArray q=symbols.getJSONObject(i).getJSONArray("quad_normalized");float[] points=new float[8];for(int j=0;j<4;j++){points[2*j]=(float)q.getJSONArray(j).getDouble(0);points[2*j+1]=(float)q.getJSONArray(j).getDouble(1);}
                    if(recordTargets)discoveredTargets.add(points);
                    if(focusedTarget!=null){double d=quadCenterDistance(focusedTarget,points);if(d<nearestTarget){nearestTarget=d;focusedIndex=i;}}
                }
                if(nearestTarget>.12)focusedIndex=-1;
                frame.put("source",sourceName).put("channel",channelName).put("frame_timestamp_elapsed_ms",now);
                frame.put("focus_target",focusedTarget==null?JSONObject.NULL:new JSONArray(focusedTarget));
                frame.put("image_timestamp_ns",sampleTimestamp).put("focus_ready_after_sensor_ns",focusReadyAfter);
                writeJson(new File(getFilesDir(),"live_boundary_frame_last.json"),frame);
                int symbolCount=symbols==null?1:Math.max(1,symbols.length());
                for(int symbolIndex=0;symbolIndex<symbolCount;symbolIndex++){
                if(closing||!liveEnabled||epoch!=liveEpoch||motionDifference(signature,latestSceneSignature)>8)break;
                final JSONObject result=new JSONObject(frame.toString());result.remove("symbols");
                if(symbols!=null&&symbols.length()>0){JSONObject symbol=symbols.getJSONObject(symbolIndex);java.util.Iterator<String> keys=symbol.keys();while(keys.hasNext()){String key=keys.next();result.put(key,symbol.get(key));}}
                result.put("source",isRaw?"RAW_SENSOR16":"YUV_420_888");result.put("channel",channelName);result.put("frame_age_ms",SystemClock.elapsedRealtime()-now);result.put("frame_timestamp_elapsed_ms",now);
                writeJson(new File(getFilesDir(),"live_boundary_last.json"),result);
                ui.post(()->{if(closing||!liveEnabled||epoch!=liveEpoch)return;
                    JSONArray body=result.optJSONArray("body_quad_normalized");
                    if(body!=null){selectedBodyOutline=body;selectedBodyId=result.optString("component_id");panelMark.invalidate();}
                    if(SystemClock.elapsedRealtime()-now>(15000L+(long)(result.optDouble("seconds")*1000))*Integer.bitCount(selectedChannels)||motionDifference(signature,latestSceneSignature)>8){boundaryMark.clear();displayedSceneSignature=null;boundaryStatus.setText("Live boundary: refreshing after motion");return;}
                    try{if(result.optString("status").equals("boundary_candidate")){JSONArray q=result.getJSONArray("quad_normalized");float[] points=new float[8];for(int i=0;i<4;i++){points[2*i]=(float)q.getJSONArray(i).getDouble(0);points[2*i+1]=(float)q.getJSONArray(i).getDouble(1);}JSONObject grid=result.optJSONObject("grid");boundaryMark.rows=grid!=null&&grid.optBoolean("reliable")?grid.optInt("rows"):0;boundaryMark.cols=grid!=null&&grid.optBoolean("reliable")?grid.optInt("columns"):0;boundaryMark.show(points,boundaryMark.rows,boundaryMark.cols,Math.max(10000L,Math.min(30000L,(long)(result.getDouble("seconds")*2500))));displayedSceneSignature=signature;boundaryStatus.setText(String.format(Locale.US,sourceName+" "+channelName+" "+result.optInt("width")+"x"+result.optInt("height")+(boundaryMark.rows>0?" + grid "+boundaryMark.rows+"x"+boundaryMark.cols:" - grid uncertain")+" - %.1f s (tap to pause)",result.getDouble("seconds")));}
                    else boundaryStatus.setText(boundaryMark.points!=null?"Previous boundary - awaiting confirmation":"No reliable boundary - adjust light or angle");}catch(Exception e){boundaryStatus.setText("Live boundary: result error");}
                });
                android.util.Log.i("LiveBoundary",result.toString());
                if((panelConfig!=null||focusedTarget==null||symbolIndex==focusedIndex)&&result.has("localizations")&&!closing&&liveEnabled&&epoch==liveEpoch&&motionDifference(signature,latestSceneSignature)<=8){

                    try{BoundaryEngine.filters(this,selectedY,width,height,rawState,channelName,result,step->{attemptState[1]|=step.optBoolean("success");try{step.put("name",sourceName+"/"+channelName+"/"+step.getString("name"));}catch(Exception ignored){}ui.post(()->{if(closing||!liveEnabled||epoch!=liveEpoch||motionDifference(signature,latestSceneSignature)>8)return;JSONArray sampled=step.optJSONArray("quad_normalized");addFilterResult(step,sampled!=null?sampled:result.optJSONArray("quad_normalized"));if(step.optBoolean("decoded"))panelRegistration.onDecoded(result.optString("component_id"),step.optString("text"));});},()->!closing&&liveEnabled&&epoch==liveEpoch&&motionDifference(signature,latestSceneSignature)<=8);}catch(Exception filterError){android.util.Log.e("LiveBoundary","filters only",filterError);}
                }
                }

                }
            }catch(Exception e){android.util.Log.e("LiveBoundary","analysis",e);ui.post(()->{boundaryMark.clear();boundaryStatus.setText("Live boundary unavailable: "+e.getMessage());});}
            finally{ui.post(()->{JSONArray p=panelConfig==null?null:panelConfig.optJSONArray("parts");if(p!=null&&p.length()==1)panelRegistration.onAttemptFinished(p.optJSONObject(0).optString("designator"),epoch,attemptState[0],attemptState[1]);});Handler handler=cameraHandler;if(handler!=null)handler.post(()->{try{nextCodeFocus(discoveredTargets,epoch,signature);}finally{boundaryRunning.set(false);}});else boundaryRunning.set(false);}});
    }
    void refreshSaveButton(){if(saveFramesButton==null)return;saveFramesButton.setText(saveFrames?"Save ON ("+savedFrames.get()+")":"Save OFF");saveFramesButton.setTextColor(saveFrames?Color.GREEN:Color.LTGRAY);}
    File liveFrameDirectory(){File dir=new File(getExternalFilesDir(null),"live_frames");if(!dir.isDirectory()&&!dir.mkdirs())throw new IllegalStateException("Cannot create live_frames");return dir;}
    boolean reserveFrameSlot(){if(savedFrames.incrementAndGet()<=MAX_SAVED_FRAMES)return true;saveFrames=false;ui.post(()->{refreshSaveButton();boundaryStatus.setText("Frame saving stopped: "+MAX_SAVED_FRAMES+" files");});return false;}
    JSONObject liveFrameMeta(long timestamp,float[] target)throws Exception{JSONObject panel=panelRegistration==null?null:panelRegistration.configuration;
        return new JSONObject().put("image_timestamp_ns",timestamp).put("saved_elapsed_ms",SystemClock.elapsedRealtime()).put("model",Build.MODEL).put("camera_id",cameraId)
            .put("focus_target",target==null?JSONObject.NULL:new JSONArray(target)).put("focus_region",String.valueOf(focusRegion)).put("live_epoch",liveEpoch).put("channel_mask",channelMask)
            .put("calibration_id",panel==null?JSONObject.NULL:panel.optString("calibration_id")).put("display_zoom",displayZoom);}
    /** Writes the RAW_SENSOR frame used by live analysis as a DNG (camera metadata of that exact capture). */
    void saveLiveRaw(byte[] data,TotalCaptureResult result,long timestamp,float[] target){
        if(!reserveFrameSlot())return;final Size size=rawSize;
        frameSaver.execute(()->{File dng=null,json=null;try{File dir=liveFrameDirectory();String name="live_raw_"+timestamp;dng=new File(dir,name+".dng");json=new File(dir,name+".json");
            try(DngCreator creator=new DngCreator(characteristics,result);OutputStream out=new BufferedOutputStream(new FileOutputStream(dng),1<<20)){
                creator.writeByteBuffer(out,size,java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.nativeOrder()),0);}
            JSONObject meta=liveFrameMeta(timestamp,target).put("source","RAW_SENSOR16").put("file",dng.getName()).put("raw_size",size.toString())
                .put("iso",result.get(CaptureResult.SENSOR_SENSITIVITY)).put("exposure_ns",result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
                .put("focus_distance_diopters",result.get(CaptureResult.LENS_FOCUS_DISTANCE)).put("af_state",result.get(CaptureResult.CONTROL_AF_STATE)).put("lens_state",result.get(CaptureResult.LENS_STATE))
                .put("af_regions_result",String.valueOf(Arrays.toString(result.get(CaptureResult.CONTROL_AF_REGIONS))))
                .put("application_pixel_processing","RAW_SENSOR bytes passed unchanged to DngCreator.writeByteBuffer; same buffer as live analysis");
            writeJson(json,meta);android.util.Log.i("FrameSave",meta.toString());ui.post(this::refreshSaveButton);
        }catch(Exception e){if(dng!=null)dng.delete();if(json!=null)json.delete();android.util.Log.e("FrameSave","raw",e);}});
    }
    /** Writes the luma plane used by live YUV analysis as a lossless 8-bit PNG (no range remapping). */
    void saveLiveYuv(byte[] y,int width,int height,long timestamp,float[] target){
        if(!reserveFrameSlot())return;
        frameSaver.execute(()->{File png=null,json=null;try{File dir=liveFrameDirectory();String name="live_yuv_"+timestamp;png=new File(dir,name+"_Y.png");json=new File(dir,name+".json");
            try(OutputStream out=new BufferedOutputStream(new FileOutputStream(png),1<<20)){YuvData.grayPng(out,y,width,height);}
            JSONObject meta=liveFrameMeta(timestamp,target).put("source","YUV_420_888 luma").put("file",png.getName()).put("width",width).put("height",height)
                .put("application_pixel_processing","Y plane bytes unchanged, lossless grayscale PNG; ISP-processed, not sensor RAW");
            writeJson(json,meta);android.util.Log.i("FrameSave",meta.toString());ui.post(this::refreshSaveButton);
        }catch(Exception e){if(png!=null)png.delete();if(json!=null)json.delete();android.util.Log.e("FrameSave","yuv",e);}});
    }
    void selectAnalysisSource(boolean raw){if(raw==rawAnalysisEnabled||raw&&yuvMode)return;rawAnalysisEnabled=raw;liveEpoch++;boundaryMark.clear();refreshSourceButtons();boundaryStatus.setText((raw?"RAW":"YUV")+": waiting for fresh frame");}
    void refreshSourceButtons(){yuvSourceButton.setTextColor(rawAnalysisEnabled?Color.LTGRAY:Color.GREEN);rawSourceButton.setTextColor(rawAnalysisEnabled?Color.GREEN:Color.LTGRAY);}
    void refreshChannelButtons(){for(int i=0;i<channelButtons.size();i++){Button b=channelButtons.get(i);boolean selected=(channelMask&(1<<i))!=0;b.setTextColor(selected?Color.GREEN:Color.LTGRAY);b.setAlpha(selected?1f:.55f);b.setContentDescription(channelNames[i]+(selected?" enabled":" disabled"));}}
    void addFilterResult(JSONObject result,JSONArray quad){
        if(result.optBoolean("success")&&quad!=null){
            float[] p=new float[8];for(int i=0;i<4;i++){p[2*i]=(float)quad.optJSONArray(i).optDouble(0);p[2*i+1]=(float)quad.optJSONArray(i).optDouble(1);}
            boundaryMark.show(p,result.optInt("rows"),result.optInt("columns"),30000L);
        }
        if(result.optBoolean("decoded")&&quad!=null&&panelRegistration.readLoop==null)boundaryMark.label(quad,result.optString("text"));
    }
    final class BoundaryMark extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);float[] points;int revision;int rows,cols;
        BoundaryMark(android.content.Context c){super(c);paint.setColor(Color.YELLOW);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2*getResources().getDisplayMetrics().density);}
        final class Symbol {float[] points;int rows,cols,ticket;String text="";}
        final ArrayList<Symbol> symbols=new ArrayList<>();
        Symbol match(float[] p){
            Symbol best=null;double distance=Double.MAX_VALUE;
            for(Symbol symbol:symbols){
                float[] q=symbol.points;double px=0,py=0,qx=0,qy=0;
                for(int i=0;i<4;i++){px+=p[2*i]/4;py+=p[2*i+1]/4;qx+=q[2*i]/4;qy+=q[2*i+1]/4;}
                double size=0;for(int i=0;i<4;i++)size=Math.max(size,Math.hypot(q[2*i]-qx,q[2*i+1]-qy));
                double d=Math.hypot(px-qx,py-qy);if(d<size*.7&&d<distance){best=symbol;distance=d;}
            }return best;
        }
        // Routine refresh/focus failures remove candidates, never a successful reading.
        void clear(){symbols.removeIf(symbol->symbol.text.isEmpty());points=symbols.isEmpty()?null:symbols.get(0).points;revision++;invalidate();}
        void clearAll(){points=null;symbols.clear();revision++;invalidate();}
        void show(float[] p,int r,int c,long holdMs){
            Symbol symbol=match(p);if(symbol==null){symbol=new Symbol();symbols.add(symbol);}
            symbol.points=p;symbol.rows=r;symbol.cols=c;points=p;int ticket=++symbol.ticket;Symbol shown=symbol;
            if(panelRegistration!=null&&panelRegistration.configuration!=null){invalidate();return;}
            invalidate();ui.postDelayed(()->{if(shown.ticket==ticket&&shown.text.isEmpty()){symbols.remove(shown);points=symbols.isEmpty()?null:symbols.get(0).points;invalidate();}},holdMs);
        }
        void label(JSONArray quad,String text){
            float[] p=new float[8];for(int i=0;i<4;i++){JSONArray xy=quad.optJSONArray(i);if(xy==null)return;p[2*i]=(float)xy.optDouble(0);p[2*i+1]=(float)xy.optDouble(1);}
            Symbol symbol=match(p);show(p,symbol==null?0:symbol.rows,symbol==null?0:symbol.cols,30000L);
            symbol=match(p);if(symbol!=null){symbol.text=text;invalidate();}
        }
        @Override protected void onDraw(Canvas canvas){if(mapping==null||previewSize==null)return;for(Symbol symbol:symbols){float[] points=symbol.points;int rows=symbol.rows,cols=symbol.cols;Path path=new Path();for(int i=0;i<4;i++){double[] p=mapping.toView(points[2*i]*previewSize.getWidth(),points[2*i+1]*previewSize.getHeight());if(i==0)path.moveTo((float)p[0],(float)p[1]);else path.lineTo((float)p[0],(float)p[1]);}path.close();canvas.drawPath(path,paint);
            if(rows>0&&cols>0){float[] corners=new float[8];for(int i=0;i<4;i++){double[] p=mapping.toView(points[2*i]*previewSize.getWidth(),points[2*i+1]*previewSize.getHeight());corners[2*i]=(float)p[0];corners[2*i+1]=(float)p[1];}android.graphics.Matrix h=new android.graphics.Matrix();if(!h.setPolyToPoly(new float[]{0,0,1,0,1,1,0,1},0,corners,0,4))return;Path gridPath=new Path();for(int axis=0;axis<2;axis++){int count=axis==0?cols:rows;for(int i=1;i<count;i++){float t=(float)i/count;float[] line=axis==0?new float[]{t,0,t,1}:new float[]{0,t,1,t};h.mapPoints(line);gridPath.moveTo(line[0],line[1]);gridPath.lineTo(line[2],line[3]);}}canvas.drawPath(gridPath,paint);}
            if(!symbol.text.isEmpty()){
                float left=Float.MAX_VALUE,bottom=0;for(int i=0;i<4;i++){double[] v=mapping.toView(points[2*i]*previewSize.getWidth(),points[2*i+1]*previewSize.getHeight());left=Math.min(left,(float)v[0]);bottom=Math.max(bottom,(float)v[1]);}
                Paint label=new Paint(Paint.ANTI_ALIAS_FLAG);label.setTextSize(18*getResources().getDisplayMetrics().scaledDensity);label.setColor(Color.GREEN);label.setShadowLayer(4,0,0,Color.BLACK);
                float x=Math.max(4,Math.min(left,getWidth()-label.measureText(symbol.text)-4));float y=Math.min(getHeight()-label.getFontMetrics().descent-4,bottom-label.getFontMetrics().ascent+6);
                canvas.drawText(symbol.text,x,y,label);
            }
        }}


    }
    static final class FocusMark extends View {
        final Paint paint=new Paint();float x,y;boolean visible;
        FocusMark(android.content.Context c){super(c);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3);}
        void show(float x,float y,int color){this.x=x;this.y=y;visible=true;paint.setColor(color);invalidate();}
        void color(int color){paint.setColor(color);invalidate();}
        void move(float x,float y){this.x=x;this.y=y;invalidate();}
        @Override protected void onDraw(Canvas canvas){if(visible)canvas.drawRect(x-35,y-35,x+35,y+35,paint);}
    }
}
