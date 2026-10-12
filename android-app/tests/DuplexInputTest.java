package dev.orca.pocket;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** The writer used by both SSH and local native pipes, without network or model requests. */
public final class DuplexInputTest {
    private static int checks;
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static void await(CountDownLatch latch)throws Exception{if(!latch.await(2,TimeUnit.SECONDS))throw new AssertionError("Fixture did not progress");}
    private interface Throwing {void run()throws Exception;}
    private static void rejects(Throwing action)throws Exception{try{action.run();throw new AssertionError("Write should fail closed");}catch(IOException expected){check(expected.getMessage().contains("不会自动重发"),"uncertain writes cannot trigger automatic retries");}}
    public static void main(String[] args)throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),flushed=new CountDownLatch(1);
        ByteArrayOutputStream received=new ByteArrayOutputStream();List<Integer> flushPositions=new ArrayList<>();AtomicInteger aborts=new AtomicInteger();
        OutputStream slow=new OutputStream(){
            public void write(int value)throws IOException{write(new byte[]{(byte)value},0,1);}
            public void write(byte[] bytes,int offset,int length)throws IOException{
                entered.countDown();try{if(!release.await(2,TimeUnit.SECONDS))throw new IOException("Fixture writer was not released");}catch(InterruptedException stopped){throw new IOException(stopped);}
                synchronized(received){received.write(bytes,offset,length);}
            }
            public void flush(){synchronized(received){flushPositions.add(received.size());if(received.size()==4)flushed.countDown();}}
        };
        SshTransport.DuplexInput input=new SshTransport.DuplexInput(slow,aborts::incrementAndGet);
        try{
            byte[] first={1,2};input.write(first);first[0]=9;input.flush();await(entered);
            long start=System.nanoTime();input.write(new byte[]{3,4});input.flush();
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<200,"protocol reader keeps running while native stdin is blocked");
            release.countDown();await(flushed);
            check(Arrays.equals(received.toByteArray(),new byte[]{1,2,3,4}),"ordered copied bytes cannot be mutated after enqueue");
            check(flushPositions.equals(Arrays.asList(2,4)),"flushes retain ordering around successive JSON requests");
            input.checkError();check(aborts.get()==0,"ordinary queued writes do not abort the owned process");
        }finally{release.countDown();input.close();input.awaitStopped();}
        rejects(()->input.write(new byte[]{5}));

        CountDownLatch aborted=new CountDownLatch(1);AtomicInteger failureAborts=new AtomicInteger();
        OutputStream broken=new OutputStream(){public void write(int value)throws IOException{throw new IOException("synthetic broken pipe");}};
        SshTransport.DuplexInput failed=new SshTransport.DuplexInput(broken,()->{failureAborts.incrementAndGet();aborted.countDown();});
        try{
            failed.write(new byte[]{1});await(aborted);rejects(failed::checkError);rejects(()->failed.write(new byte[]{2}));
            check(failureAborts.get()==1,"asynchronous write failure unblocks the reader once and never replays bytes");
        }finally{failed.close();failed.awaitStopped();}

        CountDownLatch blocked=new CountDownLatch(1),waitForever=new CountDownLatch(1);AtomicInteger closingAborts=new AtomicInteger();
        OutputStream stalled=new OutputStream(){public void write(int value)throws IOException{blocked.countDown();try{waitForever.await();}catch(InterruptedException closing){throw new IOException(closing);}}};
        SshTransport.DuplexInput bounded=new SshTransport.DuplexInput(stalled,closingAborts::incrementAndGet);
        try{
            bounded.write(new byte[]{1});await(blocked);
            for(int i=0;i<SshTransport.DuplexInput.MAX_PENDING_WRITES;i++)bounded.flush();
            rejects(bounded::flush);
            check(closingAborts.get()==0,"queue overflow rejects the producer instead of replaying or launching another process");
        }finally{long start=System.nanoTime();bounded.close();bounded.awaitStopped();check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1200,"closing a blocked writer is bounded");}
        check(closingAborts.get()==0,"intentional close cannot turn into a second abort action");
        System.out.println("PASS "+checks+" duplex input checks: reader independence, exact ordering, owned bytes, failure propagation, bounded queues and close cleanup.");
    }
}
