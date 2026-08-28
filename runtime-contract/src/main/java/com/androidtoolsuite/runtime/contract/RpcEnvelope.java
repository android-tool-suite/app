package com.androidtoolsuite.runtime.contract;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Set;

public final class RpcEnvelope {
    public enum Kind {
        HELLO("hello"),
        READY("ready"),
        REQUEST("request"),
        RESPONSE("response"),
        EVENT("event"),
        CANCEL("cancel");

        public final String wireName;

        Kind(String wireName) {
            this.wireName = wireName;
        }

        static Kind fromWire(String value) throws ContractException {
            for (Kind kind : values()) {
                if (kind.wireName.equals(value)) {
                    return kind;
                }
            }
            throw new ContractException("未知 RPC kind：" + value);
        }
    }

    public final ProtocolVersion protocol;
    public final Kind kind;
    public final String pluginId;
    public final String sessionId;
    public final String requestId;
    public final String method;
    public final String event;
    public final long sequence;
    public final int deadlineMs;
    public final JSONObject json;

    private RpcEnvelope(
            ProtocolVersion protocol,
            Kind kind,
            String pluginId,
            String sessionId,
            String requestId,
            String method,
            String event,
            long sequence,
            int deadlineMs,
            JSONObject json
    ) {
        this.protocol = protocol;
        this.kind = kind;
        this.pluginId = pluginId;
        this.sessionId = sessionId;
        this.requestId = requestId;
        this.method = method;
        this.event = event;
        this.sequence = sequence;
        this.deadlineMs = deadlineMs;
        this.json = json;
    }

    public static RpcEnvelope parse(String raw) throws ContractException {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > ContractLimits.MAX_RPC_BYTES) {
            throw new ContractException("RPC 消息超出大小限制");
        }
        try {
            JSONObject json = new JSONObject(raw);
            JsonContract.requireDepth(json, ContractLimits.MAX_JSON_DEPTH);
            JsonContract.requireOnlyKeys(json, "protocol", "kind", "pluginId", "sessionId", "requestId",
                    "method", "event", "sequence", "deadlineMs", "payload", "ok", "result", "error");
            ProtocolVersion protocol = ProtocolVersion.parse(json.optString("protocol", ""));
            Kind kind = Kind.fromWire(json.optString("kind", ""));
            String pluginId = ContractPatterns.requireId("pluginId", json.optString("pluginId", ""), 128);
            String sessionId = ContractPatterns.requireText("sessionId", json.optString("sessionId", ""), 128);
            if (sessionId.length() < 16) {
                throw new ContractException("sessionId 长度不足");
            }
            String requestId = ContractPatterns.requireText("requestId", json.optString("requestId", ""), 128);
            String method = json.optString("method", "").trim();
            String event = json.optString("event", "").trim();
            long sequence = json.optLong("sequence", -1L);
            int deadlineMs = json.optInt("deadlineMs", ContractLimits.DEFAULT_DEADLINE_MS);
            if (deadlineMs < 1 || deadlineMs > ContractLimits.MAX_DEADLINE_MS) {
                throw new ContractException("deadlineMs 超出范围");
            }
            validateKind(json, kind, method, event, sequence);
            return new RpcEnvelope(protocol, kind, pluginId, sessionId, requestId, method, event, sequence,
                    deadlineMs, json);
        } catch (JSONException error) {
            throw new ContractException("RPC 消息不是有效 JSON：" + error.getMessage(), error);
        }
    }

    public void requireIdentity(String expectedPluginId, String expectedSessionId) throws ContractException {
        if (!pluginId.equals(expectedPluginId) || !sessionId.equals(expectedSessionId)) {
            throw new ContractException("RPC transport 身份与 envelope 不一致");
        }
    }

    public Object payload() {
        return json.opt("payload");
    }

    public static JSONObject success(
            ProtocolVersion protocol,
            String pluginId,
            String sessionId,
            String requestId,
            Object result
    ) throws JSONException {
        return base(protocol, Kind.RESPONSE, pluginId, sessionId, requestId)
                .put("ok", true)
                .put("result", result == null ? JSONObject.NULL : result);
    }

    public static JSONObject failure(
            ProtocolVersion protocol,
            String pluginId,
            String sessionId,
            String requestId,
            RpcErrorCode code,
            String message,
            boolean retryable
    ) throws JSONException {
        JSONObject error = new JSONObject()
                .put("code", code.name())
                .put("message", message)
                .put("retryable", retryable);
        return base(protocol, Kind.RESPONSE, pluginId, sessionId, requestId)
                .put("ok", false)
                .put("error", error);
    }

    private static JSONObject base(
            ProtocolVersion protocol,
            Kind kind,
            String pluginId,
            String sessionId,
            String requestId
    ) throws JSONException {
        return new JSONObject()
                .put("protocol", protocol.toString())
                .put("kind", kind.wireName)
                .put("pluginId", pluginId)
                .put("sessionId", sessionId)
                .put("requestId", requestId);
    }

    private static void validateKind(JSONObject json, Kind kind, String method, String event, long sequence)
            throws ContractException, JSONException {
        switch (kind) {
            case REQUEST:
                if (method.length() < 3 || method.length() > 160 || !json.has("payload")) {
                    throw new ContractException("request 必须包含 method 和 payload");
                }
                break;
            case RESPONSE:
                if (!json.has("ok") || !(json.get("ok") instanceof Boolean)) {
                    throw new ContractException("response 必须包含布尔 ok");
                }
                boolean ok = json.getBoolean("ok");
                if (ok == json.has("error") || ok != json.has("result")) {
                    throw new ContractException("response 必须只包含 result 或 error");
                }
                if (!ok) {
                    JSONObject error = json.optJSONObject("error");
                    if (error == null) {
                        throw new ContractException("response.error 必须是对象");
                    }
                    JsonContract.requireOnlyKeys(error, "code", "message", "retryable", "details");
                    try {
                        RpcErrorCode.valueOf(error.optString("code", ""));
                    } catch (IllegalArgumentException invalid) {
                        throw new ContractException("未知 RPC error code");
                    }
                    ContractPatterns.requireText("error.message", error.optString("message", ""), 320);
                    JsonContract.requireBoolean(error, "retryable");
                }
                break;
            case EVENT:
                if (event.length() < 3 || event.length() > 160 || sequence < 0 || !json.has("payload")) {
                    throw new ContractException("event 必须包含 event、sequence 和 payload");
                }
                break;
            case CANCEL:
                if ("0".equals(json.optString("requestId", ""))) {
                    throw new ContractException("cancel 必须引用非握手 requestId");
                }
                break;
            case HELLO:
            case READY:
                break;
        }
        if (!method.isEmpty() && kind != Kind.REQUEST) {
            throw new ContractException("只有 request 可以包含 method");
        }
        if (!event.isEmpty() && kind != Kind.EVENT) {
            throw new ContractException("只有 event 可以包含 event 名称");
        }
    }
}
