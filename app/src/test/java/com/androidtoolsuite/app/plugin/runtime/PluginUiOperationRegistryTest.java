package com.androidtoolsuite.app.plugin.runtime;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;
import java.util.List;

public final class PluginUiOperationRegistryTest {
    private static PluginUiOperationRegistry.Report report(String id, String status) throws Exception {
        return PluginUiOperationRegistry.Report.parse(new JSONObject().put("id", id).put("label", "同步记录").put("status", status));
    }
    private interface Attempt { void run() throws Exception; }
    private static void rejected(String code, Attempt attempt) throws Exception {
        try { attempt.run(); fail("Expected " + code); }
        catch (CapabilityFailure error) { assertEquals(code, error.code); }
    }

    @Test public void firstReportRequiresVisibleLiveSessionAndGesture() throws Exception {
        PluginUiOperationRegistry registry = new PluginUiOperationRegistry();
        rejected("CANCELLED", () -> registry.report("one", "session", report("op", "running"), false, true, true));
        rejected("CONSENT_REQUIRED", () -> registry.report("one", "session", report("op", "running"), true, false, true));
        rejected("CONSENT_REQUIRED", () -> registry.report("one", "session", report("op", "running"), true, true, false));
        rejected("INVALID_REQUEST", () -> registry.report("one", "session", report("op", "success"), true, true, true));
    }

    @Test public void existingOperationCanFinishInBackgroundButOtherSessionsCannotUpdateIt() throws Exception {
        PluginUiOperationRegistry registry = new PluginUiOperationRegistry();
        PluginUiOperationRegistry.Operation started = registry.report("one", "first", report("op", "running"), true, true, true);
        rejected("INVALID_REQUEST", () -> registry.report("one", "second", report("op", "success"), true, false, false));
        rejected("INVALID_REQUEST", () -> registry.report("two", "first", report("op", "failed"), true, false, false));
        assertEquals("running", started.status);
        PluginUiOperationRegistry.Operation finished = registry.report("one", "first", report("op", "success"), true, false, false);
        assertEquals(started.taskId, finished.taskId);
        assertEquals("one", finished.pluginId);
        assertEquals("success", finished.status);
    }

    @Test public void finishedOperationIsIdempotentButCannotReviveOrChangeItsResult() throws Exception {
        PluginUiOperationRegistry registry = new PluginUiOperationRegistry();
        registry.report("one", "session", report("op", "running"), true, true, true);
        PluginUiOperationRegistry.Operation result = registry.report("one", "session", report("op", "success"), true, false, false);
        assertSame(result, registry.report("one", "session", report("op", "success"), true, false, false));
        rejected("INVALID_REQUEST", () -> registry.report("one", "session", report("op", "running"), true, true, true));
        rejected("INVALID_REQUEST", () -> registry.report("one", "session", report("op", "failed"), true, true, true));
    }

    @Test public void closeCancelsOnlyUnfinishedOperationsInThatSession() throws Exception {
        PluginUiOperationRegistry registry = new PluginUiOperationRegistry();
        registry.report("one", "first", report("op", "running"), true, true, true);
        PluginUiOperationRegistry.Operation finished = registry.report("one", "first", report("done", "running"), true, true, true);
        registry.report("one", "first", report("done", "success"), true, true, true);
        PluginUiOperationRegistry.Operation other = registry.report("one", "second", report("op", "running"), true, true, true);
        List<PluginUiOperationRegistry.Operation> cancelled = registry.closeSession("first");
        assertEquals(1, cancelled.size());
        assertEquals("cancelled", cancelled.get(0).status);
        assertEquals("success", finished.status);
        assertEquals("running", other.status);
        assertTrue(registry.closeSession("first").isEmpty());
        rejected("CANCELLED", () -> registry.report("one", "first", report("op", "running"), false, true, true));
    }

    @Test public void sameClientIdInNewSessionHasIndependentIdentity() throws Exception {
        PluginUiOperationRegistry registry = new PluginUiOperationRegistry();
        String first = registry.report("one", "first", report("op", "running"), true, true, true).taskId;
        String second = registry.report("one", "second", report("op", "running"), true, true, true).taskId;
        assertNotEquals(first, second);
    }

    @Test public void activeCountsAreBoundedAndClosedSessionReleasesItsSlots() throws Exception {
        PluginUiOperationRegistry registry = new PluginUiOperationRegistry();
        for (int session = 0; session < 4; session++) for (int item = 0; item < 4; item++) {
            registry.report("one", "session" + session, report("op" + item, "running"), true, true, true);
        }
        rejected("RESOURCE_LIMIT", () -> registry.report("one", "session0", report("overflow", "running"), true, true, true));
        rejected("RESOURCE_LIMIT", () -> registry.report("one", "session4", report("op", "running"), true, true, true));
        registry.closeSession("session0");
        assertEquals("running", registry.report("one", "session4", report("op", "running"), true, true, true).status);
    }

    @Test public void metadataRejectsPayloadOverridesAndInvalidBounds() throws Exception {
        rejected("INVALID_REQUEST", () -> PluginUiOperationRegistry.Report.parse(new JSONObject()
                .put("id", "op").put("label", "同步").put("status", "running").put("pluginId", "other")));
        rejected("INVALID_REQUEST", () -> PluginUiOperationRegistry.Report.parse(new JSONObject()
                .put("id", "op").put("label", "同步").put("status", "running").put("payload", new JSONObject())));
        rejected("INVALID_REQUEST", () -> report("x".repeat(97), "running"));
        rejected("INVALID_REQUEST", () -> report("op", "unknown"));
        rejected("INVALID_REQUEST", () -> PluginUiOperationRegistry.Report.parse(new JSONObject()
                .put("id", "op").put("label", "同步").put("status", "running").put("detail", "x".repeat(241))));
    }
}
