package com.androidtoolsuite.app.plugin.runtime;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.androidtoolsuite.runtime.contract.RuntimePluginManifest;
import com.androidtoolsuite.app.host.MainActivity;
import android.view.ViewGroup;
import java.io.File;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises real snapshot scheduling/consent with a controlled live status provider. */
@RunWith(AndroidJUnit4.class)
public final class WidgetStartupInstrumentedTest {
    private static final String ID = "test.widget_startup";
    private PluginRuntime runtime;
    private WidgetSnapshotStore store;
    private WidgetSnapshotStore.Entry entry;
    private RuntimePluginManifest manifest;
    private PluginPackageStore.InstalledPlugin installed;
    private AutoCloseable registration;
    private AutoCloseable observation;
    private final CountDownLatch called = new CountDownLatch(1);
    private final AtomicInteger calls = new AtomicInteger();
    private volatile boolean offline;

    @Before public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        runtime = PluginRuntime.get(context);
        manifest = RuntimePluginManifest.parse("""
            {"format":"ats-plugin","formatVersion":3,
             "plugin":{"id":"test.widget_startup","title":"Startup fixture","description":"Status regression",
               "version":"1.0.0","versionCode":1,"minHostVersionCode":24,"publisher":"test.publisher","kind":"tool"},
             "platforms":["android"],
             "runtime":{"ui":[{"id":"main","type":"declarative","entry":"ui/main.json"}]},
             "requires":{"capabilities":[{"id":"shizuku.control","version":"^1.0.0","optional":false,"scopes":{}}]},
             "contributes":{"tools":[{"id":"main","uiEntry":"main"}],
               "homeWidgets":[{"id":"status","title":"Live status","template":"status",
               "dataSource":"shizuku.getConnection","sizes":["2x2"]}]}}
            """);
        runtime.permissions().reconcile(manifest);
        runtime.permissions().setGranted(manifest, "shizuku.control", true);
        registration = runtime.capabilities().register(new CapabilityProvider() {
            public String capabilityId() { return "shizuku.control"; }
            public String version() { return "1.0.0"; }
            public Set<String> methods() { return Set.of("shizuku.getConnection"); }
            public JSONObject call(CapabilityCall call) throws CapabilityFailure {
                calls.incrementAndGet();
                called.countDown();
                if (offline) throw new CapabilityFailure("PROVIDER_OFFLINE", "Service stopped", true);
                try { return new JSONObject().put("state", "ready").put("connected", true).put("title", "Ready"); }
                catch (Exception error) { throw new AssertionError(error); }
            }
        }, ID, 1000);
        main(() -> {
            store = runtime.widgetSnapshots();
            store.setVisible(true);
            installed = new PluginPackageStore.InstalledPlugin(manifest,
                new File(context.getCacheDir(), ID), new File(context.getCacheDir(), ID + ".atsplugin"),
                true, "test", "", false);
            entry = store.entry(installed, manifest.homeWidgetContributions.get(0));
        });
    }

    @After public void tearDown() throws Exception {
        if (observation != null) observation.close();
        if (registration != null) registration.close();
        if (store != null) main(() -> store.reconcile(runtime.packages().load()));
        runtime.permissions().removePlugin(ID);
    }

    @Test public void firstLiveReadStartsBeforeFirstFrameAndObservationDoesNotRestartIt() throws Exception {
        main(() -> {
            store.hostChanged(entry, 1);
            // The UI thread has not yielded to draw or a delayed callback yet.
            try { assertTrue("First query was deferred until after the first frame", called.await(1, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { throw new AssertionError(error); }
            observation = store.observe(entry);
            store.hostChanged(entry, 1);
        });
        awaitState(true);
        assertEquals(1, calls.get());
        Context context = ApplicationProvider.getApplicationContext();
        assertFalse("A live authorization must not be restored as confirmed on cold start",
            context.getSharedPreferences("runtime-widget-snapshots", Context.MODE_PRIVATE).contains(ID + "/status"));
    }

    @Test public void connectionFailureAndRevocationStillClearConfirmedState() throws Exception {
        main(() -> { store.hostChanged(entry, 1); observation = store.observe(entry); });
        awaitState(true);
        offline = true;
        main(() -> store.hostChanged(entry, 2));
        awaitState(false);
        offline = false;
        main(() -> store.hostChanged(entry, 3));
        awaitState(true);
        runtime.permissions().setGranted(manifest, "shizuku.control", false);
        awaitState(false);
        assertFalse(runtime.permissions().isGranted(manifest, "shizuku.control"));
    }

    @Test public void fetchedResultWaitsForCardCompositionBeforeReleasingFirstFrame() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                entry = store.entry(installed, manifest.homeWidgetContributions.get(0));
                store.setVisible(true);
                store.hostChanged(entry, 1);
            });
            awaitState(true);
            main(() -> assertTrue("A fetched result is not yet a rendered card", entry.isAwaitingFirstPresentation()));
            scenario.onActivity(activity -> {
                HostHomeWidget widget = new DeclarativeToolPlugin(installed, activity)
                    .createHomeWidgets(activity, activity).get(0);
                activity.addContentView(widget.createView(activity, activity), new ViewGroup.LayoutParams(400, 400));
            });
            for (int i = 0; i < 60; i++) {
                boolean[] pending = {true};
                main(() -> pending[0] = entry.isAwaitingFirstPresentation());
                if (!pending[0]) return;
                SystemClock.sleep(50);
            }
            fail("Card composition did not acknowledge its first result");
        }
    }

    private void awaitState(boolean ready) {
        for (int i = 0; i < 60; i++) {
            boolean[] done = {false};
            main(() -> done[0] = ready ? entry.getValue() != null && entry.getError() == null
                : entry.getValue() == null && entry.getError() != null);
            if (done[0]) return;
            SystemClock.sleep(50);
        }
        fail("Live widget did not reach expected state: ready=" + ready);
    }

    private static void main(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
}
