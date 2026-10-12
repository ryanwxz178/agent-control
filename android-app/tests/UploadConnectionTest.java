package dev.orca.pocket;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Deterministic races between file transfer and the real engine connection monitor. */
public final class UploadConnectionTest {
    static final class Transport extends SshTransport {
        volatile boolean open;
        volatile boolean failUpload, failHeartbeat, holdProbe;
        final AtomicInteger probes=new AtomicInteger(), disconnects=new AtomicInteger(), connects=new AtomicInteger();
        final CountDownLatch transferStarted=new CountDownLatch(1), transferRelease=new CountDownLatch(1), probeStarted=new CountDownLatch(1);
        final CountDownLatch probeRelease=new CountDownLatch(1);
        public void connect(String h,int p,String u,String password,byte[] key,Trust trust){open=true;connects.incrementAndGet();}
        public boolean connected(){return open;}
        public int run(String command,String stdin,Line line,AtomicBoolean cancelled,long timeout)throws Exception {
            if(command.equals(Agent.powershell("exit 0"))){probes.incrementAndGet();probeStarted.countDown();if(holdProbe&&!probeRelease.await(3,TimeUnit.SECONDS))throw new AssertionError("Probe fixture was not released");if(failHeartbeat)throw new IOException("heartbeat timed out");return 0;}
            if(!command.contains("New-Item"))for(String row:new String[]{"ORCA_OS|Windows","ORCA_HOME|D:/project","ORCA_AGENT|codex|codex|codex","ORCA_DETECTION_DONE"})line.accept(row);
            return 0;
        }
        public void upload(String path,byte[] bytes)throws Exception {
            transferStarted.countDown();
            if(!transferRelease.await(3,TimeUnit.SECONDS))throw new AssertionError("Transfer fixture was not released");
            if(failUpload){open=false;throw new IOException("Broken pipe");}
        }
        public void disconnect(){open=false;disconnects.incrementAndGet();}
    }
    static final class Host implements PocketEngine.Host {
        final CountDownLatch connected=new CountDownLatch(1);
        public String asset(String name){return "detect fixture";}
        public void event(String type,JSONObject value){if(type.equals("connected"))connected.countDown();}
        public boolean trust(String host,String fp){return false;}
    }
    static PocketEngine connected(Transport transport)throws Exception {
        Host host=new Host();PocketEngine engine=new PocketEngine(host,transport);
        engine.connect(new JSONObject().put("host","pc").put("user","user").put("directory","D:/project"),null);
        if(!host.connected.await(2,TimeUnit.SECONDS))throw new AssertionError("Engine did not connect");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(engine.busy.get()&&System.nanoTime()<deadline)Thread.sleep(5);
        if(engine.busy.get())throw new AssertionError("Initial connection did not complete");
        return engine;
    }
    public static void main(String[] args)throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        Transport transfer=new Transport();PocketEngine engine=connected(transfer);
        try {
            transfer.failHeartbeat=true;
            Future<JSONObject> result=worker.submit(()->engine.uploadInput("photo.jpg","image/jpeg",new byte[]{1,2,3}));
            if(!transfer.transferStarted.await(2,TimeUnit.SECONDS))throw new AssertionError("Upload did not start");
            engine.checkConnection();
            if(transfer.probeStarted.await(250,TimeUnit.MILLISECONDS))throw new AssertionError("Heartbeat ran during upload and can close its SSH session");
            if(transfer.disconnects.get()!=0)throw new AssertionError("Upload's connection was closed");
            engine.connect(new JSONObject().put("host","other").put("user","user"),null);
            if(transfer.connects.get()!=1)throw new AssertionError("Manual reconnect replaced an in-flight upload connection");
            transfer.transferRelease.countDown();
            if(result.get(2,TimeUnit.SECONDS).getLong("size")!=3)throw new AssertionError("Upload metadata was lost");
            transfer.failHeartbeat=false;engine.checkConnection();
            if(!transfer.probeStarted.await(2,TimeUnit.SECONDS))throw new AssertionError("Upload did not release its connection lease");
        } finally {transfer.transferRelease.countDown();engine.close();worker.shutdownNow();}
        Transport failed=new Transport();PocketEngine failedEngine=connected(failed);
        try {
            failed.failUpload=true;failed.transferRelease.countDown();
            try{failedEngine.uploadInput("photo.jpg","image/jpeg",new byte[]{1});throw new AssertionError("Failed upload was accepted");}catch(IOException expected){}
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(failed.connects.get()<2&&System.nanoTime()<deadline)Thread.sleep(5);
            if(failed.connects.get()!=2)throw new AssertionError("Failed upload left the connection lease stuck and prevented recovery");
        } finally {failedEngine.close();}
        Transport checking=new Transport();PocketEngine checkingEngine=connected(checking);ExecutorService waiting=Executors.newSingleThreadExecutor();
        try {
            checking.holdProbe=true;checkingEngine.checkConnection();
            if(!checking.probeStarted.await(2,TimeUnit.SECONDS))throw new AssertionError("Probe did not start");
            Future<JSONObject> result=waiting.submit(()->checkingEngine.uploadInput("photo.jpg","image/jpeg",new byte[]{1}));
            if(checking.transferStarted.await(150,TimeUnit.MILLISECONDS))throw new AssertionError("Upload bypassed an active connection check");
            checking.probeRelease.countDown();checking.transferRelease.countDown();
            if(result.get(2,TimeUnit.SECONDS).getLong("size")!=1)throw new AssertionError("Upload did not resume after connection check");
        } finally {checking.probeRelease.countDown();checking.transferRelease.countDown();checkingEngine.close();waiting.shutdownNow();}
        Transport replaced=new Transport();PocketEngine replacedEngine=connected(replaced);ExecutorService disconnecting=Executors.newSingleThreadExecutor();
        try {
            Future<JSONObject> result=disconnecting.submit(()->replacedEngine.uploadInput("photo.jpg","image/jpeg",new byte[]{1}));
            if(!replaced.transferStarted.await(2,TimeUnit.SECONDS))throw new AssertionError("Upload did not start");
            replacedEngine.disconnect();replaced.open=true;replaced.transferRelease.countDown();
            try{result.get(2,TimeUnit.SECONDS);throw new AssertionError("Upload from a disconnected session was registered");}catch(ExecutionException expected){if(!(expected.getCause() instanceof IllegalStateException))throw expected;}
        } finally {replaced.transferRelease.countDown();replacedEngine.close();disconnecting.shutdownNow();}
        System.out.println("PASS upload connection lease: delayed heartbeat, connection replacement, release on success/failure and recovery without sending work.");
    }
}
