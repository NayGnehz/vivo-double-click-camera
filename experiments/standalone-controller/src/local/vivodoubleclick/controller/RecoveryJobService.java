package local.vivodoubleclick.controller;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Persistent fallback; the connected helper can restore immediately without waiting for a job. */
public final class RecoveryJobService extends JobService {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Attempt current;
    private static final class Attempt {
        final JobParameters parameters;
        final RecoveryGate.Ticket ticket;
        Future<?> future;
        Attempt(JobParameters parameters, RecoveryGate.Ticket ticket) { this.parameters = parameters; this.ticket = ticket; }
        void cancel() { ticket.cancel(); if (future != null) future.cancel(true); AutomaticRecovery.GATE.release(ticket); }
    }
    @Override public boolean onStartJob(JobParameters parameters) {
        if (PairingSession.isActive() || !RecoveryScheduler.needed(this)) return false;
        RecoveryGate.Ticket ticket = AutomaticRecovery.GATE.claim();
        if (ticket == null) return false;
        Attempt attempt = new Attempt(parameters, ticket);
        current = attempt;
        attempt.future = worker.submit(() -> {
            final boolean retry = AutomaticRecovery.run(this, ticket);
            main.post(() -> {
                if (current != attempt || ticket.cancelled()) return;
                current = null;
                jobFinished(parameters, retry);
            });
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters parameters) {
        Attempt attempt = current;
        if (attempt == null || attempt.parameters.getJobId() != parameters.getJobId()
                || !RecoveryPolicy.sameJob(attempt.parameters.getExtras().getInt("boot", -2),
                    attempt.parameters.getExtras().getLong("generation", -1),
                    parameters.getExtras().getInt("boot", -2), parameters.getExtras().getLong("generation", -1))) return false;
        attempt.cancel(); current = null;
        return RecoveryScheduler.mayRetry(this);
    }
    @Override public void onDestroy() {
        if (current != null) current.cancel();
        worker.shutdownNow();
        super.onDestroy();
    }
}
