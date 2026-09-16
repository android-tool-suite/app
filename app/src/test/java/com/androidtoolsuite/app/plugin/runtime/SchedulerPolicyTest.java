package com.androidtoolsuite.app.plugin.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SchedulerPolicyTest {
    @Test
    public void schedulesOnlyEnabledAndAuthorizedPlugins() {
        assertTrue(SchedulerPolicy.canSchedule(true, true));
        assertFalse(SchedulerPolicy.canSchedule(false, true));
        assertFalse(SchedulerPolicy.canSchedule(true, false));
    }

    @Test
    public void mapsPeriodicAndConditionalTriggersToWorkManagerIntervals() {
        assertEquals(15L, SchedulerPolicy.scheduledIntervalMinutes("periodic", 1L));
        assertEquals(90L, SchedulerPolicy.scheduledIntervalMinutes("periodic", 90L));
        assertEquals(15L, SchedulerPolicy.scheduledIntervalMinutes("network-available", 0L));
        assertEquals(15L, SchedulerPolicy.scheduledIntervalMinutes("charging", 0L));
        assertEquals(0L, SchedulerPolicy.scheduledIntervalMinutes("manual", 0L));
        assertEquals(0L, SchedulerPolicy.scheduledIntervalMinutes("provider-event", 0L));
    }

    @Test
    public void triggerConstraintsStrengthenButNeverWeakenDeclarations() {
        assertEquals("connected", SchedulerPolicy.effectiveNetwork("none", "network-available"));
        assertEquals("unmetered", SchedulerPolicy.effectiveNetwork("unmetered", "network-available"));
        assertEquals("none", SchedulerPolicy.effectiveNetwork("none", "periodic"));
        assertTrue(SchedulerPolicy.requiresCharging(false, "charging"));
        assertTrue(SchedulerPolicy.requiresCharging(true, "periodic"));
        assertFalse(SchedulerPolicy.requiresCharging(false, "periodic"));
    }

    @Test
    public void retryAndLeaseRulesAreBounded() {
        assertTrue(SchedulerPolicy.shouldRetry(true, 1, 3, false));
        assertFalse(SchedulerPolicy.shouldRetry(false, 1, 3, false));
        assertFalse(SchedulerPolicy.shouldRetry(true, 3, 3, false));
        assertFalse(SchedulerPolicy.shouldRetry(true, 1, 3, true));
        assertEquals(15L * 60_000L, SchedulerPolicy.leaseStaleAfterMillis(10_000L));
        assertEquals(21L * 60_000L, SchedulerPolicy.leaseStaleAfterMillis(20L * 60_000L));
    }

    @Test
    public void onlyQueuedRunningAndRetryingRunsAreActive() {
        assertTrue(SchedulerPolicy.isActiveStatus("queued"));
        assertTrue(SchedulerPolicy.isActiveStatus("running"));
        assertTrue(SchedulerPolicy.isActiveStatus("retrying"));
        assertFalse(SchedulerPolicy.isActiveStatus("succeeded"));
        assertFalse(SchedulerPolicy.isActiveStatus("failed"));
        assertFalse(SchedulerPolicy.isActiveStatus("cancelled"));
    }

    @Test
    public void providerEventsRequireBothCapabilityAndEventToMatch() {
        assertTrue(SchedulerPolicy.matchesProviderEvent(
                "sample.source", "changed", "sample.source", "changed"
        ));
        assertFalse(SchedulerPolicy.matchesProviderEvent(
                "sample.source", "changed", "sample.other", "changed"
        ));
        assertFalse(SchedulerPolicy.matchesProviderEvent(
                "sample.source", "changed", "sample.source", "deleted"
        ));
    }
}
