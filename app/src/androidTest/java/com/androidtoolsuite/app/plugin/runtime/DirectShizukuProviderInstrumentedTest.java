package com.androidtoolsuite.app.plugin.runtime;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Opt-in device check: install the signed Shizuku package and authorize Host first. */
@RunWith(AndroidJUnit4.class)
public final class DirectShizukuProviderInstrumentedTest {
    @Test public void signedProviderReadsSettingsAndLogsWithoutHostBridge() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        PluginPackageStore.InstalledPlugin installed = PluginRuntime.get(context).packages().find("shizuku_auth");
        assertNotNull("Install the signed Shizuku Provider before this device test", installed);
        File apk = new File(installed.generationDirectory, "android/provider.apk");
        ClassLoader loader = new DexClassLoader(apk.getAbsolutePath(), context.getCodeCacheDir().getAbsolutePath(), null, context.getClassLoader());
        NativeProviderEntry entry = (NativeProviderEntry) loader.loadClass("com.androidtoolsuite.provider.shizuku.ShizukuProviderEntry").getDeclaredConstructor().newInstance();
        Map<String, CapabilityProvider> providers = new HashMap<>();
        ProviderContext providerContext = new ProviderContext() {
            @Override public Context applicationContext() { return context; }
            @Override public void log(String level, String message) { }
            @Override public TrustedPlatformBridge trustedPlatform() { throw new AssertionError("Provider must not call the SDK platform bridge"); }
        };
        CapabilityRegistrar registrar = new CapabilityRegistrar() {
            @Override public AutoCloseable register(CapabilityProvider provider) { providers.put(provider.capabilityId(), provider); return () -> providers.remove(provider.capabilityId()); }
            @Override public AutoCloseable registerBackgroundTask(BackgroundTaskProvider provider) { throw new AssertionError("Unexpected task"); }
            @Override public void emitEvent(String id, String event, JSONObject payload) { }
        };
        try (AutoCloseable registration = entry.register(providerContext, registrar)) {
            JSONObject status = providers.get("shizuku.control").call(call("shizuku.getConnection", new JSONObject(), new JSONObject()));
            assertEquals("ready", status.getString("state"));
            JSONObject services = providers.get("accessibility.manage").call(call("accessibility.listServices", new JSONObject(), new JSONObject()));
            assertEquals("ready", services.getString("state"));
            String marker = "ATS_DIRECT_PROVIDER_PROBE_20260908";
            android.util.Log.i("AtsProviderProbe", marker);
            JSONObject scope = new JSONObject().put("terms", new JSONArray().put(marker)).put("maxLines", 1);
            JSONObject logs = providers.get("system.logs").call(call("system.logs.search", new JSONObject().put("terms", new JSONArray().put(marker)), scope));
            assertEquals(1, logs.getJSONArray("lines").length());
            assertTrue(logs.getJSONArray("lines").getString(0).contains(marker));
        }
    }
    private static CapabilityCall call(String method, JSONObject payload, JSONObject scopes) {
        return new CapabilityCall("test.direct", "device-test", method, payload, scopes, true, System.currentTimeMillis() + 15000);
    }
}
