package com.androidtoolsuite.runtime.contract;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RpcEnvelopeTest {
    private static final String SESSION = "0123456789abcdef0123456789abcdef";

    @Test
    public void parsesBoundRequest() throws Exception {
        String raw = new JSONObject()
                .put("protocol", "2.0")
                .put("kind", "request")
                .put("pluginId", "sample.hello_web")
                .put("sessionId", SESSION)
                .put("requestId", "42")
                .put("method", "app.getSession")
                .put("payload", new JSONObject())
                .toString();

        RpcEnvelope envelope = RpcEnvelope.parse(raw);
        envelope.requireIdentity("sample.hello_web", SESSION);

        assertEquals(RpcEnvelope.Kind.REQUEST, envelope.kind);
        assertEquals("app.getSession", envelope.method);
        assertEquals(ContractLimits.DEFAULT_DEADLINE_MS, envelope.deadlineMs);
    }

    @Test
    public void rejectsTransportIdentityMismatch() throws Exception {
        String raw = new JSONObject()
                .put("protocol", "2.0")
                .put("kind", "cancel")
                .put("pluginId", "sample.hello_web")
                .put("sessionId", SESSION)
                .put("requestId", "42")
                .toString();

        RpcEnvelope envelope = RpcEnvelope.parse(raw);

        assertThrows(ContractException.class, () -> envelope.requireIdentity("sample.other", SESSION));
    }

    @Test
    public void responseHasExactlyOneResultBranch() throws Exception {
        JSONObject response = RpcEnvelope.failure(
                ProtocolVersion.CURRENT,
                "sample.hello_web",
                SESSION,
                "42",
                RpcErrorCode.NOT_SUPPORTED,
                "当前设备不支持此能力",
                false
        );

        RpcEnvelope parsed = RpcEnvelope.parse(response.toString());

        assertEquals(RpcEnvelope.Kind.RESPONSE, parsed.kind);
        assertTrue(parsed.json.has("error"));
    }

    @Test
    public void negotiatesHighestCompatibleMajor() throws Exception {
        ProtocolVersion selected = ProtocolVersion.negotiate(
                List.of(new ProtocolVersion(1, 9), new ProtocolVersion(2, 1)),
                List.of(new ProtocolVersion(2, 0), new ProtocolVersion(3, 0))
        );

        assertEquals(new ProtocolVersion(2, 0), selected);
    }
}
