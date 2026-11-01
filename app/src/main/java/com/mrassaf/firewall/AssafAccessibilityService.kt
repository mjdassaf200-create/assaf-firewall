package com.mrassaf.firewall

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility entry point for ASSAF's user-enabled tap assistance.
 * Gesture behavior is implemented separately; this class currently only
 * provides the registered service lifecycle.
 */
class AssafAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No screen content is collected or transmitted.
    }

    override fun onInterrupt() {
        // Nothing to interrupt until a user-started gesture is active.
    }
}
