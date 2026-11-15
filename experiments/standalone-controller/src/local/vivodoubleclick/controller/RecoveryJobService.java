package local.vivodoubleclick.controller;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

public final class RecoveryJobService extends JobService {
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Attempt current;
    static boolean isRunning() { return ACTIVE.get() > 0; }
    private static final class Attempt {
        final JobParameters parameters;
        volatile boolean cancelled;
        Future<?> future;
        Attempt(JobParameters parameters) { this.parameters = parameters; }
    }
    @Override public boolean onStartJob(JobParameters parameters) {
        if (PairingSession.isActive() || !RecoveryScheduler.beginAttempt(this)) return false;
        Attempt attempt = new Attempt(parameters);
        current = attempt;
        attempt.future = worker.submit(() -> {
            boolean retry = false;
            ACTIVE.incrementAndGet();
            try {
                JSONObject result = ControllerRuntime.execute(this, "start", 0, () -> {
                    if (attempt.cancelled || Thread.currentThread().isInterrupted() || !RecoveryScheduler.needed(this))
                        throw new InterruptedException("Recovery cancelled");
                });
                if (!result.optBoolean("ok") || !"RUNNING".equals(result.optString("state"))) throw new IllegalStateException("Daemon not verified running");
                if (!attempt.cancelled) RecoveryScheduler.success(this);
            } catch (Exception failure) {
                if (!attempt.cancelled) {
                    RecoveryScheduler.failed(this);
                    android.app.KeyguardManager keyguard = getSystemService(android.app.KeyguardManager.class);
                    if (keyguard != null && keyguard.isDeviceLocked()) RecoveryScheduler.waitForUnlock(this);
                    else retry = RecoveryScheduler.mayRetry(this);
                }
            } finally {
                ACTIVE.decrementAndGet();
                final boolean reschedule = retry;
                main.post(() -> {
                    if (current != attempt || attempt.cancelled) return;
                    current = null;
                    jobFinished(parameters, reschedule);
                });
            }
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters parameters) {
        Attempt attempt = current;
        if (attempt != null && attempt.parameters.getJobId() == parameters.getJobId()) {
            attempt.cancelled = true;
            if (attempt.future != null) attempt.future.cancel(true);
            current = null;
        }
        return RecoveryScheduler.mayRetry(this);
    }
    @Override public void onDestroy() {
        if (current != null) current.cancelled = true;
        worker.shutdownNow();
        super.onDestroy();
    }
}
