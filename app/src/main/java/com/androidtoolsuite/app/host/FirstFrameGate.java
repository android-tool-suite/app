package com.androidtoolsuite.app.host;

/** A one-shot, bounded wait measured from the first drawable frame, not Activity creation. */
final class FirstFrameGate {
    private static final long MAX_WAIT_MS = 150L;
    private long startedAt = -1L;
    private boolean released;

    boolean keepOnScreen(long now, boolean pending) {
        if (released) return false;
        if (startedAt < 0L) startedAt = now;
        if (!pending || now - startedAt >= MAX_WAIT_MS) {
            released = true;
            return false;
        }
        return true;
    }
}
