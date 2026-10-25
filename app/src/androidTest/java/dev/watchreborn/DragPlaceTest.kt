package dev.watchreborn

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.watchreborn.ui.WatchView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end drag-to-place: expand to the full grid, long-press the centered
 * bubble to pick it up, drag it onto a neighbouring slot, drop, and verify a
 * new pin landed in the repo prefs. Pins are snapshotted first and the device
 * is restored afterwards, pass or fail.
 */
@RunWith(AndroidJUnit4::class)
class DragPlaceTest {

    private fun pointer(action: Int, x: Float, y: Float, downTime: Long) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        inst.sendPointerSync(ev)
        ev.recycle()
        inst.waitForIdleSync()
    }

    private fun pins(context: Context): Map<String, Int> =
        context.getSharedPreferences("apps", Context.MODE_PRIVATE).all
            .filterKeys { it.startsWith("pin:") }
            .mapValues { it.value as Int }

    @Test
    fun longPressDragPlacesCenterBubble() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = pins(context)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var view: WatchView? = null
            scenario.onActivity { a ->
                val content = a.findViewById<ViewGroup>(android.R.id.content)
                view = content.getChildAt(0) as WatchView
            }
            val v = view!!
            val loc = IntArray(2)
            scenario.onActivity { v.getLocationOnScreen(loc) }
            val cx = loc[0] + v.width / 2f
            val cy = loc[1] + v.height / 2f

            scenario.onActivity { v.openApps() }
            Thread.sleep(800)

            // Expand focus -> full grid with a pan drag, then let it settle so
            // an item is centered again.
            repeat(2) {
                val t = SystemClock.uptimeMillis()
                pointer(MotionEvent.ACTION_DOWN, cx, cy + v.height * 0.18f, t)
                pointer(MotionEvent.ACTION_MOVE, cx, cy - v.height * 0.05f, t)
                pointer(MotionEvent.ACTION_UP, cx, cy - v.height * 0.05f, t)
                Thread.sleep(1000)
            }

            // Long-press the centered bubble to pick it up...
            val t2 = SystemClock.uptimeMillis()
            pointer(MotionEvent.ACTION_DOWN, cx, cy, t2)
            Thread.sleep(900) // long-press timeout is 500ms: the pickup happens here
            // ...drag right onto the neighbouring slot...
            pointer(MotionEvent.ACTION_MOVE, cx + v.width * 0.13f, cy, t2)
            pointer(MotionEvent.ACTION_MOVE, cx + v.width * 0.24f, cy, t2)
            Thread.sleep(150)
            // ...and drop.
            pointer(MotionEvent.ACTION_UP, cx + v.width * 0.24f, cy, t2)
            Thread.sleep(600)
        }

        val after = pins(context)
        // Restore the device exactly as found before asserting.
        val editor = context.getSharedPreferences("apps", Context.MODE_PRIVATE).edit()
        (after.keys - before.keys).forEach { editor.remove(it) }
        before.forEach { (k, value) -> editor.putInt(k, value) }
        editor.apply()

        assertEquals("expected exactly one new pin after drag-to-place, before=$before after=$after", before.size + 1, after.size)
    }
}
