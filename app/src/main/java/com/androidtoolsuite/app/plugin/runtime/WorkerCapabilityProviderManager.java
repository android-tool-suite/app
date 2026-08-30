package com.androidtoolsuite.app.plugin.runtime;

import android.content.Context;

import com.androidtoolsuite.app.BuildConfig;
import com.androidtoolsuite.app.plugin.runtime.CapabilityCall;
import com.androidtoolsuite.app.plugin.runtime.CapabilityFailure;
import com.androidtoolsuite.app.plugin.runtime.CapabilityProvider;
import com.androidtoolsuite.runtime.contract.RuntimePluginManifest;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Registers capabilities implemented by ordinary plugins in an out-of-process JavaScript worker.
 * These providers run with the plugin identity at the Capability Router and never receive Context,
 * host classes, Binder, Shizuku, or filesystem paths.
 */
public final class WorkerCapabilityProviderManager implements AutoCloseable {
    private final Context context;
    private final CapabilityRouter router;
    private final Map<String, ActivePlugin> active = new LinkedHashMap<>();

    public WorkerCapabilityProviderManager(Context context, CapabilityRouter router) {
        this.context = context.getApplicationContext();
        this.router = router;
    }

    public synchronized void sync(List<PluginPackageStore.InstalledPlugin> installedPlugins) {
        close();
        List<PluginPackageStore.InstalledPlugin> pending = new ArrayList<>();
        Map<String, String> enabledVersions = new LinkedHashMap<>();
        for (PluginPackageStore.InstalledPlugin installed : installedPlugins) {
            if (installed.enabled) enabledVersions.put(
                    installed.manifest.plugin.id, installed.manifest.plugin.version
            );
            if (installed.enabled
                    && installed.manifest.capabilityContributions.stream()
                    .anyMatch(item -> !item.workerEntry.isEmpty())) {
                pending.add(installed);
            }
        }
        boolean progressed;
        do {
            progressed = false;
            for (int index = pending.size() - 1; index >= 0; index--) {
                PluginPackageStore.InstalledPlugin installed = pending.get(index);
                if (!prerequisitesAvailable(installed.manifest, enabledVersions)) continue;
                if (activate(installed)) progressed = true;
                pending.remove(index);
            }
        } while (progressed);
    }

    private boolean activate(PluginPackageStore.InstalledPlugin installed) {
        List<AutoCloseable> effects = new ArrayList<>();
        try {
            for (RuntimePluginManifest.CapabilityContribution contribution
                    : installed.manifest.capabilityContributions) {
                if (contribution.workerEntry.isEmpty()) continue;
                RuntimePluginManifest.BackgroundEntry entry = findBackground(
                        installed.manifest, contribution.workerEntry
                );
                if (entry == null || !"javascript-worker".equals(entry.type)
                        || !JavaScriptWorkerEngine.isSupported()) {
                    throw new CapabilityFailure(
                            "NOT_SUPPORTED",
                            "Worker Capability runtime is unavailable: " + contribution.workerEntry,
                            true
                    );
                }
                WorkerProvider provider = new WorkerProvider(
                        context, installed, entry, contribution, router
                );
                effects.add(router.register(
                        provider,
                        "plugin-worker:" + installed.manifest.plugin.id + "@"
                                + installed.generationDirectory.getName() + ":" + entry.id,
                        10
                ));
            }
            if (effects.isEmpty()) return false;
            active.put(installed.manifest.plugin.id, new ActivePlugin(
                    installed.generationDirectory.getName(), effects
            ));
            return true;
        } catch (Exception error) {
            closeReverse(effects);
            return false;
        }
    }

    private boolean prerequisitesAvailable(
            RuntimePluginManifest manifest,
            Map<String, String> enabledVersions
    ) {
        if (manifest.plugin.minHostVersionCode > BuildConfig.VERSION_CODE) return false;
        for (RuntimePluginManifest.Requirement requirement : manifest.pluginRequirements) {
            if (!requirement.optional && !CapabilityRouter.versionSatisfied(
                    enabledVersions.get(requirement.id), requirement.version)) return false;
        }
        for (RuntimePluginManifest.CapabilityRequirement requirement : manifest.capabilityRequirements) {
            if (requirement.optional || router.canResolve(requirement.id, requirement.version)) continue;
            boolean selfProvided = false;
            for (RuntimePluginManifest.CapabilityContribution contribution : manifest.capabilityContributions) {
                if (contribution.id.equals(requirement.id)
                        && CapabilityRouter.versionSatisfied(contribution.version, requirement.version)) {
                    selfProvided = true;
                    break;
                }
            }
            if (!selfProvided) return false;
        }
        return true;
    }

    public synchronized boolean isActive(String pluginId, String generationName) {
        ActivePlugin plugin = active.get(pluginId);
        return plugin != null && plugin.generationName.equals(generationName);
    }

    @Override
    public synchronized void close() {
        for (ActivePlugin plugin : new ArrayList<>(active.values())) closeReverse(plugin.effects);
        active.clear();
    }

    private static RuntimePluginManifest.BackgroundEntry findBackground(
            RuntimePluginManifest manifest,
            String id
    ) {
        for (RuntimePluginManifest.BackgroundEntry entry : manifest.backgroundEntries) {
            if (entry.id.equals(id)) return entry;
        }
        return null;
    }

    private static void closeReverse(List<AutoCloseable> effects) {
        for (int index = effects.size() - 1; index >= 0; index--) {
            try { effects.get(index).close(); } catch (Exception ignored) { }
        }
    }

    private static final class WorkerProvider implements CapabilityProvider {
        private final Context context;
        private final PluginPackageStore.InstalledPlugin installed;
        private final RuntimePluginManifest.BackgroundEntry entry;
        private final RuntimePluginManifest.CapabilityContribution contribution;
        private final CapabilityRouter router;

        WorkerProvider(
                Context context,
                PluginPackageStore.InstalledPlugin installed,
                RuntimePluginManifest.BackgroundEntry entry,
                RuntimePluginManifest.CapabilityContribution contribution,
                CapabilityRouter router
        ) {
            this.context = context;
            this.installed = installed;
            this.entry = entry;
            this.contribution = contribution;
            this.router = router;
        }

        @Override public String capabilityId() { return contribution.id; }
        @Override public String version() { return contribution.version; }
        @Override public Set<String> methods() { return new LinkedHashSet<>(contribution.methods); }

        @Override
        public JSONObject call(CapabilityCall call) throws CapabilityFailure {
            try {
                JSONObject input = new JSONObject()
                        .put("kind", "capability")
                        .put("capability", contribution.id)
                        .put("method", call.method)
                        .put("payload", call.payload)
                        .put("caller", new JSONObject()
                                .put("pluginId", call.pluginId)
                                .put("sessionId", call.sessionId)
                                .put("scopes", call.scopes)
                                .put("userGesture", call.userGesture));
                return JavaScriptWorkerEngine.run(
                        context,
                        installed,
                        entry,
                        input,
                        router,
                        "provider-" + UUID.randomUUID().toString().replace("-", "")
                );
            } catch (JavaScriptWorkerEngine.WorkerFailure failure) {
                throw new CapabilityFailure(failure.code, failure.getMessage(), failure.retryable);
            } catch (JSONException error) {
                throw new CapabilityFailure("INTERNAL", "Cannot encode Worker Capability call", false);
            }
        }
    }

    private static final class ActivePlugin {
        final String generationName;
        final List<AutoCloseable> effects;

        ActivePlugin(String generationName, List<AutoCloseable> effects) {
            this.generationName = generationName;
            this.effects = new ArrayList<>(effects);
        }
    }
}
