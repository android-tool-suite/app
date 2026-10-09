package com.androidtoolsuite.runtime.contract;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ContractResourcesTest {
    @Test public void widgetSettingsAreScopedToTheCallingPlugin() throws Exception {
        JSONObject params=resource("contracts/capabilities/app-1.json").getJSONObject("methods").getJSONObject("app.openWidgetSettings").getJSONObject("params");
        assertEquals(false,params.getBoolean("additionalProperties"));
        assertEquals(1,params.getJSONObject("properties").length());
        assertTrue(params.getJSONObject("properties").has("instanceId"));
        assertEquals("app",GeneratedContract.capabilityForMethod("app.openWidgetSettings"));
        JSONObject properties=resource("contracts/manifest-v3.schema.json").getJSONObject("$defs").getJSONObject("homeWidget").getJSONObject("properties");
        assertTrue(properties.has("configurationSource"));
        JSONArray templates=properties.getJSONObject("template").getJSONArray("enum");
        Set<String> names=new HashSet<>();for(int i=0;i<templates.length();i++)names.add(templates.getString(i));
        assertTrue(names.contains("trend"));assertTrue(names.contains("rows"));
    }
    @Test
    public void operationReportsExposeOnlyBoundedMetadata() throws Exception {
        JSONObject method = resource("contracts/capabilities/app-1.json").getJSONObject("methods").getJSONObject("app.reportOperation");
        JSONObject params = method.getJSONObject("params");
        assertEquals(false, params.getBoolean("additionalProperties"));
        JSONObject fields = params.getJSONObject("properties");
        assertEquals(4, fields.length());
        assertEquals(96, fields.getJSONObject("id").getInt("maxLength"));
        assertEquals(80, fields.getJSONObject("label").getInt("maxLength"));
        assertEquals(240, fields.getJSONObject("detail").getInt("maxLength"));
        assertEquals("app", GeneratedContract.capabilityForMethod("app.reportOperation"));
    }

    @Test
    public void backNavigationContractIsBoundToCallingSession() throws Exception {
        JSONObject app = resource("contracts/capabilities/app-1.json");
        JSONObject params = app.getJSONObject("methods").getJSONObject("app.setBackNavigation").getJSONObject("params");
        assertEquals("enabled", params.getJSONArray("required").getString(0));
        assertEquals("boolean", params.getJSONObject("properties").getJSONObject("enabled").getString("type"));
        assertEquals(1, params.getJSONObject("properties").length());
        assertEquals(false, params.getBoolean("additionalProperties"));
        assertEquals("app", GeneratedContract.capabilityForMethod("app.setBackNavigation"));
        assertEquals("app", GeneratedContract.capabilityForEvent("app.backRequested"));
    }

    @Test
    public void indexSchemasAndCapabilitiesAreValidJson() throws Exception {
        JSONObject index = resource("contracts/contract-index.json");
        assertEquals(3, index.getInt("packageFormatVersion"));
        resource("contracts/" + index.getJSONObject("schemas").getString("manifest"));
        resource("contracts/" + index.getJSONObject("schemas").getString("rpc"));
        resource("contracts/" + index.getJSONObject("schemas").getString("declarativeUi"));

        Set<String> ids = new HashSet<>();
        Set<String> methodNames = new HashSet<>();
        JSONArray capabilities = index.getJSONArray("capabilities");
        for (int item = 0; item < capabilities.length(); item++) {
            JSONObject capability = resource("contracts/" + capabilities.getString(item));
            String id = capability.getString("id");
            assertTrue(ids.add(id));
            JSONObject permission = capability.getJSONObject("permission");
            assertTrue(Set.of("implicit", "user").contains(permission.getString("mode")));
            assertTrue(Set.of("normal", "sensitive", "restricted").contains(permission.getString("risk")));
            assertTrue(!permission.getString("title").isBlank());
            assertTrue(!permission.getString("description").isBlank());
            JSONObject methods = capability.getJSONObject("methods");
            Iterator<String> names = methods.keys();
            while (names.hasNext()) {
                String name = names.next();
                assertTrue(name.contains("."));
                assertTrue(methodNames.add(name));
            }
        }
    }

    private static JSONObject resource(String path) throws Exception {
        try (InputStream input = ContractResourcesTest.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Missing resource " + path);
            }
            return new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
