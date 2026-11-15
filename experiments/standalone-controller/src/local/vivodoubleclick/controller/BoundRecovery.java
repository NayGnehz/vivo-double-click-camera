package local.vivodoubleclick.controller;

import android.content.Context;
import android.os.Handler;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Event-driven worker whose lifetime belongs to the system-bound accessibility helper. */
final class BoundRecovery {
    private final Context context;
    private final Handler main;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private RecoveryGate.Ticket current;
    private final Runnable retry = this::request;
    BoundRecovery(Context context, Handler main) { this.context = context; this.main = main; }
    void request() {
        if (closed || PairingSession.isActive() || !RecoveryScheduler.mayRetry(context)
                || !AutomaticRecovery.wifi(context)) return;
        if (AutomaticRecovery.locked(context)) { RecoveryScheduler.waitForUnlock(context); return; }
        RecoveryGate.Ticket ticket = AutomaticRecovery.GATE.claim();
        if (ticket == null) return;
        current = ticket;
        main.removeCallbacks(retry);
        try {
            worker.execute(() -> {
                boolean again = AutomaticRecovery.run(context, ticket);
                main.post(() -> {
                    if (current == ticket) current = null;
                    if (closed) return;
                    if (again && RecoveryScheduler.mayRetry(context)) {
                        RecoveryScheduler.schedule(context);
                        main.postDelayed(retry, 5_000L);
                    }
                });
            });
        } catch (RejectedExecutionException closing) { AutomaticRecovery.GATE.release(ticket); }
    }
    void close() {
        closed = true;
        main.removeCallbacks(retry);
        if (current != null) { current.cancel(); AutomaticRecovery.GATE.release(current); }
        worker.shutdownNow();
    }
}
