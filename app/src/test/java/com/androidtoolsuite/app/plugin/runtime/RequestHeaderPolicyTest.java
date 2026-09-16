package com.androidtoolsuite.app.plugin.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertThrows;

public class RequestHeaderPolicyTest {
    @Test public void acceptsTokenHeadersUsedByMihoyo() throws Exception {
        JSONObject scopes = new JSONObject();
        for (String header : new String[]{"x-rpc-device_id", "x-rpc-app_version", "x-rpc-client_type", "x-rpc-device_fp", "DS", "Content-Type"}) {
            HostCapabilityProviders.validateRequestHeader(header, "fixture-only", scopes);
        }
    }

    @Test public void rejectsMalformedNamesAndHeaderInjection() throws Exception {
        for (String header : new String[]{"bad name", "bad:name", "x\r\nHost", "", "x".repeat(65)}) {
            assertThrows(CapabilityFailure.class, () -> HostCapabilityProviders.validateRequestHeader(header, "ok", new JSONObject()));
        }
        for (String value : new String[]{"value\r\nHost: injected", "a\nb", "a\0b", "a\u007fb"}) {
            assertThrows(CapabilityFailure.class, () -> HostCapabilityProviders.validateRequestHeader("X-Test", value, new JSONObject()));
        }
    }

    @Test public void retainsForbiddenAndCredentialHeaderPolicy() throws Exception {
        JSONObject scopes = new JSONObject().put("headers", new JSONArray().put("cookie"));
        HostCapabilityProviders.validateRequestHeader("Cookie", "fixture=only", scopes);
        for (String header : new String[]{"Host", "Connection", "Content-Length", "Proxy-Authorization", "Authorization"}) {
            assertThrows(CapabilityFailure.class, () -> HostCapabilityProviders.validateRequestHeader(header, "fixture", scopes));
        }
        assertThrows(CapabilityFailure.class, () -> HostCapabilityProviders.validateRequestHeader("Cookie", "fixture=only", new JSONObject()));
    }
}
