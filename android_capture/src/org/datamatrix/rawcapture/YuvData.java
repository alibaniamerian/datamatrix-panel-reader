package org.datamatrix.rawcapture;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.zip.*;

/** Lossless plane packing and grayscale PNG; no RGB conversion or range remapping. */
public final class YuvData {
    public static byte[] pack(ByteBuffer source,int width,int height,int rowStride,int pixelStride) {
        if(width<=0||height<=0||pixelStride<=0||rowStride<(width-1L)*pixelStride+1)throw new IllegalArgumentException("Invalid plane geometry");
        ByteBuffer b=source.duplicate();int base=b.position();
        long end=base+(height-1L)*rowStride+(width-1L)*pixelStride;
        if(end>=b.limit())throw new IllegalArgumentException("Plane buffer too short");
        byte[] out=new byte[Math.multiplyExact(width,height)];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)out[y*width+x]=b.get(base+y*rowStride+x*pixelStride);
        return out;
    }
    static void chunk(DataOutputStream out,String type,byte[] data)throws IOException {
        byte[] tag=type.getBytes("US-ASCII");CRC32 crc=new CRC32();crc.update(tag);crc.update(data);
        out.writeInt(data.length);out.write(tag);out.write(data);out.writeInt((int)crc.getValue());
    }
    public static void grayPng(OutputStream stream,byte[] y,int width,int height)throws IOException {
        if(y.length!=Math.multiplyExact(width,height))throw new IllegalArgumentException("Wrong luma size");
        DataOutputStream out=new DataOutputStream(stream);
        out.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
        ByteArrayOutputStream header=new ByteArrayOutputStream();DataOutputStream h=new DataOutputStream(header);
        h.writeInt(width);h.writeInt(height);h.write(new byte[]{8,0,0,0,0});chunk(out,"IHDR",header.toByteArray());
        ByteArrayOutputStream compressed=new ByteArrayOutputStream();
        try(DeflaterOutputStream z=new DeflaterOutputStream(compressed)){for(int row=0;row<height;row++){z.write(0);z.write(y,row*width,width);}}
        chunk(out,"IDAT",compressed.toByteArray());chunk(out,"IEND",new byte[0]);out.flush();
    }
}
