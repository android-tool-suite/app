package com.androidtoolsuite.app.plugin.runtime;

import static org.junit.Assert.*;

import android.os.SystemClock;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.androidtoolsuite.app.host.MainActivity;
import com.androidtoolsuite.runtime.contract.RuntimePluginManifest;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** A real local WebView uses the production bridge; no system permission or account is involved. */
@RunWith(AndroidJUnit4.class)
public final class WebBackNavigationInstrumentedTest {
    private static final String ID = "test.native_back";
    private static final String SCRIPT = """
        (() => {
          const pluginId = document.querySelector('meta[name="ats-plugin-id"]').content;
          const sessionId = document.querySelector('meta[name="ats-session-id"]').content;
          let nextId = 0;
          let root = false;
          window.atsTransport.onmessage = ({data}) => {
            const message = JSON.parse(data);
            if (message.kind === 'ready') configure(true);
            if (message.kind === 'response') document.getElementById('state').textContent = message.ok ? root ? 'root' : 'child' : 'error:' + message.error.code;
            if (message.kind === 'event' && message.event === 'app.backRequested') {
              root = true;
              configure(false);
            }
          };
          function configure(enabled) {
            window.atsTransport.postMessage(JSON.stringify({protocol:'2.0',kind:'request',pluginId,sessionId,
              requestId:String(++nextId),method:'app.setBackNavigation',payload:{enabled},deadlineMs:5000}));
          }
          window.atsTransport.postMessage(JSON.stringify({protocol:'2.0',kind:'hello',pluginId,sessionId,
            requestId:'0',payload:{supportedProtocols:['2.0'],features:[]}}));
        })();
        """;

    @Test public void liveDeclarativeWebPageConsumesBackThenReturnsToHost() throws Exception {
        final File[] directory = {null};
        final DeclarativeToolPlugin[] tool = {null};
        final MainActivity[] owner = {null};
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            try {
                scenario.onActivity(activity -> {
                    owner[0] = activity;
                    try {
                        directory[0] = Files.createTempDirectory(activity.getCacheDir().toPath(), "native-back-").toFile();
                        File ui = new File(directory[0], "ui"); assertTrue(ui.mkdir());
                        File web = new File(directory[0], "web"); assertTrue(web.mkdir());
                        Files.write(new File(ui, "main.json").toPath(),
                            "{\"formatVersion\":1,\"body\":{\"type\":\"webview\",\"entry\":\"web/index.html\"}}".getBytes(StandardCharsets.UTF_8));
                        Files.write(new File(web, "index.html").toPath(),
                            "<!doctype html><html><head><meta charset=\"utf-8\"></head><body><p id=\"state\">starting</p><script src=\"test.js\"></script></body></html>".getBytes(StandardCharsets.UTF_8));
                        Files.write(new File(web, "test.js").toPath(), SCRIPT.getBytes(StandardCharsets.UTF_8));
                        RuntimePluginManifest manifest = RuntimePluginManifest.parse("""
                            {"format":"ats-plugin","formatVersion":3,
                             "plugin":{"id":"test.native_back","title":"Native Back test","description":"Local bridge test",
                               "version":"1.0.0","versionCode":1,"minHostVersionCode":24,"publisher":"test.publisher","kind":"tool"},
                             "platforms":["android"],
                             "runtime":{"ui":[{"id":"main","type":"declarative","entry":"ui/main.json"}]},
                             "requires":{"capabilities":[{"id":"app","version":"^1.0.0","optional":false,"scopes":{}}]},
                             "contributes":{"tools":[{"id":"main","uiEntry":"main"}]}}
                            """);
                        PluginRuntime.get(activity).permissions().reconcile(manifest);
                        PluginPackageStore.InstalledPlugin installed = new PluginPackageStore.InstalledPlugin(manifest,
                            directory[0], new File(directory[0], "fixture.atsplugin"), true, "test", "", false);
                        tool[0] = new DeclarativeToolPlugin(installed, activity);
                        tools(activity).add(tool[0]);
                        activity.navigateForUi(1);
                        activity.openPluginForUi(tool[0]);
                    } catch (Exception error) { throw new AssertionError(error); }
                });
                awaitState(tool[0], "child");
                scenario.onActivity(activity -> {
                    String sessionId = (String) field(session(tool[0]), "sessionId");
                    assertTrue(tool[0].ownsSession(sessionId));
                    tool[0].setBackNavigation("different-session", false);
                    tool[0].onVisibilityChanged(false);
                    assertFalse("Hidden tools must not consume Back", tool[0].onBackRequested());
                    tool[0].onVisibilityChanged(true);
                    activity.handleBackForUi();
                    assertSame(tool[0], activity.selectedPluginForUi());
                });
                awaitState(tool[0], "root");
                scenario.onActivity(activity -> {
                    activity.handleBackForUi();
                    assertNull(activity.selectedPluginForUi());
                    assertEquals(1, activity.currentSectionForUi());
                    WebSession session = session(tool[0]);
                    String sessionId = (String) field(session, "sessionId");
                    tool[0].onDestroy();
                    assertFalse(tool[0].ownsSession(sessionId));
                    assertFalse(session.requestBack());
                });
            } finally {
                scenario.onActivity(activity -> {
                    activity.navigateForUi(0);
                    if (tool[0] != null) { tools(activity).remove(tool[0]); tool[0].onDestroy(); }
                    PluginRuntime.get(activity).permissions().removePlugin(ID);
                });
                if (directory[0] != null) removeFixture(directory[0]);
            }
        }
    }

    private static void awaitState(DeclarativeToolPlugin tool, String expected) throws Exception {
        String actual = null;
        for (int index = 0; index < 120; index++) {
            AtomicReference<String> value = new AtomicReference<>();
            CountDownLatch read = new CountDownLatch(1);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                WebSession session = session(tool);
                WebView view = session == null ? null : (WebView) field(session, "webView");
                if (view == null) { read.countDown(); return; }
                view.evaluateJavascript("document.getElementById('state')?.textContent", text -> { value.set(text); read.countDown(); });
            });
            assertTrue("WebView script read timed out", read.await(2, TimeUnit.SECONDS));
            actual = value.get();
            if (("\"" + expected + "\"").equals(actual)) return;
            SystemClock.sleep(50);
        }
        fail("Expected bridge state " + expected + ", got " + actual);
    }
    private static WebSession session(DeclarativeToolPlugin tool) { return (WebSession) field(tool, "activeWebSession"); }
    @SuppressWarnings("unchecked") private static List<HostTool> tools(MainActivity activity) { return (List<HostTool>) field(activity, "plugins"); }
    private static Object field(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void removeFixture(File file) throws Exception {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) removeFixture(child);
        Files.deleteIfExists(file.toPath());
    }
}
