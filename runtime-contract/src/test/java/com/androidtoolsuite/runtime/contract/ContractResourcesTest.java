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
