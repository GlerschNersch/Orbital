package dev.watchreborn

import android.content.ComponentName
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.UserHandle

/**
 * Queries an app's native dynamic and manifest shortcuts when Watch Reborn holds shortcut permission
 * (granted by Android to the active default home app).
 */
object Shortcuts {
    private const val PER_APP = 4

    fun forApp(apps: LauncherApps, component: ComponentName, user: UserHandle): List<ShortcutInfo> {
        if (!runCatching { apps.hasShortcutHostPermission() }.getOrDefault(false)) return emptyList()
        val q = LauncherApps.ShortcutQuery().apply {
            setPackage(component.packageName)
            setActivity(component)
            setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
        }
        return runCatching { apps.getShortcuts(q, user) }.getOrNull().orEmpty()
            .filter { it.isEnabled }
            .sortedWith(compareBy({ !it.isDeclaredInManifest }, { it.rank }))
            .take(PER_APP)
    }
}
