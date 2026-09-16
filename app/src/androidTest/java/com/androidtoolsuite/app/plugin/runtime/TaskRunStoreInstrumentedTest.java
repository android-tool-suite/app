package com.androidtoolsuite.app.plugin.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.androidtoolsuite.runtime.contract.OriginKey;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

@RunWith(AndroidJUnit4.class)
public final class TaskRunStoreInstrumentedTest {
    private static final String PLUGIN_ID = "test.scheduler_history";
    private static final String TASK_ID = "refresh-summary";

    private Context context;
    private TaskRunStore store;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        deleteRecursively(pluginDirectory());
        store = new TaskRunStore(context);
    }

    @After
    public void tearDown() throws Exception {
        deleteRecursively(pluginDirectory());
    }

    @Test
    public void historySurvivesStoreRecreationAndKeepsBoundedStatusOnlyOutput() throws Exception {
        TaskRunStore.Run run = store.create(
                PLUGIN_ID,
                "generation-a",
                TASK_ID,
                new JSONObject(),
                "manual"
        );
        store.markRunning(PLUGIN_ID, TASK_ID, run.runId, 1);
        store.markRetrying(PLUGIN_ID, TASK_ID, run.runId, 1, "TEMPORARY", "稍后重试");
        store.markRunning(PLUGIN_ID, TASK_ID, run.runId, 2);
        store.markSucceeded(
                PLUGIN_ID,
                TASK_ID,
                run.runId,
                new JSONObject().put("itemsProcessed", 3)
        );

        JSONObject last = new TaskRunStore(context).lastRun(PLUGIN_ID, TASK_ID);
        assertEquals("succeeded", last.getString("status"));
        assertEquals(2, last.getInt("attempt"));
        assertEquals("manual", last.getString("source"));
        assertEquals(3, last.getJSONObject("output").getInt("itemsProcessed"));
        assertEquals(0, last.getJSONObject("input").length());
    }

    @Test
    public void leaseEnforcesConcurrencyAndIsReleasedByOwner() throws Exception {
        TaskRunStore.Run first = store.create(
                PLUGIN_ID, "generation-a", TASK_ID, new JSONObject(), "manual"
        );
        TaskRunStore.Run second = store.create(
                PLUGIN_ID, "generation-a", TASK_ID, new JSONObject(), "manual"
        );

        TaskRunStore.Lease lease = store.acquire(PLUGIN_ID, TASK_ID, first.runId, 1, 60_000L);
        assertNotNull(lease);
        assertNull(store.acquire(PLUGIN_ID, TASK_ID, second.runId, 1, 60_000L));
        lease.close();
        TaskRunStore.Lease replacement = store.acquire(
                PLUGIN_ID, TASK_ID, second.runId, 1, 60_000L
        );
        assertNotNull(replacement);
        replacement.close();
    }

    @Test
    public void cancellingActiveRunsDoesNotRewriteCompletedHistory() throws Exception {
        TaskRunStore.Run queued = store.create(
                PLUGIN_ID, "generation-a", TASK_ID, new JSONObject(), "provider-event"
        );
        TaskRunStore.Run completed = store.create(
                PLUGIN_ID, "generation-a", TASK_ID, new JSONObject(), "periodic"
        );
        store.markSucceeded(PLUGIN_ID, TASK_ID, completed.runId, new JSONObject());

        store.cancelActive(PLUGIN_ID, TASK_ID);

        assertEquals("cancelled", store.read(PLUGIN_ID, TASK_ID, queued.runId).record.getString("status"));
        assertEquals("succeeded", store.read(PLUGIN_ID, TASK_ID, completed.runId).record.getString("status"));
    }

    @Test
    public void terminalLeaseOwnerIsReclaimedWithoutWaitingForAgeTimeout() throws Exception {
        TaskRunStore.Run interrupted = store.create(
                PLUGIN_ID, "generation-a", TASK_ID, new JSONObject(), "periodic"
        );
        TaskRunStore.Lease abandoned = store.acquire(
                PLUGIN_ID, TASK_ID, interrupted.runId, 1, 15 * 60_000L
        );
        assertNotNull(abandoned);
        store.markFailed(
                PLUGIN_ID, TASK_ID, interrupted.runId, 1, "CANCELLED", "worker stopped", true
        );

        TaskRunStore.Run retry = store.create(
                PLUGIN_ID, "generation-a", TASK_ID, new JSONObject(), "manual"
        );
        TaskRunStore.Lease replacement = store.acquire(
                PLUGIN_ID, TASK_ID, retry.runId, 1, 15 * 60_000L
        );
        assertNotNull(replacement);
        abandoned.close();
        replacement.close();
    }

    private File pluginDirectory() throws Exception {
        return new File(
                new File(context.getFilesDir(), "runtime-v2/task-runs"),
                OriginKey.fromPluginId(PLUGIN_ID)
        );
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
