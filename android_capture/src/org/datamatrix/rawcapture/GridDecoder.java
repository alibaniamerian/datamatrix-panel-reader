package org.datamatrix.rawcapture;
import org.json.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.DecoderResult;
import com.google.zxing.datamatrix.decoder.Decoder;

/** Decode observed cells only; never alter finder bits or run Chase guesses. */
final class GridDecoder {
    static void decode(JSONObject step)throws Exception {
        step.put("decoded",false);
        if(!step.optBoolean("success")||step.optDouble("finder_conformance")<.95)return;
        JSONArray board=step.getJSONArray("board");int rows=board.length(),cols=board.getJSONArray(0).length();
        boolean[][] a=new boolean[rows][cols];for(int r=0;r<rows;r++)for(int c=0;c<cols;c++)a[r][c]=board.getJSONArray(r).getInt(c)==1;
        // Panel-mm coordinates may reverse handedness relative to sensor pixels.
        // Try both observed orientations; never invent or repair individual bits.
        for(int mirror=0;mirror<2;mirror++){
        if(mirror==1){int h=a.length,w=a[0].length;boolean[][] flipped=new boolean[h][w];for(int r=0;r<h;r++)for(int c=0;c<w;c++)flipped[r][c]=a[r][w-1-c];a=flipped;}
        for(int rot=0;rot<4;rot++){
            int h=a.length,w=a[0].length,match=0;
            for(int r=0;r<h;r++){if(a[r][0])match++;if(a[r][w-1]==((h-1-r)%2==0))match++;}
            for(int c=0;c<w;c++){if(a[h-1][c])match++;if(a[0][c]==(c%2==0))match++;}
            if((double)match/(2*h+2*w)>=.95){
                BitMatrix bits=new BitMatrix(w,h);for(int r=0;r<h;r++)for(int c=0;c<w;c++)if(a[r][c])bits.set(c,r);
                try{DecoderResult decoded=new Decoder().decode(bits);step.put("decoded",true);step.put("text",decoded.getText());step.put("rotation_k90",rot);step.put("mirrored",mirror==1);return;}
                catch(com.google.zxing.FormatException|com.google.zxing.ChecksumException ignored){}
            }
            boolean[][] next=new boolean[w][h];for(int r=0;r<h;r++)for(int c=0;c<w;c++)next[w-1-c][r]=a[r][c];a=next;
        }
        }
    }
}
