package dev.watchreborn

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.widget.PopupMenu
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import dev.watchreborn.apps.App
import dev.watchreborn.apps.AppRepo
import dev.watchreborn.ui.FaceInfo
import dev.watchreborn.ui.Metrics
import dev.watchreborn.ui.WatchView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var view: WatchView
    private lateinit var repo: AppRepo
    private val loader = Executors.newSingleThreadExecutor()
    private val launcherApps by lazy { getSystemService(LauncherApps::class.java) }
    private val prefs by lazy { getSharedPreferences("watch", Context.MODE_PRIVATE) }
    private var apps: List<App> = emptyList()
    private var battery = -1
    private var charging = false

    private val appsChanged = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = reload()
        override fun onPackageAdded(packageName: String, user: UserHandle) = reload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (level >= 0) battery = level * 100 / scale
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            refreshInfo()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = AppRepo(this)
        view = WatchView(this, ::launch, ::appOptions, ::faceOptions, ::expandNotifications)
        view.accent = prefs.getInt(KEY_ACCENT, ACCENTS[0].second)
        view.preferList = prefs.getBoolean(KEY_LIST, false)
        view.preferListChanged = { prefs.edit().putBoolean(KEY_LIST, it).apply() }
        view.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.topInset = bars.top.toFloat()
            view.bottomInset = bars.bottom.toFloat()
            v.invalidate()
            insets
        }
        setContentView(view)
        window.setDecorFitsSystemWindows(false)
        // targetSdk 36 uses predictive back, which never calls onBackPressed.
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { view.back() }
        launcherApps.registerCallback(appsChanged)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        reload()
    }

    override fun onResume() {
        super.onResume()
        refreshInfo()
        // Back from an app, the apps it was opened from are reordered by use.
        if (apps.isNotEmpty()) view.setApps(apps, repo.byUse(apps))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The Home key is the crown: from the face to the apps, from anywhere else to the face.
        if (Intent.ACTION_MAIN == intent.action && hasWindowFocus()) view.home()
    }

    /**
     * On some phones (Android 16 on OnePlus) the Back key never reaches the predictive-back
     * callback and would finish the activity, so it's handled here too. Back on the face does
     * nothing: a home screen has nowhere to go back to.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) { view.back(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        launcherApps.unregisterCallback(appsChanged)
        unregisterReceiver(batteryReceiver)
        loader.shutdownNow()
        super.onDestroy()
    }

    private fun reload() {
        // Sized for the cluster's centre icon, capped: ~190 apps at 288 px is about 60 MB.
        val unitPx = minOf(resources.displayMetrics.widthPixels.toFloat(), Metrics.WATCH_WIDTH_DP * resources.displayMetrics.density)
        val iconPx = (unitPx * Metrics.FOCUS_CENTER_D).toInt().coerceIn(96, 288)
        loader.execute {
            val loaded = runCatching { repo.load(iconPx) }
                .onFailure { Log.e(TAG, "Loading apps failed", it) }
                .getOrNull() ?: return@execute
            val byUse = repo.byUse(loaded)
            view.post { apps = loaded; view.setApps(loaded, byUse) }
        }
    }

    private fun refreshInfo() {
        val alarm = getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime
        view.info = FaceInfo(battery, charging, alarm)
        view.refreshCards()
    }

    private fun launch(app: App, from: RectF) {
        try {
            val f = LaunchFrom.of(view, from)
            launcherApps.startMainActivity(app.component, app.user, f?.sourceBounds, f?.options)
            repo.markOpened(app.key)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Can't open ${app.label}", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Can't open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun appOptions(app: App) {
        val menu = PopupMenu(this, view, Gravity.CENTER)
        menu.menu.add(0, 1, 0, "App Info")
        // ACTION_DELETE always means the personal profile; work apps are removed from App Info.
        if (!app.system && app.user == Process.myUserHandle()) menu.menu.add(0, 2, 1, "Uninstall")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> runCatching { launcherApps.startAppDetailsActivity(app.component, app.user, null, null) }
                2 -> runCatching { startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.component.packageName, null))) }
            }
            true
        }
        menu.show()
    }

    private fun faceOptions() {
        val menu = PopupMenu(this, view, Gravity.CENTER)
        ACCENTS.forEachIndexed { i, (name, _) -> menu.menu.add(1, 100 + i, i, "Colour: $name") }
        menu.menu.add(2, 2, 50, "Choose Home App")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                2 -> chooseHome()
                in 100 until 100 + ACCENTS.size -> {
                    val c = ACCENTS[item.itemId - 100].second
                    prefs.edit().putInt(KEY_ACCENT, c).apply()
                    view.accent = c
                }
            }
            true
        }
        menu.show()
    }

    private fun chooseHome() {
        val roles = getSystemService(RoleManager::class.java)
        if (roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            @Suppress("DEPRECATION")
            startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), 1)
        } else {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) }
        }
    }

    /** Swipe down on the face: Android's notifications, where the watch shows its own. */
    @SuppressLint("WrongConstant")
    private fun expandNotifications() {
        runCatching {
            val sb = getSystemService("statusbar")
            sb.javaClass.getMethod("expandNotificationsPanel").invoke(sb)
        }.onFailure { Log.w(TAG, "Can't open notifications", it) }
    }

    private companion object {
        const val TAG = "WatchReborn"
        const val KEY_ACCENT = "accent"
        const val KEY_LIST = "list"
        val ACCENTS = listOf(
            "Green" to Color.rgb(166, 227, 110),
            "Orange" to Color.rgb(255, 159, 10),
            "Blue" to Color.rgb(100, 210, 255),
            "Pink" to Color.rgb(255, 100, 160),
            "White" to Color.rgb(235, 235, 235),
        )
    }
}
