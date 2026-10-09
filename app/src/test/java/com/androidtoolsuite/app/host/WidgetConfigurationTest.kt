package com.androidtoolsuite.app.host

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WidgetConfigurationTest {
    private fun schema()=JSONObject("""{"configuration":{"account":"a","pools":["role"]},"fields":[{"key":"account","label":"账号","type":"select","options":[{"value":"a","label":"A"},{"value":"b","label":"B"}]},{"key":"pools","label":"卡池","type":"multi","maxItems":2,"options":[{"value":"role","label":"角色"},{"value":"weapon","label":"武器"}]}]}""")
    @Test fun onlyDeclaredConfigurationFieldsArePersisted() {
        val draft=JSONObject("""{"account":"a","pools":["role"],"unrelated":"never persist"}""")
        val result=WidgetConfiguration.validated(schema(),draft)
        assertFalse(result.has("unrelated"));assertTrue(draft.has("unrelated"));assertEquals("a",result.getString("account"))
    }
    @Test fun deletedAccountsInvalidPoolsAndDuplicateSelectionsCannotBeSaved() {
        listOf("""{"account":"gone","pools":["role"]}""","""{"account":"a","pools":["gone"]}""","""{"account":"a","pools":["role","role"]}""").forEach { raw->
            try {WidgetConfiguration.validated(schema(),JSONObject(raw));fail("Expected invalid selection") }catch(expected:IllegalArgumentException){}
        }
    }
    @Test fun instancesRoundTripWithoutCollapsingIdenticalPresetsOrOwners() {
        val values=JSONArray("""[{"pluginId":"test.one","id":"a","presetId":"p","configuration":{"account":"one"}},{"pluginId":"test.one","id":"b","presetId":"p","configuration":{"account":"two"}},{"pluginId":"test.two","id":"a","presetId":"p","configuration":{}}]""")
        val parsed=HomeWidgetInstances.parse(values)
        assertEquals(3,parsed.size);assertEquals("two",parsed[1].configuration.getString("account"))
        assertEquals(values.length(),HomeWidgetInstances.parse(HomeWidgetInstances.encode(parsed)).size)
        values.put(values.getJSONObject(0))
        try {HomeWidgetInstances.parse(values);fail("Duplicate instance must be rejected")}catch(expected:org.json.JSONException){}
    }
}
