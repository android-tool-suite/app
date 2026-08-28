package com.androidtoolsuite.app.plugin.v2;

import android.content.Context;
import android.util.Log;

import com.androidtoolsuite.app.plugin.v2.CapabilityFailure;
import com.androidtoolsuite.app.plugin.v2.BackgroundTaskProvider;
import com.androidtoolsuite.app.plugin.v2.CapabilityProvider;
import com.androidtoolsuite.app.plugin.v2.CapabilityRegistrar;
import com.androidtoolsuite.app.plugin.v2.NativeProviderEntry;
import com.androidtoolsuite.app.plugin.v2.ProviderContext;
import com.androidtoolsuite.app.plugin.v2.TrustedPlatformBridge;
import com.androidtoolsuite.runtime.contract.ContractException;
import com.androidtoolsuite.runtime.contract.ContractLimits;
import com.androidtoolsuite.runtime.contract.RuntimePluginManifest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dalvik.system.DexClassLoader;

/** Cold-start loader for trusted android/provider.apk generations. */
public final class V2NativeProviderManager implements AutoCloseable {
    private static final String TAG = "AtsV2Provider";
    private final Context context;
    private final V2CapabilityRouter router;
    private final V2BackgroundTaskRegistry backgroundTasks;
    private final V2PublisherTrustStore trustStore;
    private final File runtimeRoot;
    private final TrustedPlatformBridge trustedPlatform;
    private final Map<String, ActiveProvider> active = new LinkedHashMap<>();
    private final Map<String, String> failures = new LinkedHashMap<>();
    private boolean coldStartLoaded;

    public V2NativeProviderManager(
            Context context,
            V2CapabilityRouter router,
            V2BackgroundTaskRegistry backgroundTasks,
            TrustedPlatformBridge trustedPlatform
    ) {
        this.context = context.getApplicationContext();
        this.router = router;
        this.backgroundTasks = backgroundTasks;
        this.trustedPlatform = trustedPlatform;
        this.trustStore = new V2PublisherTrustStore(this.context);
        this.runtimeRoot = new File(this.context.getCodeCacheDir(), "runtime-v2-providers");
    }

    public synchronized void loadEnabledAtColdStart(List<V2PackageStore.InstalledPlugin> installedPlugins) {
        if (coldStartLoaded) return;
        coldStartLoaded = true;
        deleteRecursively(runtimeRoot);
        runtimeRoot.mkdirs();
        for (V2PackageStore.InstalledPlugin installed : installedPlugins) {
            if (!installed.enabled || installed.manifest.providerEntries.isEmpty()) continue;
            try {
                activate(installed);
            } catch (Exception error) {
                failures.put(installed.manifest.plugin.id, safeMessage(error));
                Log.e(TAG, "Provider activation failed for " + installed.manifest.plugin.id, error);
            }
        }
    }

    public synchronized boolean isActive(String pluginId, String generationName) {
        ActiveProvider provider = active.get(pluginId);
        return provider != null && provider.generationName.equals(generationName);
    }

    public synchronized boolean isPendingRestart(V2PackageStore.InstalledPlugin installed) {
        return installed.enabled && !installed.manifest.providerEntries.isEmpty()
                && !isActive(installed.manifest.plugin.id, installed.generationDirectory.getName());
    }

    public synchronized void deactivate(String pluginId) {
        ActiveProvider provider = active.remove(pluginId);
        if (provider != null) provider.close();
    }

    public synchronized JSONObject debugGraph() {
        JSONArray values = new JSONArray();
        for (ActiveProvider provider : active.values()) {
            try {
                values.put(new JSONObject()
                        .put("pluginId", provider.pluginId)
                        .put("generation", provider.generationName)
                        .put("state", "ready"));
            } catch (JSONException ignored) {
            }
        }
        for (Map.Entry<String, String> failure : failures.entrySet()) {
            try {
                values.put(new JSONObject()
                        .put("pluginId", failure.getKey())
                        .put("state", "failed")
                        .put("reason", failure.getValue()));
            } catch (JSONException ignored) {
            }
        }
        try {
            return new JSONObject().put("nativeProviders", values);
        } catch (JSONException impossible) {
            return new JSONObject();
        }
    }

