package local.vivodoubleclick.controller;

/** An attempt belongs to its owner; delayed completion cannot release a newer one. */
final class RecoveryGate {
    static final class Ticket {
        private volatile boolean cancelled;
        private Thread thread;
        synchronized void attach() { thread = Thread.currentThread(); if (cancelled) thread.interrupt(); }
        synchronized void detach() { thread = null; }
        synchronized void cancel() { cancelled = true; if (thread != null) thread.interrupt(); }
        boolean cancelled() { return cancelled; }
    }
    private Ticket active;
    synchronized Ticket claim() {
        if (active != null) return null;
        active = new Ticket(); return active;
    }
    synchronized boolean busy() { return active != null; }
    synchronized void release(Ticket ticket) { if (active == ticket) active = null; }
    synchronized void cancel() { if (active != null) active.cancel(); }
}
