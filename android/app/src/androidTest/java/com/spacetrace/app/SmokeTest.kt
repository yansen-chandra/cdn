package com.spacetrace.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

/**
 * End-to-end smoke test: launches [MainActivity] and confirms it renders past the
 * permission gate into the main scaffold, i.e. the app doesn't crash on startup.
 *
 * The MANAGE_EXTERNAL_STORAGE special permission can't be granted through the usual
 * [androidx.test.rule.GrantPermissionRule] (that's for runtime permissions only), so
 * it's granted here the same way CI/automation does it for any "All files access" app:
 * flipping the underlying app-op directly via a shell command, before the Activity
 * under test is ever launched by the Compose test rule.
 */
class SmokeTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun grantAllFilesAccess() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val packageName = instrumentation.targetContext.packageName
            instrumentation.uiAutomation
                .executeShellCommand("appops set --uid $packageName MANAGE_EXTERNAL_STORAGE allow")
                .close()
        }
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesAndShowsHomeScaffold() {
        // "Recent" appears twice once past the permission gate (top bar title + bottom
        // nav label). waitUntil itself throws (failing the test) if this never becomes
        // true within the timeout, so that's the whole assertion.
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Recent").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
