package com.mrassaf.firewall

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class MyAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Events are not collected or transmitted.
    }

    override fun onInterrupt() {
        // No active gesture to interrupt.
    }
}