    private void activate(V2PackageStore.InstalledPlugin installed)
            throws IOException, ContractException, ReflectiveOperationException, CapabilityFailure {
        byte[] packageBytes = readFile(installed.packageFile, ContractLimits.MAX_PACKAGE_BYTES);
        File extraction = new File(runtimeRoot, UUID.randomUUID().toString());
        V2PluginPackageArchive.VerifiedPackage verified = V2PluginPackageArchive.extract(
                packageBytes, extraction, trustStore
        );
        if (!verified.manifest.plugin.id.equals(installed.manifest.plugin.id)
                || verified.manifest.plugin.versionCode != installed.manifest.plugin.versionCode) {
            deleteRecursively(extraction);
            throw new ContractException("Provider execution copy does not match active package generation");
        }
        if (!"trusted-provider".equals(verified.manifest.plugin.kind)) {
            throw new ContractException("Native Provider 必须使用 trusted-provider 包类型");
        }
        V2PluginPackageArchive.sealGeneration(extraction);
        File providerApk = new File(extraction, "android/provider.apk");
        if (!providerApk.isFile()) {
            deleteRecursively(extraction);
            throw new ContractException("Provider payload is missing after verification");
        }
        File optimized = new File(extraction, "optimized");
        // DexClassLoader needs a writable optimization directory. It is outside the sealed package tree.
        optimized = new File(runtimeRoot, extraction.getName() + "-optimized");
        if (!optimized.mkdirs() && !optimized.isDirectory()) throw new IOException("Cannot create Provider code cache");
        ClassLoader filteredParent = new ProviderParentClassLoader(context.getClassLoader());
        DexClassLoader loader = new DexClassLoader(
                providerApk.getAbsolutePath(),
                optimized.getAbsolutePath(),
                null,
                filteredParent
        );
        List<AutoCloseable> effects = new ArrayList<>();
        Set<String> allowedTaskEntries = new HashSet<>();
        for (RuntimePluginManifest.BackgroundEntry background : verified.manifest.backgroundEntries) {
            if ("provider-task".equals(background.type)) allowedTaskEntries.add(background.entry);
        }
        Set<String> registeredTaskEntries = new HashSet<>();
        try {
            for (RuntimePluginManifest.ProviderEntry entry : verified.manifest.providerEntries) {
                Set<String> allowed = new HashSet<>();
                Map<String, String> versions = new HashMap<>();
                Map<String, Set<String>> methods = new HashMap<>();
                for (RuntimePluginManifest.CapabilityContribution contribution
                        : verified.manifest.capabilityContributions) {
                    if (contribution.providerEntry.equals(entry.id)) {
                        allowed.add(contribution.id);
                        versions.put(contribution.id, contribution.version);
                        methods.put(contribution.id, new HashSet<>(contribution.methods));
                    }
                }
                if (allowed.isEmpty() && allowedTaskEntries.isEmpty()) {
                    throw new ContractException("Provider entry 未提供 Capability 或 provider-task：" + entry.id);
                }
                ScopedRegistrar registrar = new ScopedRegistrar(
                        router,
                        backgroundTasks,
                        installed.manifest.plugin.id,
                        installed.manifest.plugin.id + "@" + installed.generationDirectory.getName() + ":" + entry.id,
                        allowed,
                        versions,
                        methods,
                        allowedTaskEntries,
                        registeredTaskEntries,
                        effects
                );
                Class<?> type = Class.forName(entry.entryClass, true, loader);
                Object instance = type.getDeclaredConstructor().newInstance();
                if (!(instance instanceof NativeProviderEntry)) {
                    throw new ContractException("Provider entry 未实现 NativeProviderEntry：" + entry.entryClass);
                }
                AutoCloseable ownEffect = ((NativeProviderEntry) instance).register(
                        new HostProviderContext(context, trustedPlatform), registrar
                );
                if (ownEffect != null) effects.add(ownEffect);
                registrar.requireComplete();
            }
            if (!registeredTaskEntries.equals(allowedTaskEntries)) {
                throw new ContractException("Provider 未注册全部声明的 provider-task entry");
            }
            active.put(installed.manifest.plugin.id, new ActiveProvider(
                    installed.manifest.plugin.id,
                    installed.generationDirectory.getName(),
                    extraction,
                    optimized,
                    effects
            ));
            failures.remove(installed.manifest.plugin.id);
        } catch (Exception error) {
            closeEffects(effects);
            deleteRecursively(extraction);
            deleteRecursively(optimized);
            if (error instanceof IOException) throw (IOException) error;
            if (error instanceof ContractException) throw (ContractException) error;
            if (error instanceof ReflectiveOperationException) throw (ReflectiveOperationException) error;
            if (error instanceof CapabilityFailure) throw (CapabilityFailure) error;
            throw new ContractException("Provider register 失败：" + safeMessage(error), error);
        }
    }

    @Override
    public synchronized void close() {
        for (ActiveProvider provider : new ArrayList<>(active.values())) provider.close();
        active.clear();
        failures.clear();
    }

