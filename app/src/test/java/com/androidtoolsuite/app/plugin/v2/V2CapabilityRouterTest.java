package com.androidtoolsuite.app.plugin.v2;

import com.androidtoolsuite.app.plugin.v2.CapabilityCall;
import com.androidtoolsuite.app.plugin.v2.CapabilityProvider;
import com.androidtoolsuite.runtime.contract.RuntimePluginManifest;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class V2CapabilityRouterTest {
    @Test
    public void supportsExactComparatorCaretAndTildeRanges() {
        assertTrue(V2CapabilityRouter.versionSatisfied("1.4.2", "1.4.2"));
        assertTrue(V2CapabilityRouter.versionSatisfied("1.4.2", ">=1.0.0 <2.0.0"));
        assertTrue(V2CapabilityRouter.versionSatisfied("1.9.0", "^1.2.0"));
        assertFalse(V2CapabilityRouter.versionSatisfied("2.0.0", "^1.2.0"));
        assertTrue(V2CapabilityRouter.versionSatisfied("1.4.9", "~1.4.0"));
        assertFalse(V2CapabilityRouter.versionSatisfied("1.5.0", "~1.4.0"));
    }

    @Test
    public void routesOnlyDeclaredCapabilityAndDisposesRegistration() throws Exception {
        V2CapabilityRouter router = new V2CapabilityRouter();
        AutoCloseable registration = router.register(new CapabilityProvider() {
            @Override public String capabilityId() { return "app"; }
            @Override public String version() { return "1.0.0"; }
            @Override public Set<String> methods() { return Set.of("app.getSession"); }
            @Override public JSONObject call(CapabilityCall call) throws com.androidtoolsuite.app.plugin.v2.CapabilityFailure {
                try {
                    return new JSONObject().put("pluginId", call.pluginId);
                } catch (org.json.JSONException error) {
                    throw new com.androidtoolsuite.app.plugin.v2.CapabilityFailure("INTERNAL", error.getMessage(), false);
                }
            }
        }, "test", 1);
        RuntimePluginManifest manifest = RuntimePluginManifest.parse(manifest("app"));

        JSONObject response = router.invoke(
                manifest, "sample.router", "0123456789abcdef", "app.getSession", new JSONObject(), false, 1_000
        ).get(2, TimeUnit.SECONDS);

        assertEquals("sample.router", response.getString("pluginId"));
        registration.close();
        assertFalse(router.canResolve("app", "1.0.0"));
        router.close();
    }

    @Test
    public void routesCustomCapabilityProvidedByOrdinaryPlugin() throws Exception {
        V2CapabilityRouter router = new V2CapabilityRouter();
        router.register(new CapabilityProvider() {
            @Override public String capabilityId() { return "sample.echo"; }
            @Override public String version() { return "1.0.0"; }
            @Override public Set<String> methods() { return Set.of("sample.echo.call"); }
            @Override public JSONObject call(CapabilityCall call) throws com.androidtoolsuite.app.plugin.v2.CapabilityFailure {
                try {
                    return new JSONObject().put("value", call.payload.optString("value"));
                } catch (org.json.JSONException error) {
                    throw new com.androidtoolsuite.app.plugin.v2.CapabilityFailure("INTERNAL", error.getMessage(), false);
                }
            }
        }, "plugin-worker:sample.provider", 10);
        RuntimePluginManifest consumer = RuntimePluginManifest.parse(manifest("sample.echo"));

        JSONObject response = router.invoke(
                consumer,
                "sample.router",
                "0123456789abcdef",
                "sample.echo.call",
                new JSONObject().put("value", "ok"),
                false,
                1_000
        ).get(2, TimeUnit.SECONDS);

        assertEquals("ok", response.getString("value"));
        router.close();
    }

    private static String manifest(String capability) {
        return "{"
                + "\"format\":\"ats-plugin\",\"formatVersion\":3,"
                + "\"plugin\":{\"id\":\"sample.router\",\"title\":\"Router\","
                + "\"description\":\"Router fixture\",\"version\":\"1.0.0\","
                + "\"versionCode\":1,\"minHostVersionCode\":1,\"publisher\":\"sample\"},"
                + "\"platforms\":[\"android\"],"
                + "\"runtime\":{\"ui\":[{\"id\":\"main\",\"type\":\"web\",\"entry\":\"web/index.html\"}]},"
                + "\"requires\":{\"plugins\":[],\"capabilities\":[{\"id\":\"" + capability
                + "\",\"version\":\"1.0.0\",\"optional\":false,\"scopes\":{}}]},"
                + "\"contributes\":{\"tools\":[{\"id\":\"main\",\"uiEntry\":\"main\"}]}"
                + "}";
    }
}
