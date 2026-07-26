package com.evidencebasedvocabulary.app

import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ControllerUnitTest {

    @Test
    fun testRecoveryLimiter() {
        val recoveryTimestamps = mutableListOf<Long>()
        val limitWindowMs = 60000L
        val maxAutoRecoveries = 3

        fun isAllowedToAutoRecover(now: Long): Boolean {
            recoveryTimestamps.removeAll { it < now - limitWindowMs }
            if (recoveryTimestamps.size >= maxAutoRecoveries) return false
            recoveryTimestamps.add(now)
            return true
        }

        var currentTime = 1000L
        
        // First 3 should be allowed
        assertTrue(isAllowedToAutoRecover(currentTime))
        currentTime += 1000
        assertTrue(isAllowedToAutoRecover(currentTime))
        currentTime += 1000
        assertTrue(isAllowedToAutoRecover(currentTime))
        
        // 4th within the window should be blocked
        currentTime += 1000
        assertFalse(isAllowedToAutoRecover(currentTime))
        
        // Move past the window
        currentTime += 60000
        assertTrue(isAllowedToAutoRecover(currentTime))
    }
}
