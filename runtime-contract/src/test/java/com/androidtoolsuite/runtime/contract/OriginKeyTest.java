package com.androidtoolsuite.runtime.contract;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class OriginKeyTest {
    @Test
    public void originIsStableAndDoesNotExposePluginId() throws Exception {
        String first = OriginKey.virtualOrigin("sample.hello_web");
        String second = OriginKey.virtualOrigin("sample.hello_web");

        assertEquals(first, second);
        assertTrue(first.matches("https://[0-9a-f]{40}\\.plugins\\.android-tool-suite\\.test"));
        assertNotEquals(first, OriginKey.virtualOrigin("sample.other"));
    }
}
