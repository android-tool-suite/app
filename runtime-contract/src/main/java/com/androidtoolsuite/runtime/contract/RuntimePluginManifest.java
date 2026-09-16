package com.androidtoolsuite.runtime.contract;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class RuntimePluginManifest {
    public static final int FORMAT_VERSION = 3;

    public final Plugin plugin;
    public final List<String> platforms;
    public final List<UiEntry> uiEntries;
    public final List<BackgroundEntry> backgroundEntries;
    public final List<ProviderEntry> providerEntries;
    public final List<Requirement> pluginRequirements;
    public final List<CapabilityRequirement> capabilityRequirements;
    public final List<CapabilityContribution> capabilityContributions;
    public final List<ToolContribution> toolContributions;
    public final List<HomeWidgetContribution> homeWidgetContributions;
    public final List<Dataset> datasets;
    public final List<Task> tasks;
    public final String sourceJson;

    private RuntimePluginManifest(
            Plugin plugin,
            List<String> platforms,
            List<UiEntry> uiEntries,
            List<BackgroundEntry> backgroundEntries,
            List<ProviderEntry> providerEntries,
            List<Requirement> pluginRequirements,
            List<CapabilityRequirement> capabilityRequirements,
            List<CapabilityContribution> capabilityContributions,
            List<ToolContribution> toolContributions,
            List<HomeWidgetContribution> homeWidgetContributions,
            List<Dataset> datasets,
            List<Task> tasks,
            String sourceJson
    ) {
        this.plugin = plugin;
        this.platforms = immutable(platforms);
        this.uiEntries = immutable(uiEntries);
        this.backgroundEntries = immutable(backgroundEntries);
        this.providerEntries = immutable(providerEntries);
        this.pluginRequirements = immutable(pluginRequirements);
        this.capabilityRequirements = immutable(capabilityRequirements);
        this.capabilityContributions = immutable(capabilityContributions);
        this.toolContributions = immutable(toolContributions);
        this.homeWidgetContributions = immutable(homeWidgetContributions);
        this.datasets = immutable(datasets);
        this.tasks = immutable(tasks);
        this.sourceJson = sourceJson;
    }

    public static RuntimePluginManifest parse(String json) throws ContractException {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > ContractLimits.MAX_MANIFEST_BYTES) {
            throw new ContractException("manifest.json 超出大小限制");
        }
        try {
            JSONObject root = new JSONObject(json);
            JsonContract.requireDepth(root, ContractLimits.MAX_JSON_DEPTH);
            JsonContract.requireOnlyKeys(root, "$schema", "format", "formatVersion", "plugin", "platforms",
                    "runtime", "requires", "provides", "contributes", "datasets", "tasks");
            if (!"ats-plugin".equals(root.optString("format", ""))) {
                throw new ContractException("manifest.format 必须是 ats-plugin");
            }
            if (JsonContract.requirePositiveInt(root, "formatVersion") != FORMAT_VERSION) {
                throw new ContractException("manifest.formatVersion 必须是 3");
            }

            Plugin plugin = parsePlugin(JsonContract.requireObject(root, "plugin"));
            List<String> platforms = JsonContract.stringList(
                    JsonContract.requireArray(root, "platforms"), "platforms", 1, 8, 32
            );
            Set<String> platformSet = new HashSet<>();
            for (String platform : platforms) {
                if (!platform.matches("[a-z][a-z0-9-]{1,31}") || !platformSet.add(platform)) {
                    throw new ContractException("platforms 包含无效或重复平台：" + platform);
                }
            }
            JSONObject runtime = JsonContract.requireObject(root, "runtime");
            JsonContract.requireOnlyKeys(runtime, "ui", "background", "providers");
            List<UiEntry> uiEntries = parseUiEntries(JsonContract.requireArray(runtime, "ui"));
            List<BackgroundEntry> backgroundEntries = parseBackgroundEntries(runtime.optJSONArray("background"));
            List<ProviderEntry> providerEntries = parseProviderEntries(runtime.optJSONArray("providers"));
            if (uiEntries.isEmpty() && backgroundEntries.isEmpty() && providerEntries.isEmpty()) {
                throw new ContractException("runtime 至少需要一个 UI、后台或 Provider 入口");
            }

            JSONObject requires = root.optJSONObject("requires");
            List<Requirement> pluginRequirements = new ArrayList<>();
            List<CapabilityRequirement> capabilityRequirements = new ArrayList<>();
            if (requires != null) {
                JsonContract.requireOnlyKeys(requires, "plugins", "capabilities");
                pluginRequirements = parsePluginRequirements(requires.optJSONArray("plugins"));
                capabilityRequirements = parseCapabilityRequirements(requires.optJSONArray("capabilities"));
            }

            JSONObject provides = root.optJSONObject("provides");
            List<CapabilityContribution> capabilityContributions = new ArrayList<>();
            if (provides != null) {
                JsonContract.requireOnlyKeys(provides, "capabilities");
                capabilityContributions = parseCapabilityContributions(provides.optJSONArray("capabilities"));
            }

            JSONObject contributes = root.optJSONObject("contributes");
            List<ToolContribution> toolContributions = new ArrayList<>();
            List<HomeWidgetContribution> homeWidgetContributions = new ArrayList<>();
            if (contributes != null) {
                JsonContract.requireOnlyKeys(contributes, "tools", "homeWidgets");
                toolContributions = parseToolContributions(contributes.optJSONArray("tools"));
                homeWidgetContributions = parseHomeWidgetContributions(contributes.optJSONArray("homeWidgets"));
            }

            List<Dataset> datasets = parseDatasets(root.optJSONArray("datasets"));
            List<Task> tasks = parseTasks(root.optJSONArray("tasks"));
            validateReferences(uiEntries, backgroundEntries, providerEntries, capabilityContributions,
                    toolContributions, datasets, tasks);
            validateHomeWidgetDataSources(
                    homeWidgetContributions,
                    capabilityRequirements,
                    capabilityContributions
            );
            validateTaskCapability(tasks, capabilityRequirements);
            validatePackageKind(plugin, uiEntries, providerEntries, capabilityContributions,
                    toolContributions, homeWidgetContributions);
            return new RuntimePluginManifest(
                    plugin,
                    platforms,
                    uiEntries,
                    backgroundEntries,
                    providerEntries,
                    pluginRequirements,
                    capabilityRequirements,
                    capabilityContributions,
                    toolContributions,
                    homeWidgetContributions,
                    datasets,
                    tasks,
                    root.toString()
            );
        } catch (JSONException error) {
            throw new ContractException("manifest.json 不是有效 JSON：" + error.getMessage(), error);
        }
    }

    public UiEntry defaultUiEntry() {
        if (!toolContributions.isEmpty()) {
            String selected = toolContributions.get(0).uiEntry;
            for (UiEntry entry : uiEntries) {
                if (entry.id.equals(selected)) {
                    return entry;
                }
            }
        }
        return uiEntries.isEmpty() ? null : uiEntries.get(0);
    }

    public Set<String> legacyPluginDependencyLabels() {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        for (Requirement requirement : pluginRequirements) {
            if (!requirement.optional) {
                labels.add(requirement.id + "@" + requirement.version);
            }
        }
        return Collections.unmodifiableSet(labels);
    }

    private static Plugin parsePlugin(JSONObject json) throws ContractException, JSONException {
        JsonContract.requireOnlyKeys(json, "id", "title", "description", "version", "versionCode",
                "minHostVersionCode", "minAndroidApi", "publisher", "homepage", "kind");
        String id = ContractPatterns.requireId("plugin.id", json.optString("id", ""), 128);
        String title = ContractPatterns.requireText("plugin.title", json.optString("title", ""), 80);
        String description = ContractPatterns.requireText(
                "plugin.description", json.optString("description", ""), 320
        );
        String version = ContractPatterns.requireText("plugin.version", json.optString("version", ""), 64);
        if (!ContractPatterns.VERSION.matcher(version).matches()) {
            throw new ContractException("plugin.version 必须是 SemVer");
        }
        int versionCode = JsonContract.requirePositiveInt(json, "versionCode");
        int minHostVersionCode = JsonContract.requirePositiveInt(json, "minHostVersionCode");
        int minAndroidApi = json.has("minAndroidApi")
                ? JsonContract.requirePositiveInt(json, "minAndroidApi") : 24;
        if (minAndroidApi < 24 || minAndroidApi > 1000) {
            throw new ContractException("plugin.minAndroidApi 超出范围");
        }
        String publisher = ContractPatterns.requireId("plugin.publisher", json.optString("publisher", ""), 128);
        String kind = json.optString("kind", "tool").trim();
        if (!Set.of("tool", "trusted-provider").contains(kind)) {
            throw new ContractException("plugin.kind 只支持 tool 或 trusted-provider");
        }
        String homepage = json.optString("homepage", "").trim();
        if (homepage.length() > 512 || (!homepage.isEmpty() && !homepage.matches("https?://[^\\s]+"))) {
            throw new ContractException("plugin.homepage 超出长度限制");
        }
        return new Plugin(id, title, description, version, versionCode, minHostVersionCode, minAndroidApi,
                publisher, homepage, kind);
    }

    private static List<UiEntry> parseUiEntries(JSONArray array) throws ContractException, JSONException {
        if (array.length() > 8) {
            throw new ContractException("runtime.ui 项目过多");
        }
        List<UiEntry> values = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = JsonContract.requireObject(array, index, "runtime.ui");
            JsonContract.requireOnlyKeys(item, "id", "type", "entry");
            String id = ContractPatterns.requireId("runtime.ui.id", item.optString("id", ""), 64);
            if (!ids.add(id)) {
                throw new ContractException("runtime.ui 包含重复 id：" + id);
            }
            String type = item.optString("type", "");
            if (!Set.of("web", "declarative").contains(type)) {
                throw new ContractException("runtime.ui.type 只支持 web 或 declarative");
            }
            String entry = PackagePathPolicy.validateFilePath(item.optString("entry", ""));
            if ("web".equals(type) && (!entry.startsWith("web/") || !entry.endsWith(".html"))) {
                throw new ContractException("Web UI 入口必须位于 web/ 且以 .html 结尾");
            }
            if ("declarative".equals(type) && (!entry.startsWith("ui/") || !entry.endsWith(".json"))) {
                throw new ContractException("声明式 UI 入口必须位于 ui/ 且以 .json 结尾");
            }
            values.add(new UiEntry(id, type, entry));
        }
        return values;
    }

    private static List<BackgroundEntry> parseBackgroundEntries(JSONArray array)
            throws ContractException, JSONException {
        List<BackgroundEntry> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        if (array.length() > 32) {
            throw new ContractException("runtime.background 项目过多");
        }
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = JsonContract.requireObject(array, index, "runtime.background");
            JsonContract.requireOnlyKeys(item, "id", "type", "entry", "required", "timeoutMs", "maxHeapBytes");
            String id = ContractPatterns.requireId("runtime.background.id", item.optString("id", ""), 64);
            if (!ids.add(id)) {
                throw new ContractException("runtime.background 包含重复 id：" + id);
            }
            String type = item.optString("type", "");
            if (!Set.of("provider-task", "javascript-worker", "wasm-worker").contains(type)) {
                throw new ContractException("未知后台入口类型：" + type);
            }
            String entry = PackagePathPolicy.validateFilePath(item.optString("entry", ""));
            if ("javascript-worker".equals(type) && (!entry.startsWith("workers/") || !entry.endsWith(".js"))) {
                throw new ContractException("JavaScript worker 必须位于 workers/ 且以 .js 结尾");
            }
            if ("wasm-worker".equals(type) && (!entry.startsWith("workers/") || !entry.endsWith(".wasm"))) {
                throw new ContractException("WASM worker 必须位于 workers/ 且以 .wasm 结尾");
            }
            int timeout = item.has("timeoutMs")
                    ? JsonContract.requirePositiveInt(item, "timeoutMs")
                    : ContractLimits.DEFAULT_DEADLINE_MS;
            if (timeout < 1000 || timeout > ContractLimits.MAX_DEADLINE_MS) {
                throw new ContractException("后台入口 timeoutMs 超出范围");
            }
            long maxHeapBytes = item.has("maxHeapBytes")
                    ? JsonContract.requirePositiveInt(item, "maxHeapBytes")
                    : 32L * 1024L * 1024L;
            if (maxHeapBytes < 1024L * 1024L || maxHeapBytes > 256L * 1024L * 1024L) {
                throw new ContractException("后台入口 maxHeapBytes 超出范围");
            }
            values.add(new BackgroundEntry(
                    id, type, entry, JsonContract.requireBoolean(item, "required"), timeout, maxHeapBytes
            ));
        }
        return values;
    }

    private static List<ProviderEntry> parseProviderEntries(JSONArray array)
            throws ContractException, JSONException {
        List<ProviderEntry> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        if (array.length() > 16) {
            throw new ContractException("runtime.providers 项目过多");
        }
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = JsonContract.requireObject(array, index, "runtime.providers");
            JsonContract.requireOnlyKeys(item, "id", "type", "code", "entryClass", "activation");
            String id = ContractPatterns.requireId("runtime.providers.id", item.optString("id", ""), 64);
            if (!ids.add(id)) {
                throw new ContractException("runtime.providers 包含重复 id：" + id);
            }
            if (!"android-dex".equals(item.optString("type", ""))
                    || !"android/provider.apk".equals(item.optString("code", ""))
                    || !"cold-start".equals(item.optString("activation", ""))) {
                throw new ContractException("Native Provider 首版必须使用 android-dex/android/provider.apk/cold-start");
            }
            String entryClass = ContractPatterns.requireText(
                    "runtime.providers.entryClass", item.optString("entryClass", ""), 240
            );
            if (!ContractPatterns.ENTRY_CLASS.matcher(entryClass).matches()) {
                throw new ContractException("Provider entryClass 格式无效");
            }
            values.add(new ProviderEntry(id, entryClass));
        }
        return values;
    }

    private static List<Requirement> parsePluginRequirements(JSONArray array)
            throws ContractException, JSONException {
        List<Requirement> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        if (array.length() > 64) {
            throw new ContractException("requires.plugins 项目过多");
        }
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = JsonContract.requireObject(array, index, "requires.plugins");
            JsonContract.requireOnlyKeys(item, "id", "version", "optional");
            String id = ContractPatterns.requireId("requires.plugins.id", item.optString("id", ""), 128);
            if (!ids.add(id)) {
                throw new ContractException("requires.plugins 包含重复 id：" + id);
            }
            String version = ContractPatterns.requireText(
                    "requires.plugins.version", item.optString("version", ""), 64
            );
            values.add(new Requirement(id, version, JsonContract.requireBoolean(item, "optional")));
        }
        return values;
    }

    private static List<CapabilityRequirement> parseCapabilityRequirements(JSONArray array)
            throws ContractException, JSONException {
        List<CapabilityRequirement> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        if (array.length() > 128) {
            throw new ContractException("requires.capabilities 项目过多");
        }
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = JsonContract.requireObject(array, index, "requires.capabilities");
            JsonContract.requireOnlyKeys(item, "id", "version", "optional", "scopes");
            String id = ContractPatterns.requireId("requires.capabilities.id", item.optString("id", ""), 128);
            if (!ids.add(id)) {
                throw new ContractException("requires.capabilities 包含重复 id：" + id);
            }
            values.add(new CapabilityRequirement(
                    id,
                    ContractPatterns.requireText(
                            "requires.capabilities.version", item.optString("version", ""), 64
                    ),
                    JsonContract.requireBoolean(item, "optional"),
                    item.optJSONObject("scopes") == null ? "{}" : item.optJSONObject("scopes").toString()
            ));
        }
        return values;
    }

    private static List<CapabilityContribution> parseCapabilityContributions(JSONArray array)
            throws ContractException, JSONException {
        List<CapabilityContribution> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        if (array.length() > 64) {
            throw new ContractException("provides.capabilities 项目过多");
        }
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = JsonContract.requireObject(array, index, "provides.capabilities");
            JsonContract.requireOnlyKeys(item, "id", "version", "providerEntry", "workerEntry", "methods");
            String id = ContractPatterns.requireId("provides.capabilities.id", item.optString("id", ""), 128);
            if (!ids.add(id)) {
                throw new ContractException("provides.capabilities 包含重复 id：" + id);
            }
            String version = ContractPatterns.requireText(
                    "provides.capabilities.version", item.optString("version", ""), 64
            );
            if (!ContractPatterns.VERSION.matcher(version).matches()) {
                throw new ContractException("Provider capability version 必须是 SemVer");
            }
            String providerEntry = item.has("providerEntry")
                    ? ContractPatterns.requireId(
                            "provides.capabilities.providerEntry", item.optString("providerEntry", ""), 64
                    )
                    : "";
            String workerEntry = item.has("workerEntry")
                    ? ContractPatterns.requireId(
                            "provides.capabilities.workerEntry", item.optString("workerEntry", ""), 64
                    )
                    : "";
            if (providerEntry.isEmpty() == workerEntry.isEmpty()) {
                throw new ContractException("Capability 必须且只能引用一个 Provider 或 Worker entry");
            }
            List<String> methods = JsonContract.stringList(
                    JsonContract.requireArray(item, "methods"),
                    "provides.capabilities.methods", 1, 64, 160
            );
            Set<String> uniqueMethods = new HashSet<>();
            for (String method : methods) {
                if (!method.matches("[a-z][A-Za-z0-9]*(?:[._-][A-Za-z0-9]+)+")
                        || !uniqueMethods.add(method)) {
                    throw new ContractException("Capability methods 包含无效或重复方法：" + method);
                }
                String knownCapability = GeneratedContract.capabilityForMethod(method);
                if (knownCapability != null && !knownCapability.equals(id)) {
                    throw new ContractException("Capability method 与 id 不匹配：" + method);
                }
                if (knownCapability == null && !(method.equals(id) || method.startsWith(id + "."))) {
                    throw new ContractException("自定义 Capability method 必须以 capability id 为前缀：" + method);
                }
            }
            values.add(new CapabilityContribution(id, version, providerEntry, workerEntry, methods));
        }
        return values;
    }

    private static List<ToolContribution> parseToolContributions(JSONArray array)
            throws ContractException, JSONException {
        List<ToolContribution> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        if (array.length() > 16) {
            throw new ContractException("contributes.tools 项目过多");
        }
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = JsonContract.requireObject(array, index, "contributes.tools");
            JsonContract.requireOnlyKeys(item, "id", "uiEntry", "title", "description");
            String id = ContractPatterns.requireId("contributes.tools.id", item.optString("id", ""), 64);
            if (!ids.add(id)) {
                throw new ContractException("contributes.tools 包含重复 id：" + id);
            }
            String uiEntry = ContractPatterns.requireId(
                    "contributes.tools.uiEntry", item.optString("uiEntry", ""), 64
            );
            String title = item.optString("title", "").trim();
            String description = item.optString("description", "").trim();
            if (title.length() > 80 || description.length() > 320) {
                throw new ContractException("Tool contribution 文本超出长度限制");
            }
            values.add(new ToolContribution(id, uiEntry, title, description));
        }
        return values;
    }

    private static List<HomeWidgetContribution> parseHomeWidgetContributions(JSONArray array)
            throws ContractException, JSONException {
        List<HomeWidgetContribution> values = new ArrayList<>();
        if (array == null) return values;
        if (array.length() > 16) throw new ContractException("contributes.homeWidgets 项目过多");
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = JsonContract.requireObject(array, index, "contributes.homeWidgets");
            JsonContract.requireOnlyKeys(item, "id", "title", "template", "dataSource", "sizes");
            String id = ContractPatterns.requireId(
                    "contributes.homeWidgets.id", item.optString("id", ""), 64
            );
            if (!ids.add(id)) throw new ContractException("contributes.homeWidgets 包含重复 id：" + id);
            String template = item.optString("template", "");
            if (!Set.of("status", "metric", "action").contains(template)) {
                throw new ContractException("未知 HomeWidget template：" + template);
            }
            List<String> sizes = JsonContract.stringList(
                    JsonContract.requireArray(item, "sizes"), "contributes.homeWidgets.sizes", 1, 4, 8
            );
            Set<String> uniqueSizes = new HashSet<>();
            for (String size : sizes) {
                if (!Set.of("1x1", "2x1", "2x2", "4x2").contains(size) || !uniqueSizes.add(size)) {
                    throw new ContractException("HomeWidget size 无效或重复：" + size);
                }
            }
            values.add(new HomeWidgetContribution(
                    id,
                    ContractPatterns.requireText(
                            "contributes.homeWidgets.title", item.optString("title", ""), 80
                    ),
                    template,
                    ContractPatterns.requireText(
                            "contributes.homeWidgets.dataSource", item.optString("dataSource", ""), 160
                    ),
                    sizes
            ));
        }
        return values;
    }

    private static List<Dataset> parseDatasets(JSONArray array) throws ContractException, JSONException {
        List<Dataset> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        if (array.length() > 64) {
            throw new ContractException("datasets 项目过多");
        }
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = JsonContract.requireObject(array, index, "datasets");
            JsonContract.requireOnlyKeys(item, "id", "title", "category", "formatVersion", "sensitive",
                    "restoreModes", "dependsOn", "mediaType", "validator", "maxBytes");
            String id = ContractPatterns.requireId("datasets.id", item.optString("id", ""), 64);
            if (!ids.add(id)) {
                throw new ContractException("datasets 包含重复 id：" + id);
            }
            String title = ContractPatterns.requireText("datasets.title", item.optString("title", ""), 80);
            String category = item.optString("category", "");
            if (!Set.of("settings", "data", "cache", "secret").contains(category)) {
                throw new ContractException("未知 Dataset category：" + category);
            }
            int formatVersion = JsonContract.requirePositiveInt(item, "formatVersion");
            boolean sensitive = JsonContract.requireBoolean(item, "sensitive");
            List<String> restoreModes = JsonContract.stringList(
                    JsonContract.requireArray(item, "restoreModes"), "datasets.restoreModes", 1, 3, 16
            );
            for (String mode : restoreModes) {
                if (!Set.of("replace", "merge", "skip").contains(mode)) {
                    throw new ContractException("未知 Dataset restore mode：" + mode);
                }
            }
            List<String> dependsOn = item.optJSONArray("dependsOn") == null
                    ? new ArrayList<>()
                    : JsonContract.stringList(item.optJSONArray("dependsOn"), "datasets.dependsOn", 0, 16, 64);
            String mediaType = item.optString("mediaType", "application/json").trim();
            if (!mediaType.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+\\-]+") || mediaType.length() > 128) {
                throw new ContractException("datasets.mediaType 格式无效");
            }
            String validator = item.optString("validator", "application/json".equals(mediaType) ? "json" : "opaque");
            if (!Set.of("json", "zip", "opaque").contains(validator)) {
                throw new ContractException("未知 Dataset validator：" + validator);
            }
            int maxBytes = item.has("maxBytes")
                    ? JsonContract.requirePositiveInt(item, "maxBytes") : 32 * 1024 * 1024;
            if (maxBytes > 512 * 1024 * 1024) throw new ContractException("Dataset maxBytes 超出范围");
            values.add(new Dataset(
                    id, title, category, formatVersion, sensitive, restoreModes, dependsOn,
                    mediaType, validator, maxBytes
            ));
        }
        return values;
    }

    private static List<Task> parseTasks(JSONArray array) throws ContractException, JSONException {
        List<Task> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        if (array.length() > 64) {
            throw new ContractException("tasks 项目过多");
        }
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = JsonContract.requireObject(array, index, "tasks");
            JsonContract.requireOnlyKeys(item, "id", "backgroundEntry", "triggers", "constraints",
                    "concurrency", "retry");
            String id = ContractPatterns.requireId("tasks.id", item.optString("id", ""), 64);
            if (!ids.add(id)) {
                throw new ContractException("tasks 包含重复 id：" + id);
            }
            String backgroundEntry = ContractPatterns.requireId(
                    "tasks.backgroundEntry", item.optString("backgroundEntry", ""), 64
            );
            JSONArray triggers = JsonContract.requireArray(item, "triggers");
            if (triggers.length() < 1 || triggers.length() > 16) {
                throw new ContractException("tasks.triggers 数量超出范围");
            }
            List<Trigger> parsedTriggers = new ArrayList<>();
            for (int triggerIndex = 0; triggerIndex < triggers.length(); triggerIndex++) {
                JSONObject trigger = JsonContract.requireObject(triggers, triggerIndex, "tasks.triggers");
                JsonContract.requireOnlyKeys(trigger, "type", "intervalMinutes", "capability", "event");
                String type = trigger.optString("type", "");
                if (!Set.of("manual", "periodic", "network-available", "charging", "app-foreground",
                        "provider-event").contains(type)) {
                    throw new ContractException("未知 task trigger type：" + type);
                }
                int intervalMinutes = 0;
                String capability = "";
                String event = "";
                if ("periodic".equals(type)) {
                    intervalMinutes = JsonContract.requirePositiveInt(trigger, "intervalMinutes");
                    if (intervalMinutes < 15 || intervalMinutes > 525_600) {
                        throw new ContractException("periodic intervalMinutes 超出范围");
                    }
                } else if (trigger.has("intervalMinutes")) {
                    throw new ContractException("只有 periodic trigger 可以声明 intervalMinutes");
                }
                if ("provider-event".equals(type)) {
                    capability = ContractPatterns.requireId(
                            "tasks.triggers.capability", trigger.optString("capability", ""), 128
                    );
                    event = ContractPatterns.requireText(
                            "tasks.triggers.event", trigger.optString("event", ""), 128
                    );
                } else if (trigger.has("capability") || trigger.has("event")) {
                    throw new ContractException("只有 provider-event trigger 可以声明 capability/event");
                }
                parsedTriggers.add(new Trigger(type, intervalMinutes, capability, event));
            }
            JSONObject constraints = item.optJSONObject("constraints");
            Constraints parsedConstraints = new Constraints("none", false, false, false);
            if (constraints != null) {
                JsonContract.requireOnlyKeys(constraints, "network", "charging", "batteryNotLow", "storageNotLow");
                String network = constraints.optString("network", "none");
                if (!Set.of("none", "connected", "unmetered").contains(network)) {
                    throw new ContractException("未知 task network constraint：" + network);
                }
                parsedConstraints = new Constraints(
                        network,
                        optionalBoolean(constraints, "charging", false),
                        optionalBoolean(constraints, "batteryNotLow", false),
                        optionalBoolean(constraints, "storageNotLow", false)
                );
            } else if (item.has("constraints")) {
                throw new ContractException("tasks.constraints 必须是对象");
            }
            JSONObject concurrency = JsonContract.requireObject(item, "concurrency");
            JsonContract.requireOnlyKeys(concurrency, "policy", "max");
            String policy = concurrency.optString("policy", "");
            if (!Set.of("forbid", "replace", "parallel").contains(policy)) {
                throw new ContractException("未知 task concurrency policy：" + policy);
            }
            int maxConcurrency = concurrency.has("max")
                    ? JsonContract.requirePositiveInt(concurrency, "max") : 1;
            if (maxConcurrency > 8 || (!"parallel".equals(policy) && concurrency.has("max"))) {
                throw new ContractException("task concurrency.max 仅适用于 parallel 且不能超过 8");
            }
            JSONObject retry = JsonContract.requireObject(item, "retry");
            JsonContract.requireOnlyKeys(retry, "maxAttempts", "initialBackoffMs");
            int maxAttempts = JsonContract.requirePositiveInt(retry, "maxAttempts");
            int initialBackoffMs = JsonContract.requirePositiveInt(retry, "initialBackoffMs");
            if (maxAttempts > 10 || initialBackoffMs < 10_000L || initialBackoffMs > 86_400_000L) {
                throw new ContractException("task retry 参数超出范围");
            }
            values.add(new Task(
                    id,
                    backgroundEntry,
                    parsedTriggers,
                    parsedConstraints,
                    policy,
                    maxConcurrency,
                    maxAttempts,
                    initialBackoffMs
            ));
        }
        return values;
    }

    private static void validateReferences(
            List<UiEntry> uiEntries,
            List<BackgroundEntry> backgroundEntries,
            List<ProviderEntry> providerEntries,
            List<CapabilityContribution> capabilityContributions,
            List<ToolContribution> toolContributions,
            List<Dataset> datasets,
            List<Task> tasks
    ) throws ContractException {
        Set<String> uiIds = idsOfUi(uiEntries);
        for (ToolContribution contribution : toolContributions) {
            if (!uiIds.contains(contribution.uiEntry)) {
                throw new ContractException("Tool 引用了不存在的 UI entry：" + contribution.uiEntry);
            }
        }
        Set<String> providerIds = new HashSet<>();
        for (ProviderEntry entry : providerEntries) {
            providerIds.add(entry.id);
        }
        java.util.Map<String, BackgroundEntry> backgroundsById = new java.util.HashMap<>();
        for (BackgroundEntry entry : backgroundEntries) {
            backgroundsById.put(entry.id, entry);
        }
        for (CapabilityContribution contribution : capabilityContributions) {
            if (!contribution.providerEntry.isEmpty()
                    && !providerIds.contains(contribution.providerEntry)) {
                throw new ContractException("Capability 引用了不存在的 Provider entry：" + contribution.providerEntry);
            }
            if (!contribution.workerEntry.isEmpty()) {
                BackgroundEntry worker = backgroundsById.get(contribution.workerEntry);
                if (worker == null || !Set.of("javascript-worker", "wasm-worker").contains(worker.type)) {
                    throw new ContractException("Capability 必须引用 JavaScript 或 WASM Worker entry："
                            + contribution.workerEntry);
                }
                if (!worker.required) {
                    throw new ContractException("提供 Capability 的 Worker entry 必须标记 required");
                }
            }
        }
        for (Task task : tasks) {
            if (!backgroundsById.containsKey(task.backgroundEntry)) {
                throw new ContractException("Task 引用了不存在的 background entry：" + task.backgroundEntry);
            }
        }
        Set<String> datasetIds = new HashSet<>();
        for (Dataset dataset : datasets) {
            datasetIds.add(dataset.id);
        }
        for (Dataset dataset : datasets) {
            for (String dependency : dataset.dependsOn) {
                if (dataset.id.equals(dependency) || !datasetIds.contains(dependency)) {
                    throw new ContractException("Dataset 依赖不存在或自引用：" + dataset.id + " -> " + dependency);
                }
            }
        }
        for (Dataset dataset : datasets) requireAcyclicDataset(dataset.id, datasets, new HashSet<>(), new HashSet<>());
    }

    private static void validateHomeWidgetDataSources(
            List<HomeWidgetContribution> widgets,
            List<CapabilityRequirement> requirements,
            List<CapabilityContribution> contributions
    ) throws ContractException {
        Set<String> declared = new HashSet<>();
        for (CapabilityRequirement requirement : requirements) declared.add(requirement.id);
        for (HomeWidgetContribution widget : widgets) {
            String capability = GeneratedContract.capabilityForMethod(widget.dataSource);
            if (capability == null) {
                for (CapabilityContribution contribution : contributions) {
                    if (contribution.methods.contains(widget.dataSource)) {
                        capability = contribution.id;
                        break;
                    }
                }
            }
            if (capability == null || !declared.contains(capability)) {
                throw new ContractException("HomeWidget dataSource 未声明对应 Capability：" + widget.dataSource);
            }
        }
    }

    private static void validateTaskCapability(
            List<Task> tasks,
            List<CapabilityRequirement> requirements
    ) throws ContractException {
        if (tasks.isEmpty()) return;
        for (CapabilityRequirement requirement : requirements) {
            if ("scheduler".equals(requirement.id)) return;
        }
        throw new ContractException("声明后台任务时必须请求 scheduler Capability");
    }

    private static void validatePackageKind(
            Plugin plugin,
            List<UiEntry> uiEntries,
            List<ProviderEntry> providerEntries,
            List<CapabilityContribution> capabilityContributions,
            List<ToolContribution> tools,
            List<HomeWidgetContribution> widgets
    ) throws ContractException {
        if ("tool".equals(plugin.kind)) {
            if (!providerEntries.isEmpty()) {
                throw new ContractException("普通 Tool 不得携带 Native Provider");
            }
            if (uiEntries.isEmpty() != tools.isEmpty()) {
                throw new ContractException("普通插件的 UI entry 与工具贡献必须同时存在");
            }
            if (tools.isEmpty() && capabilityContributions.isEmpty()) {
                throw new ContractException("普通插件必须贡献 Tool 或 Worker Capability");
            }
            for (CapabilityContribution contribution : capabilityContributions) {
                if (contribution.workerEntry.isEmpty()) {
                    throw new ContractException("普通插件提供 Capability 时必须引用受限 Worker entry");
                }
            }
            return;
        }
        if (providerEntries.isEmpty()) {
            throw new ContractException("trusted-provider 必须携带 Native Provider entry");
        }
        if (uiEntries.isEmpty() != tools.isEmpty()) {
            throw new ContractException("受信 Provider 的 UI entry 与工具贡献必须同时存在");
        }
    }

    private static void requireAcyclicDataset(
            String id,
            List<Dataset> datasets,
            Set<String> visiting,
            Set<String> visited
    ) throws ContractException {
        if (visited.contains(id)) return;
        if (!visiting.add(id)) throw new ContractException("Dataset 依赖形成循环：" + id);
        Dataset current = null;
        for (Dataset dataset : datasets) if (dataset.id.equals(id)) current = dataset;
        if (current != null) {
            for (String dependency : current.dependsOn) {
                requireAcyclicDataset(dependency, datasets, visiting, visited);
            }
        }
        visiting.remove(id);
        visited.add(id);
    }

    private static Set<String> idsOfUi(List<UiEntry> entries) {
        Set<String> ids = new HashSet<>();
        for (UiEntry entry : entries) {
            ids.add(entry.id);
        }
        return ids;
    }

    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static boolean optionalBoolean(JSONObject value, String name, boolean fallback)
            throws ContractException, JSONException {
        return value.has(name) ? JsonContract.requireBoolean(value, name) : fallback;
    }

    public static final class Plugin {
        public final String id;
        public final String title;
        public final String description;
        public final String version;
        public final int versionCode;
        public final int minHostVersionCode;
        public final int minAndroidApi;
        public final String publisher;
        public final String homepage;
        public final String kind;

        Plugin(String id, String title, String description, String version, int versionCode,
               int minHostVersionCode, int minAndroidApi, String publisher, String homepage, String kind) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.version = version;
            this.versionCode = versionCode;
            this.minHostVersionCode = minHostVersionCode;
            this.minAndroidApi = minAndroidApi;
            this.publisher = publisher;
            this.homepage = homepage;
            this.kind = kind;
        }
    }

    public static final class UiEntry {
        public final String id;
        public final String type;
        public final String entry;

        UiEntry(String id, String type, String entry) {
            this.id = id;
            this.type = type;
            this.entry = entry;
        }
    }

    public static final class BackgroundEntry {
        public final String id;
        public final String type;
        public final String entry;
        public final boolean required;
        public final int timeoutMs;
        public final long maxHeapBytes;

        BackgroundEntry(String id, String type, String entry, boolean required, int timeoutMs, long maxHeapBytes) {
            this.id = id;
            this.type = type;
            this.entry = entry;
            this.required = required;
            this.timeoutMs = timeoutMs;
            this.maxHeapBytes = maxHeapBytes;
        }
    }

    public static final class ProviderEntry {
        public final String id;
        public final String entryClass;

        ProviderEntry(String id, String entryClass) {
            this.id = id;
            this.entryClass = entryClass;
        }
    }

    public static class Requirement {
        public final String id;
        public final String version;
        public final boolean optional;

        Requirement(String id, String version, boolean optional) {
            this.id = id;
            this.version = version;
            this.optional = optional;
        }
    }

    public static final class CapabilityRequirement extends Requirement {
        public final String scopesJson;

        CapabilityRequirement(String id, String version, boolean optional, String scopesJson) {
            super(id, version, optional);
            this.scopesJson = scopesJson;
        }
    }

    public static final class CapabilityContribution {
        public final String id;
        public final String version;
        public final String providerEntry;
        public final String workerEntry;
        public final List<String> methods;

        CapabilityContribution(String id, String version, String providerEntry,
                               String workerEntry, List<String> methods) {
            this.id = id;
            this.version = version;
            this.providerEntry = providerEntry;
            this.workerEntry = workerEntry;
            this.methods = immutable(methods);
        }
    }

    public static final class ToolContribution {
        public final String id;
        public final String uiEntry;
        public final String title;
        public final String description;

        ToolContribution(String id, String uiEntry, String title, String description) {
            this.id = id;
            this.uiEntry = uiEntry;
            this.title = title;
            this.description = description;
        }
    }

    public static final class HomeWidgetContribution {
        public final String id;
        public final String title;
        public final String template;
        public final String dataSource;
        public final List<String> sizes;

        HomeWidgetContribution(String id, String title, String template, String dataSource, List<String> sizes) {
            this.id = id;
            this.title = title;
            this.template = template;
            this.dataSource = dataSource;
            this.sizes = immutable(sizes);
        }
    }

    public static final class Dataset {
        public final String id;
        public final String title;
        public final String category;
        public final int formatVersion;
        public final boolean sensitive;
        public final List<String> restoreModes;
        public final List<String> dependsOn;
        public final String mediaType;
        public final String validator;
        public final int maxBytes;

        Dataset(String id, String title, String category, int formatVersion, boolean sensitive,
                List<String> restoreModes, List<String> dependsOn, String mediaType,
                String validator, int maxBytes) {
            this.id = id;
            this.title = title;
            this.category = category;
            this.formatVersion = formatVersion;
            this.sensitive = sensitive;
            this.restoreModes = immutable(restoreModes);
            this.dependsOn = immutable(dependsOn);
            this.mediaType = mediaType;
            this.validator = validator;
            this.maxBytes = maxBytes;
        }
    }

    public static final class Task {
        public final String id;
        public final String backgroundEntry;
        public final List<Trigger> triggers;
        public final Constraints constraints;
        public final String concurrencyPolicy;
        public final int maxConcurrency;
        public final int maxAttempts;
        public final int initialBackoffMs;

        Task(String id, String backgroundEntry, List<Trigger> triggers, Constraints constraints,
             String concurrencyPolicy, int maxConcurrency, int maxAttempts, int initialBackoffMs) {
            this.id = id;
            this.backgroundEntry = backgroundEntry;
            this.triggers = immutable(triggers);
            this.constraints = constraints;
            this.concurrencyPolicy = concurrencyPolicy;
            this.maxConcurrency = maxConcurrency;
            this.maxAttempts = maxAttempts;
            this.initialBackoffMs = initialBackoffMs;
        }
    }

    public static final class Trigger {
        public final String type;
        public final int intervalMinutes;
        public final String capability;
        public final String event;

        Trigger(String type, int intervalMinutes, String capability, String event) {
            this.type = type;
            this.intervalMinutes = intervalMinutes;
            this.capability = capability;
            this.event = event;
        }
    }

    public static final class Constraints {
        public final String network;
        public final boolean charging;
        public final boolean batteryNotLow;
        public final boolean storageNotLow;

        Constraints(String network, boolean charging, boolean batteryNotLow, boolean storageNotLow) {
            this.network = network;
            this.charging = charging;
            this.batteryNotLow = batteryNotLow;
            this.storageNotLow = storageNotLow;
        }
    }
}
