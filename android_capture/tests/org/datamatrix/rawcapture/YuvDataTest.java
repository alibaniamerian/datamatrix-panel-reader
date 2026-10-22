package org.datamatrix.rawcapture;
import java.nio.ByteBuffer;
import java.io.*;
import java.util.Arrays;

public final class YuvDataTest {
    public static void main(String[] args)throws Exception {
        ByteBuffer b=ByteBuffer.wrap(new byte[]{99,1,77,2,77,3,88,88,4,77,5,77,6});b.position(1);
        byte[] a=YuvData.pack(b,3,2,7,2);
        if(!Arrays.equals(a,new byte[]{1,2,3,4,5,6})||b.position()!=1)throw new AssertionError("Strided plane packing");
        ByteBuffer shared=ByteBuffer.wrap(new byte[]{10,20,11,21,12,22,13,23});
        byte[] u=YuvData.pack(shared,2,2,4,2);shared.position(1);byte[] v=YuvData.pack(shared,2,2,4,2);
        if(!Arrays.equals(u,new byte[]{10,11,12,13})||!Arrays.equals(v,new byte[]{20,21,22,23}))throw new AssertionError("Shared UV buffer");
        boolean rejected=false;try{YuvData.pack(ByteBuffer.allocate(3),2,2,2,1);}catch(IllegalArgumentException e){rejected=true;}if(!rejected)throw new AssertionError("Short plane rejected");
        try(OutputStream out=new FileOutputStream(args[0])){YuvData.grayPng(out,new byte[]{0,16,64,(byte)128,(byte)235,(byte)255},3,2);}
        System.out.println("YUV padded/interleaved plane tests passed; PNG fixture written");
    }
}
