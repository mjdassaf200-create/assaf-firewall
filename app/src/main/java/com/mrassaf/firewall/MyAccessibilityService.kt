package com.mrassaf.firewall

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class MyAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            if (!packageName.isNullOrBlank()) {
                Log.d("ParentControl", "Opened App: $packageName")
            }
        }
    }

    override fun onInterrupt() {
        Log.d("ParentControl", "Accessibility Service Interrupted")
    }
}
