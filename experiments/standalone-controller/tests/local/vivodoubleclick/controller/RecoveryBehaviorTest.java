package local.vivodoubleclick.controller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Regression tests for delayed connectivity, approval scope, shutdown order and failures. */
public final class RecoveryBehaviorTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static final class State { boolean ok = true, running = true; }
    private static final class Gateway implements ControllerFlow.Gateway<State> {
        final List<String> calls = new ArrayList<>();
        final State state = new State();
        String failure = "";
        private void call(String name) throws Exception {
            calls.add(name); if (failure.equals(name)) throw new Exception("synthetic failure");
        }
        public void connect() throws Exception { call("connect"); }
        public void ensureUsb() throws Exception { call("usb"); }
        public State perform(String action) throws Exception { call(action); return state; }
        public boolean successful(State result) { return result.ok; }
        public boolean running(State result) { return result.running; }
        public void closeWireless() throws Exception { call("wireless-off"); }
        public void close() { calls.add("close-connection"); }
    }
    private static void expectFailure(Gateway gateway, String action, ControllerFlow.Cancellation cancel) throws Exception {
        try { ControllerFlow.run(action, gateway, cancel); throw new AssertionError("Expected failure"); }
        catch (AssertionError error) { throw error; }
        catch (Exception expected) { check(gateway.calls.get(gateway.calls.size()-1).equals("close-connection"), "failure releases connection"); }
    }
    private static void recovery() {
        check(RecoveryPolicy.needed(true,true,true,10,9,9), "new boot waits for network even hours later");
        check(!RecoveryPolicy.needed(false,true,true,10,9,9), "disabled feature");
        check(!RecoveryPolicy.needed(true,false,true,10,9,9), "never creates an unpaired identity on boot");
        check(!RecoveryPolicy.needed(true,true,false,10,9,9), "wait for first unlock");
        check(!RecoveryPolicy.needed(true,true,true,10,10,9), "completed boot does not restart on later networks");
        check(!RecoveryPolicy.needed(true,true,true,10,9,10), "manual stop suppresses this boot");
        check(RecoveryPolicy.needed(true,true,true,11,10,10), "manual stop does not suppress the next boot");
        check(!RecoveryPolicy.needed(true,true,true,-1,9,9), "unknown boot fails closed");
        for(int attempt=0;attempt<3;attempt++) check(RecoveryPolicy.mayAttempt(attempt),"bounded retry accepted");
        check(!RecoveryPolicy.mayAttempt(3),"no fourth attempt on same connection");
        check(!RecoveryPolicy.mayAttempt(100),"no endless retry");
        check(RecoveryPolicy.recentSuccess(10,10,10_000,6_000),"fresh verified result reusable by UI");
        check(!RecoveryPolicy.recentSuccess(11,10,10_000,6_000),"previous boot result is stale");
        check(!RecoveryPolicy.recentSuccess(10,10,20_000,6_000),"old status requires verification");
        check(!RecoveryPolicy.recentSuccess(10,10,1_000,6_000),"elapsed clock reset rejected");
    }
    private static WifiApprovalPolicy.Action approval(boolean armed,boolean unlocked,boolean pkg,boolean title,boolean checkbox,boolean checked,boolean button,boolean clicked,boolean unchecked) {
        return WifiApprovalPolicy.decide(armed,unlocked,pkg,title,checkbox,checked,button,clicked,unchecked);
    }
    private static void approvals() {
        check(!AccessibilityWorkPolicy.needsWindowEvents(false,false),"idle helper does not process windows");
        check(AccessibilityWorkPolicy.needsWindowEvents(true,false),"pairing activates events temporarily");
        check(AccessibilityWorkPolicy.needsWindowEvents(false,true),"network approval activates events temporarily");
        check(AccessibilityWorkPolicy.needsWindowEvents(true,true),"overlapping work shares event subscription");
        check(approval(true,true,true,true,true,false,true,false,false)==WifiApprovalPolicy.Action.ALLOW_ONCE,"temporary allow only");
        check(approval(true,true,true,true,true,true,true,false,false)==WifiApprovalPolicy.Action.UNCHECK_ALWAYS,"uncheck permanent approval first");
        check(approval(true,true,true,true,true,false,true,false,true)==WifiApprovalPolicy.Action.ALLOW_ONCE,"allow only after unchecked readback");
        check(approval(true,true,true,true,true,true,true,false,true)==WifiApprovalPolicy.Action.NONE,"never click allow on failed uncheck");
        check(approval(false,true,true,true,true,false,true,false,false)==WifiApprovalPolicy.Action.NONE,"idle service cannot click");
        check(approval(true,false,true,true,true,false,true,false,false)==WifiApprovalPolicy.Action.NONE,"no lockscreen automation");
        check(approval(true,true,false,true,true,false,true,false,false)==WifiApprovalPolicy.Action.NONE,"unrelated application ignored");
        check(approval(true,true,true,false,true,false,true,false,false)==WifiApprovalPolicy.Action.NONE,"unrelated Allow dialog ignored");
        check(approval(true,true,true,true,false,false,true,false,false)==WifiApprovalPolicy.Action.NONE,"missing checkbox fails closed");
        check(approval(true,true,true,true,true,false,false,false,false)==WifiApprovalPolicy.Action.NONE,"missing positive button");
        check(approval(true,true,true,true,true,false,true,true,false)==WifiApprovalPolicy.Action.NONE,"one Allow attempt per session");
    }
    private static void flow() throws Exception {
        Gateway g=new Gateway();
        ControllerFlow.run("start",g,()->{});
        check(g.calls.equals(Arrays.asList("connect","usb","start","usb","wireless-off","close-connection")),"startup verifies USB before disabling wireless");
        g=new Gateway(); ControllerFlow.run("status",g,()->{});
        check(g.calls.equals(Arrays.asList("connect","status","usb","wireless-off","close-connection")),"verified running status uses same cleanup");
        g=new Gateway();g.state.running=false;ControllerFlow.run("stop",g,()->{});
        check(g.calls.equals(Arrays.asList("connect","stop","close-connection")),"stop does not enable USB or close UI as running");
        g=new Gateway();g.state.ok=false;ControllerFlow.run("start",g,()->{});
        check(!g.calls.contains("wireless-off"),"failed status cannot authorize successful exit");
        g=new Gateway();g.state.running=false;ControllerFlow.run("status",g,()->{});
        check(!g.calls.contains("usb"),"stopped query does not change USB");
        for(String failure:new String[]{"connect","usb","start","wireless-off"}) {
            g=new Gateway();g.failure=failure;expectFailure(g,"start",()->{});
        }
        g=new Gateway();expectFailure(g,"start",()->{throw new InterruptedException();});
        check(g.calls.equals(Arrays.asList("close-connection")),"cancelled job cannot connect or launch");
        final int[] phases={0};g=new Gateway();
        expectFailure(g,"start",()->{if(++phases[0]==3)throw new InterruptedException();});
        check(!g.calls.contains("start"),"manual-stop cancellation prevents daemon start");
        check(ControllerFlow.shouldClose(true,true),"verified success closes UI");
        check(!ControllerFlow.shouldClose(false,true),"unconfirmed running does not close UI");
        check(!ControllerFlow.shouldClose(true,false),"stopped UI stays open");
        check(ControllerFlow.CLOSE_DELAY_MS==1000L,"visible success is retained for one second");
    }
    private static void reopen() {
        // Repeated opens, including hours after verification, retain only a
        // historical launch state. A recent-success window is never stretched.
        for (int open=0; open<50; open++) {
            long now=10_000L+open*360_000L;
            check(ControlStatePolicy.saved(10,10,-2,now,6_000)==ControlStatePolicy.Saved.STARTED,"same-boot launch survives reopening");
            check(AccessibilityWorkPolicy.eventMask(false)!=0,"idle subscription remains valid on every return");
            check(!AccessibilityWorkPolicy.needsWindowEvents(false,false),"reopening does not scan windows");
        }
        check(ControlStatePolicy.saved(11,10,-2,10000,6000)==ControlStatePolicy.Saved.UNKNOWN,"reboot invalidates saved launch");
        check(ControlStatePolicy.saved(-1,-1,-1,10000,6000)==ControlStatePolicy.Saved.UNKNOWN,"unknown boot is never running");
        check(ControlStatePolicy.saved(10,10,10,10000,6000)==ControlStatePolicy.Saved.STOPPED,"confirmed stop replaces launch");
        check(ControlStatePolicy.saved(10,10,-2,1000,6000)==ControlStatePolicy.Saved.UNKNOWN,"invalid elapsed time rejected");
        check(ControlStatePolicy.saved(10,10,-2,10000,0)==ControlStatePolicy.Saved.UNKNOWN,"no verification cannot claim launch");
        check((AccessibilityWorkPolicy.eventMask(false)&0x800)==0,"idle excludes content-change events");
        check((AccessibilityWorkPolicy.eventMask(true)&0x800)!=0,"active authorization receives content updates");
        check(!RecoveryPolicy.recentSuccess(10,10,60_000,6000),"historical state cannot trigger verified-success exit");
    }
    public static void main(String[] ignored) throws Exception {
        recovery();approvals();flow();reopen();
        System.out.println("RecoveryBehaviorTest: "+checks+" assertions passed");
    }
}
