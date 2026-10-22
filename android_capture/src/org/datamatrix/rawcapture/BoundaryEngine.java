package org.datamatrix.rawcapture;

import android.content.Context;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import org.json.JSONObject;

/** All Python calls run on the single boundary executor, never on UI/camera threads. */
final class BoundaryEngine {
    static synchronized void start(Context context){
        if(!Python.isStarted())Python.start(new AndroidPlatform(context.getApplicationContext()));
    }
    static byte[] channel(Context context,byte[] y,byte[] u,byte[] v,int width,int height,String name){
        if(name.equals("FULL"))return y;start(context);
        return Python.getInstance().getModule("live_boundary").callAttr("preview_channel",y,u,v,width,height,name).toJava(byte[].class);
    }
    static com.chaquo.python.PyObject prepareRaw(Context context,byte[] data,int width,int height,JSONObject metadata){start(context);return Python.getInstance().getModule("raw_analysis").callAttr("prepare",data,width,height,metadata.toString());}
    static JSONObject analyzeRaw(com.chaquo.python.PyObject state,String channel,String roi)throws Exception{return new JSONObject(Python.getInstance().getModule("raw_analysis").callAttr("analyze",state,channel,roi).toString());}
    static JSONObject analyze(Context context,byte[] y,int width,int height,String roi)throws Exception {
        start(context);
        return new JSONObject(Python.getInstance().getModule("live_boundary").callAttr("analyze",y,width,height,roi).toString());
    }
    static JSONObject analyzePanel(Context context,byte[] y,int width,int height,JSONObject config,float[] target)throws Exception {
        start(context);
        JSONObject options=new JSONObject(config.toString()).put("require_pose_check",!config.optBoolean("manual_alignment",true)).put("reference_path",new java.io.File(context.getFilesDir(),"registration_frame.png").getAbsolutePath());
        return new JSONObject(Python.getInstance().getModule("panel_registration").callAttr("analyze",y,width,height,options.toString(),target==null?"null":new org.json.JSONArray(target).toString()).toString());
    }
    static JSONObject analyzeRawPanel(Context context,com.chaquo.python.PyObject state,String channel,JSONObject config,float[] target)throws Exception {
        start(context);
        JSONObject options=new JSONObject(config.toString()).put("reference_path",new java.io.File(context.getFilesDir(),"registration_frame.png").getAbsolutePath());
        return new JSONObject(Python.getInstance().getModule("panel_registration").callAttr("analyze_raw",state,channel,options.toString(),target==null?"null":new org.json.JSONArray(target).toString()).toString());
    }
    static com.chaquo.python.PyObject roi(com.chaquo.python.PyObject rawState,String channel,byte[] y,int width,int height,String quad){
        return rawState==null?Python.getInstance().getModule("live_boundary").callAttr("prepare_filters",y,width,height,quad):Python.getInstance().getModule("raw_analysis").callAttr("filter_state",rawState,channel,quad);
    }
    static void filters(Context context,byte[] y,int width,int height,com.chaquo.python.PyObject rawState,String channel,JSONObject boundary,java.util.function.Consumer<JSONObject> consumer,java.util.function.BooleanSupplier valid)throws Exception {
        start(context);
        com.chaquo.python.PyObject module=Python.getInstance().getModule("live_boundary");
        com.chaquo.python.PyObject state=boundary.has("quad_normalized")?roi(rawState,channel,y,width,height,boundary.getJSONArray("quad_normalized").toString()):null;
        org.json.JSONArray trials=new org.json.JSONArray();
        org.json.JSONArray routes=boundary.optJSONArray("localizations");
        if(routes!=null)for(int i=0;i<routes.length();i++){
            if(!valid.getAsBoolean())return;
            JSONObject route=routes.getJSONObject(i);JSONObject step;
            boolean located=route.optString("status").equals("boundary_candidate");
            if(located){com.chaquo.python.PyObject roi=roi(rawState,channel,y,width,height,route.getJSONArray("quad_normalized").toString());step=new JSONObject(module.callAttr("filter_step",roi,"original").toString());GridDecoder.decode(step);}
            else step=new JSONObject().put("decoded",false);
            step.put("name",route.getString("name")).put("located",located);if(located)step.put("quad_normalized",route.getJSONArray("quad_normalized"));trials.put(step);consumer.accept(step);
        }
        if(state==null)return;
        for(String name:new String[]{"original","unsharp_s1.2_a1.5","unsharp_s2_a1","unsharp_s0.9_a3","illumination_sigma8","gaussian_s0.6"}){
            if(!valid.getAsBoolean())break;
            JSONObject step=new JSONObject(module.callAttr("filter_step",state,name).toString());GridDecoder.decode(step);trials.put(step);consumer.accept(step);
            JSONObject record=new JSONObject().put("boundary",boundary).put("filters",trials).put("criterion","grid fit >=0.9 gap>=0.025; decode requires finder>=0.95 and ZXing ECC200");
            try(java.io.Writer writer=new java.io.OutputStreamWriter(context.openFileOutput("live_filters_last.json",Context.MODE_PRIVATE),"UTF-8")){writer.write(record.toString(2));}
        }
    }
    static JSONObject test(Context context,String path)throws Exception {
        start(context);
        return new JSONObject(Python.getInstance().getModule("live_boundary").callAttr("analyze_file",path).toString());
    }
}
