package local.vivodoubleclick.controller;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/** Reproduces the actual pinned libadb timeout, including cancellation lookalikes. */
public final class AdbConnectionRetryTest {
    private static int checks;
    private static void check(boolean value,String message) { checks++;if(!value)throw new AssertionError(message); }
    private static InterruptedException discoveryTimeout() { return new InterruptedException(AdbConnectionRetry.LIBRARY_DISCOVERY_TIMEOUT); }
    private static void fail() { throw new AssertionError("Expected exception"); }
    public static void main(String[] ignored) throws Exception {
        AtomicInteger attempts=new AtomicInteger(),pauses=new AtomicInteger();
        check(AdbConnectionRetry.connect(()->{if(attempts.incrementAndGet()<3)throw discoveryTimeout();return true;},()->{},pauses::incrementAndGet),"discovery can succeed on third attempt");
        check(attempts.get()==3&&pauses.get()==2,"library timeout really retries rather than aborting as cancellation");
        attempts.set(0);pauses.set(0);
        try { AdbConnectionRetry.connect(()->{attempts.incrementAndGet();throw discoveryTimeout();},()->{},pauses::incrementAndGet);fail(); }
        catch(AdbConnectionRetry.DiscoveryTimeout timeout) {check(timeout.getCause() instanceof InterruptedException,"last discovery failure retains library cause");}
        check(attempts.get()==3&&pauses.get()==2,"exhaustion is bounded");
        attempts.set(0);pauses.set(0);
        InterruptedException real=new InterruptedException();
        try { AdbConnectionRetry.connect(()->{attempts.incrementAndGet();throw real;},()->{},pauses::incrementAndGet);fail(); }
        catch(InterruptedException error) {check(error==real,"real await interruption with cleared flag is never normalized");}
        check(attempts.get()==1&&pauses.get()==0,"real cancellation does not reconnect");
        check(AdbConnectionRetry.classify(new InterruptedException("different timeout")) instanceof InterruptedException,"unknown interrupt message remains cancellation");
        Thread.currentThread().interrupt();
        try {check(AdbConnectionRetry.classify(discoveryTimeout()) instanceof InterruptedException,"concurrent thread interruption wins over timeout classification");}
        finally {Thread.interrupted();}
        attempts.set(0);
        try {AdbConnectionRetry.connect(()->{attempts.incrementAndGet();return true;},()->{throw real;},()->{});fail();}
        catch(InterruptedException expected) {check(attempts.get()==0,"pre-connect cancellation cannot open a connection");}
        attempts.set(0);
        try {AdbConnectionRetry.connect(()->{attempts.incrementAndGet();throw new IOException("synthetic");},()->{},()->{throw real;});fail();}
        catch(InterruptedException expected) {check(attempts.get()==1,"cancellation during retry pause is preserved");}
        attempts.set(0);pauses.set(0);
        check(!AdbConnectionRetry.connect(()->{attempts.incrementAndGet();return false;},()->{},pauses::incrementAndGet),"false connection result eventually fails");
        check(attempts.get()==3&&pauses.get()==2,"false results cannot retry forever");
        // Running -> stop -> reconnect -> start, with discovery initially not ready.
        final boolean[] running={true};
        running[0]=false;attempts.set(0);
        boolean ready=AdbConnectionRetry.connect(()->{if(attempts.incrementAndGet()==1)throw discoveryTimeout();return true;},()->{},()->{});
        if(ready)running[0]=true;
        check(running[0]&&attempts.get()==2,"stop/start can recover from delayed port advertisement");
        for(int cycle=0;cycle<20;cycle++) {
            attempts.set(0);
            check(AdbConnectionRetry.connect(()->{if(attempts.incrementAndGet()==1)throw discoveryTimeout();return true;},()->{},()->{}),"subsequent connection owns a fresh bounded retry");
        }
        System.out.println("AdbConnectionRetryTest: "+checks+" assertions passed");
    }
}
