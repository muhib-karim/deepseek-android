package app.dsandroid.core.screen

import org.junit.Assert.*
import org.junit.Test

class ScreenCapturePolicyTest {
    @Test fun `projection is limited to versions that do not require foreground service permission`() {
        assertEquals(CaptureScope.PROJECTION_WHILE_VISIBLE, ScreenCapturePolicy.scope(26))
        assertEquals(CaptureScope.PROJECTION_WHILE_VISIBLE, ScreenCapturePolicy.scope(28))
        assertEquals(CaptureScope.OWN_WINDOW, ScreenCapturePolicy.scope(29))
        assertEquals(CaptureScope.OWN_WINDOW, ScreenCapturePolicy.scope(35))
    }
    @Test fun `large screens retain aspect ratio within pixel budget`() {
        assertEquals(CaptureSize(720, 1280), ScreenCapturePolicy.size(2160, 3840))
        assertEquals(CaptureSize(1280, 720), ScreenCapturePolicy.size(3840, 2160))
        assertEquals(CaptureSize(320, 480), ScreenCapturePolicy.size(320, 480))
    }
    @Test fun `invalid sizes fail before bitmap allocation`() {
        assertThrows(IllegalArgumentException::class.java) { ScreenCapturePolicy.size(0, 480) }
        assertThrows(IllegalArgumentException::class.java) { ScreenCapturePolicy.size(320, -1) }
    }
}
