package com.androidtoolsuite.app.host

import androidx.compose.ui.unit.IntSize
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataOperationFeedbackTest {
    @Test fun errorRetainsTheActionableReasonWithoutPasswordOrPayload() {
        assertEquals("恢复数据失败：数据包密码错误或密码保护区已损坏",
            MainActivity.dataOperationFailureMessage("恢复数据失败", IOException("数据包密码错误或密码保护区已损坏"), "fixture-password".toCharArray()))
        val redacted = MainActivity.dataOperationFailureMessage("恢复数据失败", IOException("invalid fixture-password"), "fixture-password".toCharArray())
        assertFalse(redacted.contains("fixture-password"))
        assertTrue(redacted.contains("invalid"))
        val payload = MainActivity.dataOperationFailureMessage("读取数据包失败", IOException("Invalid JSON at 8: {\"sessionToken\":\"fixture-private-data\"}"), null)
        assertTrue(payload.contains("Invalid JSON at 8"))
        assertFalse(payload.contains("fixture-private-data"))
        assertFalse(payload.contains("sessionToken"))
    }

    @Test fun changingWindowBoundsDoesNotRetainThePortraitWidth() {
        val portrait = dataOperationViewportSize(1080, 2400, 0, 90, 0, 60, 0)
        val wide = dataOperationViewportSize(2520, 1890, 0, 90, 0, 60, 0)
        val restored = dataOperationViewportSize(1080, 2400, 0, 90, 0, 60, 0)
        assertEquals(IntSize(1080, 2250), portrait)
        assertEquals(IntSize(2520, 1740), wide)
        assertEquals(portrait, restored)
    }

    @Test fun keyboardOverlapIsRemovedOnceAndClosingItRestoresTheLatestBounds() {
        val open = dataOperationViewportSize(2520, 1890, 20, 90, 30, 60, 600)
        val closed = dataOperationViewportSize(2520, 1890, 20, 90, 30, 60, 0)
        assertEquals(IntSize(2470, 1200), open)
        assertEquals(IntSize(2470, 1740), closed)
    }
}
