package com.androidtoolsuite.app.plugin.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.androidtoolsuite.app.plugin.runtime.CapabilityCall;
import com.androidtoolsuite.app.plugin.runtime.CapabilityFailure;
import com.androidtoolsuite.app.plugin.runtime.CapabilityProvider;
import com.androidtoolsuite.runtime.contract.RuntimePluginManifest;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class PluginPermissionInstrumentedTest {
    private static final String PLUGIN_ID = "test.runtime_v2_permissions";
    private Context context;
    private PluginPermissionManager permissions;
    private PluginRuntime runtime;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        runtime = PluginRuntime.get(context);
        permissions = runtime.permissions();
        permissions.removePlugin(PLUGIN_ID);
    }

    @After
    public void tearDown() {
        permissions.removePlugin(PLUGIN_ID);
    }

    @Test
    public void persistsGrantAndInvalidatesItWhenScopeExpands() throws Exception {
        RuntimePluginManifest first = RuntimePluginManifest.parse(manifest("api.example.com"));
        permissions.reconcile(first);

        assertTrue(permissions.isGranted(first, "storage"));
        assertFalse(permissions.isGranted(first, "network.request"));
        assertEquals(1, permissions.permissions(first).size());
        assertEquals("network.request", permissions.permissions(first).get(0).capabilityId);

        permissions.setGranted(first, "network.request", true);
        PluginPermissionManager reloaded = new PluginPermissionManager(context);
        reloaded.reconcile(first);
        assertTrue(reloaded.isGranted(first, "network.request"));

        RuntimePluginManifest expanded = RuntimePluginManifest.parse(manifest("other.example.com"));
        reloaded.reconcile(expanded);
        assertFalse(reloaded.isGranted(expanded, "network.request"));
        assertTrue(reloaded.audit(PLUGIN_ID).length() >= 1);
    }

    @Test
    public void trustedProviderHasNoManageablePermissionsAndCannotBeRevoked() throws Exception {
        RuntimePluginManifest manifest = RuntimePluginManifest.parse(trustedManifest());
        permissions.reconcile(manifest);

        assertTrue(permissions.permissions(manifest).isEmpty());
        assertTrue(permissions.isGranted(manifest, "shizuku.control"));
        permissions.setGranted(manifest, "shizuku.control", false);
        assertTrue(permissions.isGranted(manifest, "shizuku.control"));
    }

    @Test
    public void routerRejectsPendingPermissionAndCancelsOnRevoke() throws Exception {
        RuntimePluginManifest manifest = RuntimePluginManifest.parse(manifest("api.example.com"));
        permissions.reconcile(manifest);
        CapabilityRouter router = new CapabilityRouter(new BackgroundTaskRegistry(), permissions);
        router.register(new CapabilityProvider() {
            @Override public String capabilityId() { return "network.request"; }
            @Override public String version() { return "1.0.0"; }
            @Override public Set<String> methods() { return Set.of("network.request"); }
            @Override public JSONObject call(CapabilityCall call) throws CapabilityFailure {
                try {
                    Thread.sleep(5_000L);
                    return new JSONObject().put("status", 200);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new CapabilityFailure("CANCELLED", "interrupted", true);
                } catch (Exception error) {
                    throw new CapabilityFailure("INTERNAL", error.getMessage(), false);
                }
            }
        }, "test", 1);

        ExecutionException denied = org.junit.Assert.assertThrows(ExecutionException.class, () -> router.invoke(
                manifest, PLUGIN_ID, "0123456789abcdef", "network.request", new JSONObject(), true, 10_000
        ).get(1, TimeUnit.SECONDS));
        assertEquals("PERMISSION_DENIED", ((CapabilityFailure) denied.getCause()).code);

        permissions.setGranted(manifest, "network.request", true);
        java.util.concurrent.CompletableFuture<JSONObject> active = router.invoke(
                manifest, PLUGIN_ID, "0123456789abcdef", "network.request", new JSONObject(), true, 10_000
        );
        permissions.setGranted(manifest, "network.request", false);
        ExecutionException revoked = org.junit.Assert.assertThrows(
                ExecutionException.class, () -> active.get(1, TimeUnit.SECONDS)
        );
        assertEquals("PERMISSION_DENIED", ((CapabilityFailure) revoked.getCause()).code);
        router.close();
    }

    @Test
    public void revocationClosesPluginOwnedBlobHandles() throws Exception {
        RuntimePluginManifest manifest = RuntimePluginManifest.parse(manifest("api.example.com"));
        permissions.reconcile(manifest);
        permissions.setGranted(manifest, "network.request", true);
        JSONObject opened = runtime.storage().blobOpenWrite(PLUGIN_ID, "permission-session", "temporary");

        permissions.setGranted(manifest, "network.request", false);

        org.junit.Assert.assertThrows(CapabilityFailure.class, () -> runtime.storage().blobWrite(
                PLUGIN_ID,
                "permission-session",
                opened.getString("handle"),
                android.util.Base64.encodeToString(new byte[]{1}, android.util.Base64.NO_WRAP)
        ));
    }

    private static String manifest(String host) {
        return "{"
                + "\"format\":\"ats-plugin\",\"formatVersion\":3,"
                + "\"plugin\":{\"id\":\"" + PLUGIN_ID + "\",\"title\":\"Permissions\","
                + "\"description\":\"Permission fixture\",\"version\":\"1.0.0\","
                + "\"versionCode\":1,\"minHostVersionCode\":1,\"publisher\":\"sample\","
                + "\"kind\":\"tool\"},"
                + "\"platforms\":[\"android\"],"
                + "\"runtime\":{\"ui\":[{\"id\":\"main\",\"type\":\"declarative\","
                + "\"entry\":\"ui/main.json\"}]},"
                + "\"requires\":{\"plugins\":[],\"capabilities\":["
                + "{\"id\":\"storage\",\"version\":\"1.0.0\",\"optional\":false,\"scopes\":{}},"
                + "{\"id\":\"network.request\",\"version\":\"1.0.0\",\"optional\":false,"
                + "\"scopes\":{\"hosts\":[\"" + host + "\"],\"methods\":[\"GET\"]}}]},"
                + "\"contributes\":{\"tools\":[{\"id\":\"main\",\"uiEntry\":\"main\"}]}"
                + "}";
    }

    private static String trustedManifest() {
        return "{"
                + "\"format\":\"ats-plugin\",\"formatVersion\":3,"
                + "\"plugin\":{\"id\":\"" + PLUGIN_ID + "\",\"title\":\"Trusted\","
                + "\"description\":\"Trusted fixture\",\"version\":\"1.0.0\","
                + "\"versionCode\":1,\"minHostVersionCode\":1,\"publisher\":\"sample\","
                + "\"kind\":\"trusted-provider\"},"
                + "\"platforms\":[\"android\"],"
                + "\"runtime\":{\"ui\":[{\"id\":\"main\",\"type\":\"declarative\","
                + "\"entry\":\"ui/main.json\"}],\"background\":[],\"providers\":[{"
                + "\"id\":\"main-provider\",\"type\":\"android-dex\","
                + "\"code\":\"android/provider.apk\",\"entryClass\":\"sample.Provider\","
                + "\"activation\":\"cold-start\"}]},"
                + "\"requires\":{\"plugins\":[],\"capabilities\":[{"
                + "\"id\":\"shizuku.control\",\"version\":\"^1.0.0\","
                + "\"optional\":false,\"scopes\":{}}]},"
                + "\"provides\":{\"capabilities\":[{\"id\":\"shizuku.control\","
                + "\"version\":\"1.0.0\",\"providerEntry\":\"main-provider\","
                + "\"methods\":[\"shizuku.getConnection\",\"shizuku.requestPermission\","
                + "\"shizuku.connect\"]}]},"
                + "\"contributes\":{\"tools\":[{\"id\":\"main\",\"uiEntry\":\"main\"}],"
                + "\"homeWidgets\":[]},\"datasets\":[],\"tasks\":[]}";
    }
}
