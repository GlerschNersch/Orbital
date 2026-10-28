package dev.watchreborn.apps

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Process
import android.os.UserManager
import dev.watchreborn.listedProfiles
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * 3.7.2: pure logic for the cold-start field cache — everything
 * here is free of Android types so the fingerprint and the
 * reconcile decision are unit-testable on the JVM.
 */
object StartupCacheLogic {
    const val SCHEMA = 1

    /** One launcher activity's identity within the installed
     *  package set: who runs it, which activity, and the owning
     *  package's version stamp. A change to any field can change
     *  what the field shows. */
    data class PkgStamp(
        val userSerial: Long,
        val component: String,
        val versionCode: Long,
        val lastUpdateTime: Long,
    )

    /** The persistable metadata of one cached app (bitmaps are
     *  stored alongside, in list order). */
    data class Entry(
        val key: String,
        val label: String,
        val component: String,
        val userSerial: Long,
        val system: Boolean,
        val group: String,
        val blurredScale: Float,
    )

    /** Stable, order-independent fingerprint of the package set
     *  plus everything else that shapes the cached field (icon
     *  size, locale — labels are locale-dependent). */
    fun fingerprint(stamps: List<PkgStamp>, iconPx: Int, localeTag: String): String {
        val sb = StringBuilder()
        sb.append("schema=").append(SCHEMA)
            .append(";px=").append(iconPx)
            .append(";locale=").append(localeTag)
        for (s in stamps.sortedWith(compareBy({ it.userSerial }, { it.component }))) {
            sb.append('\n').append(s.userSerial).append('|').append(s.component)
                .append('|').append(s.versionCode).append('|').append(s.lastUpdateTime)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sb.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** The reconcile decision: the fresh load may be skipped only
     *  when a field is already painted (from cache, or from an
     *  identical earlier load this process) and the live package
     *  set still fingerprints exactly as the cache does. */
    fun canSkipFreshLoad(
        cacheFingerprint: String?,
        probeFingerprint: String?,
        hasField: Boolean,
    ): Boolean =
        hasField && cacheFingerprint != null && probeFingerprint != null &&
            cacheFingerprint == probeFingerprint

    /** One entry as a single line; fields are length-prefixed so
     *  arbitrary labels survive the round trip. */
    fun encodeEntry(e: Entry): String {
        val sb = StringBuilder()
        fun field(v: String) {
            sb.append(v.length).append(':').append(v)
        }
        field(e.key); sb.append(';')
        field(e.label); sb.append(';')
        field(e.component); sb.append(';')
        field(e.userSerial.toString()); sb.append(';')
        field(if (e.system) "1" else "0"); sb.append(';')
        field(e.group); sb.append(';')
        field(e.blurredScale.toString())
        return sb.toString()
    }

    fun decodeEntry(s: String): Entry? {
        var i = 0
        fun field(): String? {
            val colon = s.indexOf(':', i)
            if (colon < 0) return null
            val len = s.substring(i, colon).toIntOrNull() ?: return null
            val start = colon + 1
            if (len < 0 || start + len > s.length) return null
            i = start + len
            return s.substring(start, start + len)
        }
        fun sep(): Boolean =
            if (i < s.length && s[i] == ';') {
                i++; true
            } else {
                false
            }
        val key = field() ?: return null
        if (!sep()) return null
        val label = field() ?: return null
        if (!sep()) return null
        val component = field() ?: return null
        if (!sep()) return null
        val serial = field()?.toLongOrNull() ?: return null
        if (!sep()) return null
        val system = field() ?: return null
        if (!sep()) return null
        val group = field() ?: return null
        if (!sep()) return null
        val scale = field()?.toFloatOrNull() ?: return null
        if (i != s.length) return null
        return Entry(key, label, component, serial, system == "1", group, scale)
    }

    fun encodeEntries(entries: List<Entry>): String =
        entries.joinToString("\n") { encodeEntry(it) }

    fun decodeEntries(s: String): List<Entry>? {
        if (s.isEmpty()) return emptyList()
        val out = ArrayList<Entry>()
        for (line in s.split("\n")) {
            out += decodeEntry(line) ?: return null
        }
        return out
    }
}

/**
 * 3.7.2: persists the last-built field's inputs — app metadata
 * plus the rasterized + blurred icon bitmaps — so a cold start
 * can paint immediately and reconcile afterwards, instead of
 * paying the full enumeration / rasterize / blur price before
 * the first frame (measured dominant on the 3.7.1 profile: blur
 * and icon rasterization were ~78% of AppRepo.load).
 *
 * The file lives in private storage and is fingerprinted by the
 * package set (see [StartupCacheLogic.fingerprint]); any doubt
 * about its contents — bad magic, schema or size mismatch, an
 * undecodable bitmap, a vanished user — invalidates the whole
 * cache and the caller falls back to the normal fresh load.
 * Writes go to a temp file renamed over the target, so a process
 * death mid-write can never leave a torn cache behind.
 */
class StartupCache(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val users = context.getSystemService(UserManager::class.java)

    class CachedField(val fingerprint: String, val apps: List<App>)

    private val file: File get() = File(context.filesDir, "startup_cache.bin")
    private val tmpFile: File get() = File(context.filesDir, "startup_cache.tmp")

    /** Cheap package-set probe: enumerates launcher activities
     *  and package version stamps WITHOUT loading a single icon.
     *  Null on any failure (the caller then just does a full load). */
    fun probeFingerprint(iconPx: Int): String? {
        val stamps = ArrayList<StartupCacheLogic.PkgStamp>()
        val pm = context.packageManager
        val infos = HashMap<String, PackageInfo?>()
        for (user in launcherApps.listedProfiles) {
            val serial = users.getSerialNumberForUser(user)
            for (info in launcherApps.getActivityList(null, user)) {
                val pkg = info.componentName.packageName
                if (pkg == context.packageName) continue
                val pi = infos.getOrPut(pkg) {
                    runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
                }
                stamps += StartupCacheLogic.PkgStamp(
                    userSerial = serial,
                    component = info.componentName.flattenToShortString(),
                    versionCode = pi?.longVersionCode ?: -1L,
                    lastUpdateTime = pi?.lastUpdateTime ?: -1L,
                )
            }
        }
        return StartupCacheLogic.fingerprint(
            stamps, iconPx, Locale.getDefault().toLanguageTag(),
        )
    }

    /** Reads and reconstructs the cached field, or null when no
     *  usable cache exists. Runs on the caller's background thread. */
    fun read(iconPx: Int): CachedField? {
        val f = file
        if (!f.isFile) return null
        DataInputStream(BufferedInputStream(FileInputStream(f))).use { inp ->
            if (inp.readInt() != MAGIC) return null
            if (inp.readInt() != StartupCacheLogic.SCHEMA) return null
            if (inp.readInt() != iconPx) return null
            val fingerprint = readBlock(inp)?.toString(Charsets.UTF_8) ?: return null
            val sidecar = readBlock(inp)?.toString(Charsets.UTF_8) ?: return null
            val entries = StartupCacheLogic.decodeEntries(sidecar) ?: return null
            val opts = BitmapFactory.Options().apply { inScaled = false }
            val apps = ArrayList<App>(entries.size)
            for (e in entries) {
                val iconBytes = readBlock(inp) ?: return null
                val icon = BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size, opts)
                    ?: return null
                if (icon.width != iconPx || icon.height != iconPx) return null
                val blurBytes = readBlock(inp) ?: return null
                val blurred = BitmapFactory.decodeByteArray(blurBytes, 0, blurBytes.size, opts)
                    ?: return null
                val component = ComponentName.unflattenFromString(e.component) ?: return null
                val user = users.getUserForSerialNumber(e.userSerial)
                    ?: Process.myUserHandle()
                val group = runCatching { AppGroup.valueOf(e.group) }
                    .getOrDefault(AppGroup.OTHER)
                apps += App(
                    key = e.key,
                    label = e.label,
                    component = component,
                    user = user,
                    system = e.system,
                    icon = icon,
                    blurred = blurred,
                    blurredScale = e.blurredScale,
                    defaultGroup = group,
                )
            }
            return CachedField(fingerprint, apps)
        }
    }

    /** Persists [apps] (a fresh load's result) under [fingerprint].
     *  Runs on the caller's background thread; PNG encoding the
     *  icons is the slow part and happens entirely off the startup
     *  path, after the fresh field has been pushed. */
    fun write(apps: List<App>, fingerprint: String, iconPx: Int) {
        val entries = apps.map { app ->
            StartupCacheLogic.Entry(
                key = app.key,
                label = app.label,
                component = app.component.flattenToShortString(),
                userSerial = users.getSerialNumberForUser(app.user),
                system = app.system,
                group = app.defaultGroup.name,
                blurredScale = app.blurredScale,
            )
        }
        val tmp = tmpFile
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(StartupCacheLogic.SCHEMA)
            out.writeInt(iconPx)
            writeBlock(out, fingerprint.toByteArray(Charsets.UTF_8))
            writeBlock(out, StartupCacheLogic.encodeEntries(entries).toByteArray(Charsets.UTF_8))
            val baos = ByteArrayOutputStream()
            for (app in apps) {
                baos.reset()
                app.icon.compress(Bitmap.CompressFormat.PNG, 100, baos)
                writeBlock(out, baos.toByteArray())
                baos.reset()
                app.blurred.compress(Bitmap.CompressFormat.PNG, 100, baos)
                writeBlock(out, baos.toByteArray())
            }
        }
        if (!tmp.renameTo(file)) {
            // Rename can fail across mount quirks; fall back to a
            // copy so the cache still lands (a torn target is
            // impossible to read back as valid — magic + strict
            // per-entry checks reject it — so this stays safe).
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun writeBlock(out: DataOutputStream, bytes: ByteArray) {
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readBlock(inp: DataInputStream): ByteArray? {
        val len = inp.readInt()
        if (len < 0 || len > MAX_BLOCK) return null
        val bytes = ByteArray(len)
        inp.readFully(bytes)
        return bytes
    }

    private companion object {
        const val MAGIC = 0x4F524243 // "ORBC"
        const val MAX_BLOCK = 64 * 1024 * 1024
    }
}
