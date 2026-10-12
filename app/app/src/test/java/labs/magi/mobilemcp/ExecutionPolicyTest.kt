package labs.magi.mobilemcp

import org.junit.Assert.*
import org.junit.Test

class ExecutionPolicyTest {
    @Test fun backgroundModeRejectsScreenAndPendingIntentEscapeRoutes() {
        for (action in listOf("apps.open", "notifications.open", "notifications.act", "device.batch", "screen.snapshot", "screen.screenshot", "interact.tap", "system.startIntent", "system.wake", "future.unknown")) {
            assertFalse(action, ExecutionPolicy.allowed(action, true))
        }
    }
    @Test fun screenModePreservesExistingActions() {
        assertTrue(ExecutionPolicy.allowed("apps.open", false))
        assertTrue(ExecutionPolicy.allowed("device.batch", false))
    }
    @Test fun independentReadQueueContainsNoScreenReadsOrMutations() {
        assertEquals(setOf("shell.status", "device.status", "device.executionPolicy", "apps.list", "notifications.list"), ExecutionPolicy.readActions)
        assertFalse(ExecutionPolicy.readActions.contains("shell.run"))
        assertTrue(ExecutionPolicy.allowed("shell.run", true))
        assertTrue(ExecutionPolicy.backgroundActions.containsAll(ExecutionPolicy.readActions))
    }
}
