package dev.watchreborn

import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.watchreborn.ui.WatchView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launch smoke test: the launcher comes up, the options sheet opens, and
 * layered Back handling consumes the press to close it again. Catches the
 * "builds fine, crashes on launch" class of regression.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @Test
    fun launchesAndSheetOpensAndBackClosesIt() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity { activity ->
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                val view = content.getChildAt(0) as WatchView
                assertNotNull(view)

                view.openClusterSheet()
                // Back must be consumed to pop the sheet layer (NavLayer.SHEET).
                assertTrue(view.back())

                // Reopen and close again: no wedged navigation state.
                view.openClusterSheet()
                assertTrue(view.back())
            }
        }
    }
}
