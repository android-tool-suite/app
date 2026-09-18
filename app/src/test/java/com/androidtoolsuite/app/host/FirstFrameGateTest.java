package com.androidtoolsuite.app.host;

import static org.junit.Assert.*;
import org.junit.Test;

public final class FirstFrameGateTest {
    @Test public void readyOrEmptyDashboardDoesNotWait() {
        FirstFrameGate gate = new FirstFrameGate();
        assertFalse(gate.keepOnScreen(10_000, false));
        assertFalse("Later refreshes cannot cover the UI again", gate.keepOnScreen(10_010, true));
    }

    @Test public void releasesAsSoonAsContentOrErrorIsPresented() {
        FirstFrameGate gate = new FirstFrameGate();
        assertTrue(gate.keepOnScreen(10_000, true));
        assertTrue(gate.keepOnScreen(10_040, true));
        assertFalse(gate.keepOnScreen(10_041, false));
        assertFalse(gate.keepOnScreen(10_050, true));
    }

    @Test public void slowOrUnresponsiveProviderCannotExtendTheDeadline() {
        FirstFrameGate gate = new FirstFrameGate();
        assertTrue(gate.keepOnScreen(10_000, true));
        assertTrue(gate.keepOnScreen(10_149, true));
        assertFalse(gate.keepOnScreen(10_150, true));
        assertFalse(gate.keepOnScreen(10_160, true));
        assertFalse(gate.keepOnScreen(20_000, true));
    }
}
