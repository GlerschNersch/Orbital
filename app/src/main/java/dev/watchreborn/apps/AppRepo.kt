package dev.watchreborn.apps

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import dev.watchreborn.listedProfiles
import java.text.Collator

/** One launchable app, its round icon (and a blurred copy for the backdrop behind the cluster). */
class App(
    val key: String,
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val system: Boolean,
    val icon: Bitmap,
    /** Small and soft: drawn [blurredScale] times the icon's size, as it carries a blur margin. */
    val blurred: Bitmap,
    val blurredScale: Float,
)

/**
 * Loads apps from every listed profile (Private Space stays out) and keeps how often and how
 * recently each was opened: watchOS 27's grid puts the apps you use most in the middle.
 */
class AppRepo(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val users = context.getSystemService(UserManager::class.java)
    private val prefs = context.getSharedPreferences("apps", Context.MODE_PRIVATE)

    fun load(iconPx: Int): List<App> {
        val me = Process.myUserHandle()
        val out = ArrayList<App>()
        for (user in launcherApps.listedProfiles) {
            for (info in launcherApps.getActivityList(null, user)) {
                if (info.componentName.packageName == context.packageName) continue
                val serial = users.getSerialNumberForUser(user)
                val key = "${info.componentName.flattenToShortString()}#$serial"
                val icon = roundIcon(info, user.takeIf { it != me }, iconPx)
                val (soft, softScale) = blur(icon)
                out += App(
                    key = key,
                    label = info.label?.toString()?.trim().orEmpty().ifEmpty { info.componentName.packageName },
                    component = info.componentName,
                    user = user,
                    system = info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    icon = icon,
                    blurred = soft,
                    blurredScale = softScale,
                )
            }
        }
        val collator = Collator.getInstance()
        return out.sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    /**
     * Most used first (opens, with recent ones counting more). Apps never opened from here follow:
     * the phone's default Phone, Messages, Camera, browser, email and maps apps first, so a fresh
     * install starts with useful apps in the middle rather than whatever sorts first A-Z.
     */
    fun byUse(apps: List<App>): List<App> {
        val now = System.currentTimeMillis()
        val essentials = essentialPackages()
        fun essentialRank(a: App) = essentials.indexOf(a.component.packageName).let { if (it < 0) Int.MAX_VALUE else it }
        return apps.withIndex().sortedWith(
            compareByDescending<IndexedValue<App>> { score(it.value.key, now) }
                .thenBy { essentialRank(it.value) }
                .thenBy { it.index },
        ).map { it.value }
    }

    /** The default apps for calling, messaging, the camera, the web, email and maps, in that order. */
    private fun essentialPackages(): List<String> {
        val pm = context.packageManager
        val intents = listOf(
            android.content.Intent(android.content.Intent.ACTION_DIAL),
            android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:")),
            android.content.Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com/")),
            android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:")),
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0")),
        )
        return intents.mapNotNull { intent ->
            runCatching { pm.resolveActivity(intent, 0)?.activityInfo?.packageName }.getOrNull()
                // "android" is the system chooser: no default set.
                ?.takeIf { it != "android" }
        }.distinct()
    }

    fun markOpened(key: String) {
        val count = prefs.getInt("n:$key", 0)
        prefs.edit().putInt("n:$key", count + 1).putLong("t:$key", System.currentTimeMillis()).apply()
    }

    private fun score(key: String, now: Long): Float {
        val count = prefs.getInt("n:$key", 0)
        if (count == 0) return 0f
        val days = (now - prefs.getLong("t:$key", 0L)) / 86_400_000f
        // A week-old app counts half as much as one opened today.
        return count / (1f + days / 7f)
    }

    /**
     * The app's icon as a full-bleed circle, like the watch's. Adaptive icons are drawn from their
     * own layers (the circle shows their middle, as the system mask would); legacy icons sit on a
     * dark disc.
     */
    private fun roundIcon(info: LauncherActivityInfo, otherUser: UserHandle?, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.clipPath(Path().apply { addCircle(px / 2f, px / 2f, px / 2f, Path.Direction.CW) })
        val d = info.getIcon(0)
        if (d is AdaptiveIconDrawable) {
            // Each layer is 1.5x the icon (108 dp around a 72 dp view), centred.
            val bleed = px / 4
            for (layer in listOfNotNull(d.background, d.foreground)) {
                layer.setBounds(-bleed, -bleed, px + bleed, px + bleed)
                layer.draw(c)
            }
        } else {
            c.drawColor(Color.rgb(44, 44, 46))
            val inset = (px * 0.14f).toInt()
            d.setBounds(inset, inset, px - inset, px - inset)
            d.draw(c)
        }
        if (otherUser == null) return bmp
        // Work apps keep Android's briefcase badge.
        val badged = context.packageManager.getUserBadgedIcon(BitmapDrawable(context.resources, bmp), otherUser)
        return toBitmap(badged, px).also { if (it !== bmp) bmp.recycle() }
    }

    private fun toBitmap(d: Drawable, px: Int): Bitmap {
        if (d is BitmapDrawable && d.bitmap.width == px) return d.bitmap
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, px, px)
        d.draw(Canvas(out))
        return out
    }

    /**
     * A soft copy for the out-of-focus apps: shrunk to a sixth and box-blurred three times (close
     * to a Gaussian). It stays small (about 2% of the icon's memory) and is stretched when drawn;
     * returns it with how much bigger than the icon to draw it, for its blur margin.
     */
    private fun blur(src: Bitmap): Pair<Bitmap, Float> {
        val sw = (src.width / 6).coerceAtLeast(8)
        val sh = (src.height / 6).coerceAtLeast(8)
        // A transparent margin so the blur can spread past the circle's edge.
        val pad = 4
        val small = Bitmap.createBitmap(sw + 2 * pad, sh + 2 * pad, Bitmap.Config.ARGB_8888)
        Canvas(small).drawBitmap(src, null, android.graphics.Rect(pad, pad, pad + sw, pad + sh), Paint(Paint.FILTER_BITMAP_FLAG))
        val w = small.width
        val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) { boxBlur(px, w, h, 2) }
        small.setPixels(px, 0, w, 0, 0, w, h)
        return small to (sw + 2f * pad) / sw
    }

    /** One horizontal then vertical box blur of premultiplied ARGB [px] (radius [r]). */
    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        val n = 2 * r + 1
        for (pass in 0 until 2) {
            val (src, dst) = if (pass == 0) px to tmp else tmp to px
            val (len, lines) = if (pass == 0) w to h else h to w
            for (line in 0 until lines) {
                for (i in 0 until len) {
                    var a = 0; var rr = 0; var g = 0; var b = 0
                    for (k in -r..r) {
                        val j = (i + k).coerceIn(0, len - 1)
                        val c = if (pass == 0) src[line * w + j] else src[j * w + line]
                        a += c ushr 24; rr += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255
                    }
                    val v = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
                    if (pass == 0) dst[line * w + i] = v else dst[i * w + line] = v
                }
            }
        }
    }
}
