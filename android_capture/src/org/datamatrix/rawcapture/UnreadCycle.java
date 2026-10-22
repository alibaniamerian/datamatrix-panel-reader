package org.datamatrix.rawcapture;

/** UI-thread round robin. A successful component is never revisited. */
final class UnreadCycle {
    final boolean[] read;int current=-1,failedAttempts;long deadline;
    UnreadCycle(int count){read=new boolean[count];}
    int next(long now){
        for(int n=1;n<=read.length;n++){int i=(current+n)%read.length;if(!read[i]){current=i;failedAttempts=0;deadline=now+4000;return i;}}
        current=-1;return -1;
    }
    boolean success(int index){if(index!=current||index<0||read[index])return false;read[index]=true;return true;}
    boolean failed(int index){return index==current&&index>=0&&!read[index]&&++failedAttempts>=3;}
    int remaining(){int n=0;for(boolean value:read)if(!value)n++;return n;}
}
