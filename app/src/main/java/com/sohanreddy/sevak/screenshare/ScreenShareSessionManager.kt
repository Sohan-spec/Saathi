package com.sohanreddy.sevak.screenshare

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-local state holder for active screen-share session metadata.
 */
object ScreenShareSessionManager {
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val latestScreenshotBase64 = AtomicReference<String?>(null)

    fun setActive(active: Boolean) {
        _isActive.value = active
        if (!active) {
            latestScreenshotBase64.set(null)
        }
    }

    fun updateLatestScreenshot(base64Jpeg: String) {
        latestScreenshotBase64.set(base64Jpeg)
    }

    fun latestScreenshotDataUrl(): String? {
        val base64 = latestScreenshotBase64.get() ?: return null
        return "data:image/jpeg;base64,$base64"
    }
}
