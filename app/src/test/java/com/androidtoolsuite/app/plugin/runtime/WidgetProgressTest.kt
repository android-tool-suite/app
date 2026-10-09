package com.androidtoolsuite.app.plugin.runtime

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WidgetProgressTest {
    @Test fun trendAndRowsPreserveRealDataAndDiscardUnrenderedPayloads() {
        val result=normalizedWidgetSnapshot(JSONObject("""{"value":"15.8000","secret":"not-rendered","points":[{"timestamp":1,"value":15.9},{"timestamp":2,"value":15.8}],"rows":[{"label":"角色池","value":"48/90 抽","extra":"not-rendered"}]}"""))
        assertFalse(result.has("secret"));assertFalse(result.getJSONArray("rows").getJSONObject(0).has("extra"))
        assertEquals(15.8,result.getJSONArray("points").getJSONObject(1).getDouble("value"),0.0)
        assertEquals("15.8000",result.getString("value"))
    }
    @Test fun unsortedOrDuplicateTrendNodesCannotReachTheCache() {
        for(points in listOf("[{\"timestamp\":2,\"value\":1},{\"timestamp\":1,\"value\":2}]","[{\"timestamp\":1,\"value\":1},{\"timestamp\":1,\"value\":2}]")) {
            try { normalizedWidgetSnapshot(JSONObject().put("points",org.json.JSONArray(points)));fail("Invalid chronology") }catch(expected:IllegalArgumentException){}
        }
    }
    @Test fun clampsOverflowAndAcceptsRealZero() {
        assertEquals(90.0 to 90.0, normalizedWidgetProgress(JSONObject().put("progress", 100).put("max", 90)))
        assertEquals(0.0 to 80.0, normalizedWidgetProgress(JSONObject().put("progress", 0).put("max", 80)))
    }

    @Test fun rejectsUnknownNegativeAndNonFiniteValues() {
        assertNull(normalizedWidgetProgress(null))
        assertNull(normalizedWidgetProgress(JSONObject().put("max", 90)))
        for (raw in listOf("NaN", "Infinity", "-1", "invalid")) {
            assertNull(normalizedWidgetProgress(JSONObject().put("progress", raw).put("max", 90)))
        }
        for (raw in listOf("NaN", "Infinity", "0", "-1")) {
            assertNull(normalizedWidgetProgress(JSONObject().put("progress", 2).put("max", raw)))
        }
    }
}
