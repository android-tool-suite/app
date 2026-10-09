package com.androidtoolsuite.app.plugin.runtime;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Bounded, in-memory UI metadata. It never schedules or cancels plugin business work. */
public final class PluginUiOperationRegistry {
    public static final int MAX_ACTIVE_PER_SESSION = 4;
    public static final int MAX_ACTIVE_TOTAL = 16;
    public static final int MAX_IDS_PER_SESSION = 1024;
    private final Map<String, Map<String, Operation>> sessions = new LinkedHashMap<>();

    public static final class Report {
        public final String id, label, status, detail;
        private Report(String id, String label, String status, String detail) {
            this.id = id; this.label = label; this.status = status; this.detail = detail;
        }
        public static Report parse(JSONObject payload) throws CapabilityFailure {
            Set<String> allowed = Set.of("id", "label", "status", "detail");
            Iterator<String> keys = payload.keys();
            while (keys.hasNext()) if (!allowed.contains(keys.next())) {
                throw CapabilityFailure.invalid("Operation metadata accepts only id, label, status and detail");
            }
            String id = text(payload, "id", 96, false);
            String label = text(payload, "label", 80, false);
            String status = text(payload, "status", 16, false);
            if (!Set.of("running", "success", "failed", "cancelled").contains(status)) {
                throw CapabilityFailure.invalid("Unsupported operation status");
            }
            return new Report(id, label, status, payload.has("detail") ? text(payload, "detail", 240, true) : "");
        }
        private static String text(JSONObject payload, String key, int limit, boolean allowEmpty) throws CapabilityFailure {
            Object raw = payload.opt(key);
            if (!(raw instanceof String)) throw CapabilityFailure.invalid(key + " must be a string");
            String value = (String) raw;
            if (value.length() > limit || (!allowEmpty && value.trim().isEmpty())
                    || value.chars().anyMatch(character -> character < 32 || character == 127)) {
                throw CapabilityFailure.invalid(key + " must be bounded, single-line metadata");
            }
            return value;
        }
    }

    public static final class Operation {
        public final String taskId = UUID.randomUUID().toString();
        public final String pluginId, sessionId, clientId, label;
        public String status, detail;
        private Operation(String pluginId, String sessionId, Report report) {
            this.pluginId = pluginId; this.sessionId = sessionId; this.clientId = report.id;
            this.label = report.label; this.status = report.status; this.detail = report.detail;
        }
    }

    public synchronized Operation report(String pluginId, String sessionId, Report report,
                                         boolean sessionAlive, boolean visibleAtDispatch, boolean userGesture) throws CapabilityFailure {
        if (!sessionAlive) throw new CapabilityFailure("CANCELLED", "操作所属页面会话已关闭", false);
        String scope = pluginId + '\0' + sessionId;
        Map<String, Operation> operations = sessions.get(scope);
        Operation operation = operations == null ? null : operations.get(report.id);
        if (operation == null) {
            if (!"running".equals(report.status)) throw CapabilityFailure.invalid("An operation must start with running in its own session");
            if (!visibleAtDispatch || !userGesture) {
                throw new CapabilityFailure("CONSENT_REQUIRED", "只能报告当前页面中由你主动开始的操作", false);
            }
            if (operations != null && operations.size() >= MAX_IDS_PER_SESSION) {
                throw new CapabilityFailure("RESOURCE_LIMIT", "当前页面的操作记录已达到上限", false);
            }
            int sessionActive = operations == null ? 0 : activeCount(operations);
            int totalActive = sessions.values().stream().mapToInt(PluginUiOperationRegistry::activeCount).sum();
            if (sessionActive >= MAX_ACTIVE_PER_SESSION || totalActive >= MAX_ACTIVE_TOTAL) {
                throw new CapabilityFailure("RESOURCE_LIMIT", "同时报告的操作过多", true);
            }
            operation = new Operation(pluginId, sessionId, report);
            if (operations == null) { operations = new LinkedHashMap<>(); sessions.put(scope, operations); }
            operations.put(report.id, operation);
            return operation;
        }
        if (!operation.label.equals(report.label)) throw CapabilityFailure.invalid("An operation label cannot change");
        if (!"running".equals(operation.status)) {
            if (operation.status.equals(report.status) && operation.detail.equals(report.detail)) return operation;
            throw CapabilityFailure.invalid("A completed operation cannot restart or change its result");
        }
        operation.status = report.status;
        operation.detail = report.detail;
        return operation;
    }

    public synchronized List<Operation> closeSession(String sessionId) {
        List<Operation> cancelled = new ArrayList<>();
        Iterator<Map.Entry<String, Map<String, Operation>>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map<String, Operation> operations = iterator.next().getValue();
            if (operations.isEmpty() || !operations.values().iterator().next().sessionId.equals(sessionId)) continue;
            for (Operation operation : operations.values()) if ("running".equals(operation.status)) {
                operation.status = "cancelled";
                operation.detail = "页面已关闭，操作状态报告已结束。";
                cancelled.add(operation);
            }
            iterator.remove();
        }
        return cancelled;
    }

    private static int activeCount(Map<String, Operation> operations) {
        return (int) operations.values().stream().filter(item -> "running".equals(item.status)).count();
    }
}
