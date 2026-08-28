package com.androidtoolsuite.runtime.contract;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict, platform-neutral parser for host-rendered Runtime v2 UI documents. */
public final class DeclarativeUiDocument {
    public static final int FORMAT_VERSION = 1;
    private static final int MAX_DOCUMENT_BYTES = 256 * 1024;
    private static final int MAX_NODES = 256;
    private static final int MAX_NODE_DEPTH = 16;
    private static final Pattern STATE_PATH = Pattern.compile(
            "^[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*){0,7}$"
    );
    private static final Pattern METHOD = Pattern.compile(
            "^[a-z][A-Za-z0-9]*(?:\\.[a-z][A-Za-z0-9]*)+$"
    );
    private static final Set<String> CONTAINER_TYPES = Set.of("column", "row", "card");
    private static final Set<String> NODE_TYPES = Set.of(
            "column", "row", "card", "section", "text", "status", "icon", "metric", "notice",
            "button", "state", "divider", "spacer"
    );

    public final JSONObject initialState;
    public final List<Query> queries;
    public final Map<String, Query> queriesById;
    public final List<Action> actions;
    public final Map<String, Action> actionsById;
    public final Node body;

    private DeclarativeUiDocument(
            JSONObject initialState,
            List<Query> queries,
            Map<String, Query> queriesById,
            List<Action> actions,
            Map<String, Action> actionsById,
            Node body
    ) {
        this.initialState = copy(initialState);
        this.queries = Collections.unmodifiableList(new ArrayList<>(queries));
        this.queriesById = Collections.unmodifiableMap(new LinkedHashMap<>(queriesById));
        this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
        this.actionsById = Collections.unmodifiableMap(new LinkedHashMap<>(actionsById));
        this.body = body;
    }

