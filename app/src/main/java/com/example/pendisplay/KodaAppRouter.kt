package com.example.pendisplay

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.example.koda.engine.KodaEngineClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PenDisplayTaskEntry(
    val packageName: String,
    val label: String,
    val displayId: Int,
    val launchedAtMs: Long
)

/**
 * Koda App Router:
 * Stores the user's Pen Display bound apps, launches them directly onto the Pen Display's
 * isolated displayId via ActivityOptions.setLaunchDisplayId, and syncs task interception rules
 * with KodaShellServer's native TaskStackListener + moveRootTaskToDisplay when elevated.
 */
class KodaAppRouter(
    private val context: Context,
    private val engineClient: KodaEngineClient
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _boundPackages = MutableStateFlow<Set<String>>(
        prefs.getStringSet(KEY_BOUND_PACKAGES, emptySet())?.toSet() ?: emptySet()
    )
    val boundPackages: StateFlow<Set<String>> = _boundPackages.asStateFlow()

    private val _activeTasks = MutableStateFlow<List<PenDisplayTaskEntry>>(emptyList())
    val activeTasks: StateFlow<List<PenDisplayTaskEntry>> = _activeTasks.asStateFlow()

    fun togglePackageBinding(packageName: String, activeDisplayId: Int) {
        val current = _boundPackages.value
        val next = if (packageName in current) current - packageName else current + packageName
        _boundPackages.value = next
        prefs.edit().putStringSet(KEY_BOUND_PACKAGES, next).apply()
        syncWithEngine(activeDisplayId)
    }

    fun setPackageBound(packageName: String, bound: Boolean, activeDisplayId: Int) {
        val current = _boundPackages.value
        val next = if (bound) current + packageName else current - packageName
        _boundPackages.value = next
        prefs.edit().putStringSet(KEY_BOUND_PACKAGES, next).apply()
        syncWithEngine(activeDisplayId)
    }

    fun syncWithEngine(displayId: Int) {
        if (engineClient.state.value.isActive) {
            engineClient.syncRoutedPenDisplayApps(_boundPackages.value, displayId)
        }
    }

    fun launchAppOnPenDisplay(packageName: String, appLabel: String, displayId: Int): Boolean {
        if (displayId <= 0) return false
        val pm: PackageManager = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
        )

        val launched = runCatching {
            val options = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                options.launchDisplayId = displayId
            }
            context.startActivity(launchIntent, options.toBundle())
            true
        }.getOrDefault(false)

        if (launched) {
            if (engineClient.state.value.isActive) {
                engineClient.moveTaskToPenDisplay(packageName, displayId)
            }
            val updated = _activeTasks.value.filterNot { it.packageName == packageName } +
                PenDisplayTaskEntry(
                    packageName = packageName,
                    label = appLabel,
                    displayId = displayId,
                    launchedAtMs = System.currentTimeMillis()
                )
            _activeTasks.value = updated
        }
        return launched
    }

    fun removeTaskEntry(packageName: String) {
        _activeTasks.value = _activeTasks.value.filterNot { it.packageName == packageName }
    }

    fun clearTasks() {
        _activeTasks.value = emptyList()
    }

    companion object {
        private const val PREFS_NAME = "koda_pendisplay_router_prefs"
        private const val KEY_BOUND_PACKAGES = "bound_pen_display_packages"
    }
}
