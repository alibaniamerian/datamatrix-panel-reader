package org.datamatrix.rawcapture;

public final class UnreadCycleTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args){
        UnreadCycle cycle=new UnreadCycle(3);
        check(cycle.next(100)==0&&cycle.deadline==4100);
        check(!cycle.success(2));
        check(cycle.success(0)&&cycle.remaining()==2);
        check(cycle.next(120)==1&&cycle.deadline==4120);
        check(cycle.next(4120)==2);
        check(cycle.success(2));
        check(cycle.next(4200)==1); // timeout revisits only unread
        check(cycle.next(8200)==1&&cycle.deadline==12200);
        check(cycle.success(1)&&cycle.remaining()==0);
        check(cycle.next(8300)==-1);
        UnreadCycle twenty=new UnreadCycle(20);
        for(int i=0;i<20;i++){check(twenty.next(i*100)==i);check(twenty.success(i));}
        check(twenty.next(2000)==-1);
        UnreadCycle retry=new UnreadCycle(2);check(retry.next(0)==0);
        check(!retry.failed(1)&&retry.failedAttempts==0);
        check(!retry.failed(0));check(!retry.failed(0));check(retry.failed(0));
        check(retry.next(100)==1&&retry.failedAttempts==0);
        check(retry.success(1));check(!retry.failed(1));check(retry.next(200)==0);
        System.out.println("Unread cycle deadlines, success skip, retry and completion passed");
    }
}