    public static DeclarativeUiDocument parse(String raw) throws ContractException {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
            throw new ContractException("声明式 UI 文档为空或超出大小限制");
        }
        try {
            JSONObject root = new JSONObject(raw);
            JsonContract.requireDepth(root, ContractLimits.MAX_JSON_DEPTH);
            JsonContract.requireOnlyKeys(root, "$schema", "formatVersion", "initialState", "queries", "actions", "body");
            if (JsonContract.requirePositiveInt(root, "formatVersion") != FORMAT_VERSION) {
                throw new ContractException("声明式 UI formatVersion 必须是 1");
            }
            JSONObject initialState = root.optJSONObject("initialState");
            if (root.has("initialState") && initialState == null) {
                throw new ContractException("声明式 UI initialState 必须是对象");
            }
            if (initialState == null) initialState = new JSONObject();
            if (initialState.length() > 64 || initialState.toString().getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
                throw new ContractException("声明式 UI initialState 超出限制");
            }

            List<Query> queries = new ArrayList<>();
            Map<String, Query> queriesById = new LinkedHashMap<>();
            JSONArray queryArray = root.optJSONArray("queries");
            if (root.has("queries") && queryArray == null) {
                throw new ContractException("声明式 UI queries 必须是数组");
            }
            if (queryArray != null) {
                if (queryArray.length() > 32) throw new ContractException("声明式 UI query 项目过多");
                for (int index = 0; index < queryArray.length(); index++) {
                    Query query = parseQuery(JsonContract.requireObject(queryArray, index, "queries"));
                    if (queriesById.put(query.id, query) != null) {
                        throw new ContractException("声明式 UI query id 重复：" + query.id);
                    }
                    queries.add(query);
                }
            }

            List<Action> actions = new ArrayList<>();
            Map<String, Action> actionsById = new LinkedHashMap<>();
            JSONArray actionArray = root.optJSONArray("actions");
            if (root.has("actions") && actionArray == null) {
                throw new ContractException("声明式 UI actions 必须是数组");
            }
            if (actionArray != null) {
                if (actionArray.length() > 32) throw new ContractException("声明式 UI action 项目过多");
                for (int index = 0; index < actionArray.length(); index++) {
                    Action action = parseAction(JsonContract.requireObject(actionArray, index, "actions"));
                    if (actionsById.put(action.id, action) != null) {
                        throw new ContractException("声明式 UI action id 重复：" + action.id);
                    }
                    actions.add(action);
                }
            }

            int[] count = {0};
            Node body = parseBody(JsonContract.requireObject(root, "body"), count);
            if ("webview".equals(body.type)
                    && (initialState.length() != 0 || !queries.isEmpty() || !actions.isEmpty())) {
                throw new ContractException("WebView 声明不能同时定义状态、Query 或 Action");
            }
            Set<String> referencedActions = new LinkedHashSet<>();
            collectActionReferences(body, referencedActions);
            if (!actionsById.keySet().containsAll(referencedActions)) {
                referencedActions.removeAll(actionsById.keySet());
                throw new ContractException("声明式 UI 节点引用未知 action：" + referencedActions.iterator().next());
            }
            for (Action action : actions) {
                for (String queryId : action.refresh) {
                    if (!queriesById.containsKey(queryId)) {
                        throw new ContractException("声明式 UI action 引用未知 query：" + queryId);
                    }
                }
            }
            return new DeclarativeUiDocument(initialState, queries, queriesById, actions, actionsById, body);
        } catch (JSONException error) {
            throw new ContractException("声明式 UI 不是有效 JSON：" + error.getMessage(), error);
        }
    }

    public void validateAgainst(RuntimePluginManifest manifest) throws ContractException {
        Set<String> declared = new HashSet<>();
        for (RuntimePluginManifest.CapabilityRequirement requirement : manifest.capabilityRequirements) {
            declared.add(requirement.id);
        }
        for (Invocation invocation : invocations()) {
            if (!declared.contains(invocation.capability)) {
                throw new ContractException("声明式 UI 调用未在 manifest 声明的 Capability：" + invocation.capability);
            }
            String expected = GeneratedContract.capabilityForMethod(invocation.method);
            if ((expected != null && !invocation.capability.equals(expected))
                    || (expected == null
                    && !(invocation.method.equals(invocation.capability)
                    || invocation.method.startsWith(invocation.capability + ".")))) {
                throw new ContractException("声明式 UI method 与 Capability 不匹配：" + invocation.method);
            }
        }
    }

    public boolean isWebView() {
        return "webview".equals(body.type);
    }

    public String webEntry() {
        return isWebView() ? body.raw.optString("entry", "") : "";
    }

    private List<Invocation> invocations() {
        List<Invocation> values = new ArrayList<>();
        values.addAll(queries);
        values.addAll(actions);
        return values;
    }

    private static Query parseQuery(JSONObject item) throws ContractException, JSONException {
        JsonContract.requireOnlyKeys(item, "id", "capability", "method", "payload", "target", "required", "deadlineMs");
        return new Query(
                id(item, "id"),
                capability(item),
                method(item),
                payload(item),
                statePath(item.optString("target", ""), "query.target"),
                JsonContract.requireBoolean(item, "required"),
                deadline(item)
        );
    }

    private static Action parseAction(JSONObject item) throws ContractException {
        JsonContract.requireOnlyKeys(item, "id", "capability", "method", "payload", "target", "refresh",
                "successMessage", "confirm", "deadlineMs");
        List<String> refresh = Collections.emptyList();
        JSONArray refreshArray = item.optJSONArray("refresh");
        if (item.has("refresh") && refreshArray == null) {
            throw new ContractException("action.refresh 必须是数组");
        }
        if (refreshArray != null) {
            refresh = JsonContract.stringList(refreshArray, "action.refresh", 0, 16, 64);
            for (String value : refresh) ContractPatterns.requireId("action.refresh", value, 64);
        }
        Confirm confirm = null;
        JSONObject confirmObject = item.optJSONObject("confirm");
        if (item.has("confirm") && confirmObject == null) {
            throw new ContractException("action.confirm 必须是对象");
        }
        if (confirmObject != null) {
            JsonContract.requireOnlyKeys(confirmObject, "title", "body", "confirmLabel");
            confirm = new Confirm(
                    text(confirmObject, "title", 80, true),
                    text(confirmObject, "body", 320, true),
                    text(confirmObject, "confirmLabel", 40, true)
            );
        }
        return new Action(
                id(item, "id"),
                capability(item),
                method(item),
                payload(item),
                item.has("target") ? statePath(item.optString("target", ""), "action.target") : "",
                refresh,
                text(item, "successMessage", 160, false),
                confirm,
                deadline(item)
        );
    }

    private static Node parseNode(JSONObject raw, int depth, int[] count) throws ContractException {
        if (depth > MAX_NODE_DEPTH || ++count[0] > MAX_NODES) {
            throw new ContractException("声明式 UI 节点数量或层级超出限制");
        }
        String type = raw.optString("type", "");
        if (!NODE_TYPES.contains(type)) throw new ContractException("未知声明式 UI 节点：" + type);
        List<Node> children = new ArrayList<>();
        switch (type) {
            case "column":
            case "row":
            case "card":
                JsonContract.requireOnlyKeys(raw, "type", "children", "gap", "tone", "when");
                requireEnum(raw, "gap", Set.of("small", "medium", "large"), false);
                requireEnum(raw, "tone", Set.of("surface", "primary", "success", "warning", "danger", "info"), false);
                children = parseChildren(JsonContract.requireArray(raw, "children"), depth, count);
                break;
            case "section":
                JsonContract.requireOnlyKeys(raw, "type", "title", "subtitle", "children", "when");
                textValue(raw.opt("title"), "section.title", true);
                textValue(raw.opt("subtitle"), "section.subtitle", false);
                children = parseChildren(JsonContract.requireArray(raw, "children"), depth, count);
                break;
            case "text":
            case "status":
                JsonContract.requireOnlyKeys(raw, "type", "value", "style", "tone", "when");
                textValue(raw.opt("value"), type + ".value", true);
                requireEnum(raw, "style", Set.of("headline", "title", "body", "supporting", "label"), false);
                requireEnum(raw, "tone", Set.of("default", "primary", "muted", "success", "warning", "danger", "info"), false);
                break;
            case "icon":
                JsonContract.requireOnlyKeys(raw, "type", "name", "tone", "when");
                requireEnum(raw, "name", Set.of("check-circle", "cloud-off"), true);
                requireEnum(raw, "tone", Set.of("default", "primary", "muted", "success", "warning", "danger", "info"), false);
                break;
            case "metric":
                JsonContract.requireOnlyKeys(raw, "type", "label", "value", "supporting", "tone", "when");
                textValue(raw.opt("label"), "metric.label", true);
                textValue(raw.opt("value"), "metric.value", true);
                textValue(raw.opt("supporting"), "metric.supporting", false);
                requireEnum(raw, "tone", Set.of("default", "primary", "success", "warning", "danger", "info"), false);
                break;
            case "notice":
                JsonContract.requireOnlyKeys(raw, "type", "value", "tone", "when");
                textValue(raw.opt("value"), "notice.value", true);
                requireEnum(raw, "tone", Set.of("neutral", "info", "success", "warning", "danger"), false);
                break;
            case "button":
                JsonContract.requireOnlyKeys(raw, "type", "label", "action", "style", "fullWidth", "enabledWhen", "when");
                textValue(raw.opt("label"), "button.label", true);
                id(raw, "action");
                requireEnum(raw, "style", Set.of("primary", "secondary", "text"), false);
                optionalBoolean(raw, "fullWidth");
                condition(raw.optJSONObject("enabledWhen"), "button.enabledWhen");
                break;
            case "state":
                JsonContract.requireOnlyKeys(raw, "type", "variant", "title", "body", "action", "actionLabel", "when");
                requireEnum(raw, "variant", Set.of("loading", "empty", "error"), true);
                textValue(raw.opt("title"), "state.title", true);
                textValue(raw.opt("body"), "state.body", false);
                if (raw.has("action")) {
                    id(raw, "action");
                    text(raw, "actionLabel", 40, true);
                } else if (raw.has("actionLabel")) {
                    throw new ContractException("state.actionLabel 只能与 action 同时声明");
                }
                break;
            case "divider":
                JsonContract.requireOnlyKeys(raw, "type", "when");
                break;
            case "spacer":
                JsonContract.requireOnlyKeys(raw, "type", "size", "when");
                requireEnum(raw, "size", Set.of("small", "medium", "large"), false);
                break;
            default:
                throw new ContractException("未知声明式 UI 节点：" + type);
        }
        condition(raw.optJSONObject("when"), type + ".when");
        return new Node(type, copy(raw), children);
    }

    private static Node parseBody(JSONObject raw, int[] count) throws ContractException {
        if ("webview".equals(raw.optString("type", ""))) {
            JsonContract.requireOnlyKeys(raw, "type", "entry");
            String entry = PackagePathPolicy.validateFilePath(raw.optString("entry", ""));
            if (!entry.startsWith("web/") || !entry.endsWith(".html")) {
                throw new ContractException("WebView entry 必须位于 web/ 且以 .html 结尾");
            }
            count[0] = 1;
            return new Node("webview", copy(raw), Collections.emptyList());
        }
        Node body = parseNode(raw, 1, count);
        if (!"column".equals(body.type)) {
            throw new ContractException("声明式 UI body 根节点必须是 column 或 webview");
        }
        return body;
    }

    private static List<Node> parseChildren(JSONArray array, int depth, int[] count) throws ContractException {
        if (array.length() > 64) throw new ContractException("声明式 UI children 项目过多");
        List<Node> values = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            values.add(parseNode(JsonContract.requireObject(array, index, "children"), depth + 1, count));
        }
        return values;
    }

    private static void collectActionReferences(Node node, Set<String> target) {
        if ("button".equals(node.type) || ("state".equals(node.type) && node.raw.has("action"))) {
            target.add(node.raw.optString("action", ""));
        }
        for (Node child : node.children) collectActionReferences(child, target);
    }

    private static void textValue(Object raw, String label, boolean required) throws ContractException {
        if (raw == null || raw == JSONObject.NULL) {
            if (required) throw new ContractException("缺少 " + label);
            return;
        }
        if (raw instanceof String) {
            if (((String) raw).length() > 320) throw new ContractException(label + " 超出长度限制");
            return;
        }
        if (!(raw instanceof JSONObject)) throw new ContractException(label + " 必须是文本或状态绑定");
        JSONObject binding = (JSONObject) raw;
        JsonContract.requireOnlyKeys(binding, "path", "fallback");
        statePath(binding.optString("path", ""), label + ".path");
        text(binding, "fallback", 320, false);
    }

    private static void condition(JSONObject condition, String label) throws ContractException {
        if (condition == null) return;
        JsonContract.requireOnlyKeys(condition, "path", "equals");
        statePath(condition.optString("path", ""), label + ".path");
        if (!condition.has("equals")) throw new ContractException("缺少 " + label + ".equals");
        Object equals = condition.opt("equals");
        if (equals != null && equals != JSONObject.NULL && !(equals instanceof String)
                && !(equals instanceof Number) && !(equals instanceof Boolean)) {
            throw new ContractException(label + ".equals 只能是标量");
        }
    }

    private static String capability(JSONObject item) throws ContractException {
        return ContractPatterns.requireId("capability", item.optString("capability", ""), 128);
    }

    private static String method(JSONObject item) throws ContractException {
        String value = text(item, "method", 160, true);
        if (!METHOD.matcher(value).matches()) throw new ContractException("声明式 UI method 格式无效");
        return value;
    }

    private static JSONObject payload(JSONObject item) throws ContractException {
        JSONObject value = item.optJSONObject("payload");
        if (item.has("payload") && value == null) {
            throw new ContractException("声明式 UI payload 必须是对象");
        }
        if (value == null) return new JSONObject();
        if (value.length() > 64 || value.toString().getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
            throw new ContractException("声明式 UI payload 超出限制");
        }
        validatePayloadTemplate(value);
        return copy(value);
    }

    private static void validatePayloadTemplate(Object value) throws ContractException {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (object.has("$state")) {
                if (object.length() != 1 || !(object.opt("$state") instanceof String)) {
                    throw new ContractException("payload $state 绑定必须是唯一字符串字段");
                }
                statePath(object.optString("$state", ""), "payload.$state");
                return;
            }
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) validatePayloadTemplate(object.opt(keys.next()));
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) {
                validatePayloadTemplate(array.opt(index));
            }
        }
    }

    private static int deadline(JSONObject item) throws ContractException {
        Object raw = item.opt("deadlineMs");
        if (raw == null || raw == JSONObject.NULL) return ContractLimits.DEFAULT_DEADLINE_MS;
        if (!(raw instanceof Number)) throw new ContractException("deadlineMs 必须是整数");
        long value = ((Number) raw).longValue();
        if (value < 1_000 || value > ContractLimits.MAX_DEADLINE_MS
                || ((Number) raw).doubleValue() != (double) value) {
            throw new ContractException("deadlineMs 超出范围");
        }
        return (int) value;
    }

    private static String id(JSONObject item, String name) throws ContractException {
        return ContractPatterns.requireId(name, item.optString(name, ""), 64);
    }

    private static String statePath(String value, String label) throws ContractException {
        if (!STATE_PATH.matcher(value).matches()) throw new ContractException(label + " 格式无效");
        return value;
    }

    private static String text(JSONObject item, String name, int maxLength, boolean required)
            throws ContractException {
        if (!item.has(name) || item.isNull(name)) {
            if (required) throw new ContractException("缺少 " + name);
            return "";
        }
        Object raw = item.opt(name);
        if (!(raw instanceof String)) throw new ContractException(name + " 必须是字符串");
        String value = ((String) raw).trim();
        if ((required && value.isEmpty()) || value.length() > maxLength) {
            throw new ContractException(name + " 为空或超出长度限制");
        }
        return value;
    }

    private static void optionalBoolean(JSONObject item, String name) throws ContractException {
        if (item.has(name) && !(item.opt(name) instanceof Boolean)) {
            throw new ContractException(name + " 必须是布尔值");
        }
    }

    private static void requireEnum(JSONObject item, String name, Set<String> values, boolean required)
            throws ContractException {
        if (!item.has(name) || item.isNull(name)) {
            if (required) throw new ContractException("缺少 " + name);
            return;
        }
        Object raw = item.opt(name);
        if (!(raw instanceof String) || !values.contains(raw)) {
            throw new ContractException(name + " 取值无效");
        }
    }

    private static JSONObject copy(JSONObject value) {
        try {
            return new JSONObject(value.toString());
        } catch (JSONException impossible) {
            throw new IllegalStateException("Validated JSON could not be copied", impossible);
        }
    }

    public abstract static class Invocation {
        public final String id;
        public final String capability;
        public final String method;
        public final JSONObject payload;
        public final String target;
        public final int deadlineMs;

        Invocation(String id, String capability, String method, JSONObject payload, String target, int deadlineMs) {
            this.id = id;
            this.capability = capability;
            this.method = method;
            this.payload = copy(payload);
            this.target = target;
            this.deadlineMs = deadlineMs;
        }
    }

    public static final class Query extends Invocation {
        public final boolean required;

        Query(String id, String capability, String method, JSONObject payload, String target,
              boolean required, int deadlineMs) {
            super(id, capability, method, payload, target, deadlineMs);
            this.required = required;
        }
    }

    public static final class Action extends Invocation {
        public final List<String> refresh;
        public final String successMessage;
        public final Confirm confirm;

        Action(String id, String capability, String method, JSONObject payload, String target,
               List<String> refresh, String successMessage, Confirm confirm, int deadlineMs) {
            super(id, capability, method, payload, target, deadlineMs);
            this.refresh = Collections.unmodifiableList(new ArrayList<>(refresh));
            this.successMessage = successMessage;
            this.confirm = confirm;
        }
    }

    public static final class Confirm {
        public final String title;
        public final String body;
        public final String confirmLabel;

        Confirm(String title, String body, String confirmLabel) {
            this.title = title;
            this.body = body;
            this.confirmLabel = confirmLabel;
        }
    }

    public static final class Node {
        public final String type;
        public final JSONObject raw;
        public final List<Node> children;

        Node(String type, JSONObject raw, List<Node> children) {
            this.type = type;
            this.raw = raw;
            this.children = Collections.unmodifiableList(new ArrayList<>(children));
        }
    }
}