    private static byte[] readFile(File file, long limit) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if ((long) output.size() + read > limit) throw new IOException("Provider package exceeds size limit");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void closeEffects(List<AutoCloseable> effects) {
        for (int index = effects.size() - 1; index >= 0; index--) {
            try {
                effects.get(index).close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        file.setWritable(true);
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static final class ScopedRegistrar implements CapabilityRegistrar {
        private final V2CapabilityRouter router;
        private final V2BackgroundTaskRegistry backgroundTasks;
        private final String source;
        private final String pluginId;
        private final Set<String> allowed;
        private final Map<String, String> versions;
        private final Map<String, Set<String>> methods;
        private final List<AutoCloseable> effects;
        private final Set<String> registered = new HashSet<>();
        private final Set<String> allowedTaskEntries;
        private final Set<String> registeredTaskEntries;

        ScopedRegistrar(V2CapabilityRouter router, V2BackgroundTaskRegistry backgroundTasks,
                        String pluginId, String source, Set<String> allowed, Map<String, String> versions,
                        Map<String, Set<String>> methods,
                        Set<String> allowedTaskEntries, Set<String> registeredTaskEntries,
                        List<AutoCloseable> effects) {
            this.router = router;
            this.backgroundTasks = backgroundTasks;
            this.source = source;
            this.pluginId = pluginId;
            this.allowed = allowed;
            this.versions = versions;
            this.methods = methods;
            this.allowedTaskEntries = allowedTaskEntries;
            this.registeredTaskEntries = registeredTaskEntries;
            this.effects = effects;
        }

        @Override
        public AutoCloseable registerBackgroundTask(BackgroundTaskProvider provider) throws CapabilityFailure {
            if (provider == null || !allowedTaskEntries.contains(provider.entryId())) {
                throw CapabilityFailure.invalid("Background task registration does not match manifest");
            }
            if (!registeredTaskEntries.add(provider.entryId())) {
                throw CapabilityFailure.invalid("Background task entry was registered twice");
            }
            AutoCloseable registration = backgroundTasks.register(pluginId, provider, source);
            effects.add(registration);
            return registration;
        }

        @Override
        public void emitEvent(String capabilityId, String event, JSONObject payload) throws CapabilityFailure {
            if (!allowed.contains(capabilityId) || event == null || event.trim().isEmpty()) {
                throw CapabilityFailure.invalid("Provider event does not match manifest contribution");
            }
            router.emitCapabilityEvent(capabilityId, event.trim(), payload);
        }

        @Override
        public AutoCloseable register(CapabilityProvider provider) throws CapabilityFailure {
            if (!allowed.contains(provider.capabilityId())
                    || !versions.get(provider.capabilityId()).equals(provider.version())
                    || !methods.get(provider.capabilityId()).equals(provider.methods())) {
                throw CapabilityFailure.invalid("Provider registration does not match manifest contribution");
            }
            if (!registered.add(provider.capabilityId())) {
                throw CapabilityFailure.invalid("Provider registered a capability twice");
            }
            AutoCloseable registration = router.register(provider, source, 110);
            effects.add(registration);
            return registration;
        }

        void requireComplete() throws ContractException {
            if (!registered.equals(allowed)) {
                throw new ContractException("Provider entry 未注册全部声明的 Capability");
            }
        }
    }

    private static final class HostProviderContext implements ProviderContext {
        private final Context context;
        private final TrustedPlatformBridge trustedPlatform;

        HostProviderContext(Context context, TrustedPlatformBridge trustedPlatform) {
            this.context = context;
            this.trustedPlatform = trustedPlatform;
        }
        @Override public Context applicationContext() { return context; }
        @Override public TrustedPlatformBridge trustedPlatform() { return trustedPlatform; }
        @Override public void log(String level, String message) {
            String clean = message == null ? "" : message.replaceAll("[\\r\\n]", " ");
            if ("error".equalsIgnoreCase(level)) Log.e(TAG, clean);
            else if ("warn".equalsIgnoreCase(level)) Log.w(TAG, clean);
            else Log.i(TAG, clean);
        }
    }

    private static final class ProviderParentClassLoader extends ClassLoader {
        private static final Set<String> ALLOWED_SDK_TYPES = Set.of(
                CapabilityCall.class.getName(),
                CapabilityFailure.class.getName(),
                CapabilityProvider.class.getName(),
                CapabilityRegistrar.class.getName(),
                BackgroundTaskProvider.class.getName(),
                com.androidtoolsuite.app.plugin.v2.BackgroundTaskCall.class.getName(),
                NativeProviderEntry.class.getName(),
                ProviderContext.class.getName(),
                TrustedPlatformBridge.class.getName()
        );

        ProviderParentClassLoader(ClassLoader parent) { super(parent); }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("com.androidtoolsuite.app.") && !ALLOWED_SDK_TYPES.contains(name)) {
                throw new ClassNotFoundException("Host-internal class is outside Provider SDK: " + name);
            }
            return super.loadClass(name, resolve);
        }
    }

    private static final class ActiveProvider {
        final String pluginId;
        final String generationName;
        final File extraction;
        final File optimized;
        final List<AutoCloseable> effects;

        ActiveProvider(String pluginId, String generationName, File extraction, File optimized,
                       List<AutoCloseable> effects) {
            this.pluginId = pluginId;
            this.generationName = generationName;
            this.extraction = extraction;
            this.optimized = optimized;
            this.effects = new ArrayList<>(effects);
        }

        void close() {
            closeEffects(effects);
            deleteRecursively(extraction);
            deleteRecursively(optimized);
        }
    }
}
