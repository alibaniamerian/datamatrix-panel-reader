package org.datamatrix.rawcapture;
import org.json.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.datamatrix.DataMatrixWriter;

public class GridDecoderTest {
 public static void main(String[] args)throws Exception{
  String text="probe-2468";
  BitMatrix original=new DataMatrixWriter().encode(text,BarcodeFormat.DATA_MATRIX,0,0);
  for(int m=0;m<2;m++)for(int rot=0;rot<4;rot++){
   BitMatrix a=original.clone();for(int n=0;n<rot;n++)a.rotate90();
   JSONArray board=new JSONArray();for(int y=0;y<a.getHeight();y++){JSONArray row=new JSONArray();for(int x=0;x<a.getWidth();x++)row.put(a.get(m==0?x:a.getWidth()-1-x,y)?1:0);board.put(row);}
   JSONObject step=new JSONObject().put("board",board).put("success",true).put("finder_conformance",1.);
   GridDecoder.decode(step);
   if(!step.optBoolean("decoded")||!text.equals(step.optString("text")))throw new AssertionError("orientation "+m+","+rot);
  }
  JSONObject rejected=new JSONObject().put("success",false);GridDecoder.decode(rejected);if(rejected.getBoolean("decoded"))throw new AssertionError();
  System.out.println("8 observed orientations and rejection gate passed");
 }
}
