package dev.watchreborn

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Minimal AccessibilityService that watches no events and reads no window content, used solely
 * so the Control Center Lock Screen button can invoke GLOBAL_ACTION_LOCK_SCREEN.
 */
class TurnOffService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        @Volatile var instance: TurnOffService? = null
            private set

        fun perform(action: Int): Boolean = instance?.performGlobalAction(action) == true
    }
}
