package com.example.hub

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.example.koda.engine.KodaEngineClient
import com.example.usb.UsbPeripheralKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class HubSlotId(
    val label: String,
    val kind: UsbPeripheralKind,
    val virtualDeviceId: Int
) {
    MOUSE_1("Mouse 1", UsbPeripheralKind.MOUSE, 101),
    MOUSE_2("Mouse 2", UsbPeripheralKind.MOUSE, 102),
    KEYBOARD_1("Teclado 1", UsbPeripheralKind.KEYBOARD, 103),
    KEYBOARD_2("Teclado 2", UsbPeripheralKind.KEYBOARD, 104),
    TABLET_1("Mesa 1", UsbPeripheralKind.TABLET, 105),
    TABLET_2("Mesa 2", UsbPeripheralKind.TABLET, 106);

    fun cursorChannel(): Int = if (this == MOUSE_2 || this == TABLET_2) 2 else 1

    fun pairedCursorSlots(): Set<HubSlotId> = when (cursorChannel()) {
        2 -> setOf(MOUSE_2, TABLET_2)
        else -> setOf(MOUSE_1, TABLET_1)
    }
}

enum class RoutingMode {
    ROUTED,
    COOP
}

data class InstalledAppEntry(
    val packageName: String,
    val label: String
)

data class HubSlotAssignment(
    val slotId: HubSlotId,
    val physicalDeviceKey: String? = null,
    val physicalDeviceName: String = "Nenhum atribuído",
    val secondaryDeviceKey: String? = null,
    val secondaryDeviceName: String? = null,
    val targetAppPackage: String? = null,
    val targetAppLabel: String = "Global (Sistema)",
    val routingMode: RoutingMode = RoutingMode.COOP,
    val customCursor: DecodedCursor? = null
) {
    fun deviceSummaryLabel(): String {
        return when {
            physicalDeviceKey != null && secondaryDeviceKey != null && !secondaryDeviceName.isNullOrBlank() ->
                "$physicalDeviceName + $secondaryDeviceName"
            physicalDeviceKey != null -> physicalDeviceName
            secondaryDeviceKey != null && !secondaryDeviceName.isNullOrBlank() -> secondaryDeviceName
            else -> "Nenhum atribuído"
        }
    }
}

data class CoopBannerEvent(
    val message: String,
    val closedAppLabel: String,
    val timestampMs: Long
)

/**
 * Manages the 6-slot Multi-Device Hub (2 Mice + 2 Keyboards + 2 Tablets) with distinct virtual
 * deviceIds, combined Mouse+Tablet simultaneous slot attribution, per-window routing, global
 * .cur/.ani/.png cursor overlay sync, and automatic 3-tier Coop Mode transition.
 */
class HubManager(
    private val context: Context,
    private val engineClient: KodaEngineClient,
    private val cursorOverlayManager: CursorOverlayManager? = null
) {
    private val _slots = MutableStateFlow(
        HubSlotId.entries.map { HubSlotAssignment(slotId = it) }
    )
    val slots: StateFlow<List<HubSlotAssignment>> = _slots.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppEntry>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppEntry>> = _installedApps.asStateFlow()

    private val _coopEvent = MutableStateFlow<CoopBannerEvent?>(null)
    val coopEvent: StateFlow<CoopBannerEvent?> = _coopEvent.asStateFlow()

    private val _simulatedClosedPackages = MutableStateFlow<Set<String>>(emptySet())
    val simulatedClosedPackages: StateFlow<Set<String>> = _simulatedClosedPackages.asStateFlow()

    private var monitorJob: Job? = null

    fun loadLaunchableApps() {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val apps = resolved
            .map { info ->
                InstalledAppEntry(
                    packageName = info.activityInfo.packageName,
                    label = info.loadLabel(pm).toString()
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
        _installedApps.value = apps
    }

    fun assignPhysicalDevice(slotId: HubSlotId, deviceKey: String?, deviceName: String) {
        _slots.update { list ->
            list.map { slot ->
                if (slot.slotId == slotId) {
                    slot.copy(
                        physicalDeviceKey = deviceKey,
                        physicalDeviceName = deviceName,
                        secondaryDeviceKey = if (deviceKey == null) null else slot.secondaryDeviceKey,
                        secondaryDeviceName = if (deviceKey == null) null else slot.secondaryDeviceName
                    )
                } else slot
            }
        }
    }

    fun assignCombinedDevices(
        slotId: HubSlotId,
        primaryKey: String?,
        primaryName: String,
        secondaryKey: String?,
        secondaryName: String?
    ) {
        _slots.update { list ->
            list.map { slot ->
                if (slot.slotId == slotId) {
                    slot.copy(
                        physicalDeviceKey = primaryKey,
                        physicalDeviceName = primaryName,
                        secondaryDeviceKey = secondaryKey,
                        secondaryDeviceName = secondaryName
                    )
                } else slot
            }
        }
    }

    fun assignTargetApp(slotId: HubSlotId, app: InstalledAppEntry?) {
        if (app != null) {
            _simulatedClosedPackages.update { it - app.packageName }
        }
        _slots.update { list ->
            list.map { slot ->
                if (slot.slotId == slotId) {
                    if (app == null) {
                        slot.copy(
                            targetAppPackage = null,
                            targetAppLabel = "Global (Sistema)",
                            routingMode = RoutingMode.COOP
                        )
                    } else {
                        slot.copy(
                            targetAppPackage = app.packageName,
                            targetAppLabel = app.label,
                            routingMode = RoutingMode.ROUTED
                        )
                    }
                } else slot
            }
        }
    }

    fun assignCustomCursor(slotId: HubSlotId, cursor: DecodedCursor?, rawBytes: ByteArray? = null) {
        val paired = slotId.pairedCursorSlots()
        val channel = slotId.cursorChannel()
        _slots.update { list ->
            list.map { slot ->
                if (slot.slotId in paired) slot.copy(customCursor = cursor) else slot
            }
        }
        cursorOverlayManager?.persistCursorForChannel(channel, cursor, rawBytes)
        cursorOverlayManager?.setChannelCursor(channel, cursor)
    }

    fun updateSlotCursorHotspot(slotId: HubSlotId, hotspotX: Float, hotspotY: Float) {
        val paired = slotId.pairedCursorSlots()
        val channel = slotId.cursorChannel()
        var updatedCursor: DecodedCursor? = null
        _slots.update { list ->
            list.map { slot ->
                val cur = slot.customCursor
                if (slot.slotId in paired && cur != null) {
                    val next = cur.withCustomHotspot(hotspotX, hotspotY)
                    updatedCursor = next
                    slot.copy(customCursor = next)
                } else slot
            }
        }
        updatedCursor?.let { next ->
            cursorOverlayManager?.updatePersistedHotspot(channel, next.hotspotX, next.hotspotY)
            cursorOverlayManager?.setChannelCursor(channel, next)
        }
    }

    fun restorePersistedCursors() {
        val mgr = cursorOverlayManager ?: return
        val (ch1, ch2) = mgr.restorePersistedCursors()
        if (ch1 != null || ch2 != null) {
            _slots.update { list ->
                list.map { slot ->
                    when (slot.slotId) {
                        HubSlotId.MOUSE_1, HubSlotId.TABLET_1 -> slot.copy(customCursor = ch1)
                        HubSlotId.MOUSE_2, HubSlotId.TABLET_2 -> slot.copy(customCursor = ch2)
                        else -> slot
                    }
                }
            }
        }
    }

    fun resolveChannelForDeviceKey(deviceKey: String): Int {
        val match = _slots.value.firstOrNull {
            it.physicalDeviceKey == deviceKey || it.secondaryDeviceKey == deviceKey
        }
        return match?.slotId?.cursorChannel() ?: 1
    }

    fun resolveTabletSlotForDeviceKey(deviceKey: String): com.example.driver.TabletSlotId? {
        val match = _slots.value.firstOrNull {
            (it.slotId == HubSlotId.TABLET_1 || it.slotId == HubSlotId.TABLET_2) &&
                (it.physicalDeviceKey == deviceKey || it.secondaryDeviceKey == deviceKey)
        } ?: return null
        return if (match.slotId == HubSlotId.TABLET_2) {
            com.example.driver.TabletSlotId.MESA_2
        } else {
            com.example.driver.TabletSlotId.MESA_1
        }
    }

    fun getPrimaryActiveCursor(): DecodedCursor? {
        val preferredOrder = listOf(HubSlotId.MOUSE_1, HubSlotId.TABLET_1, HubSlotId.MOUSE_2, HubSlotId.TABLET_2)
        for (id in preferredOrder) {
            val found = _slots.value.firstOrNull { it.slotId == id }?.customCursor
            if (found != null) return found
        }
        return _slots.value.firstNotNullOfOrNull { it.customCursor }
    }

    fun syncActiveCursorToOverlay() {
        val ch1 = _slots.value.firstOrNull { it.slotId == HubSlotId.MOUSE_1 }?.customCursor
            ?: _slots.value.firstOrNull { it.slotId == HubSlotId.TABLET_1 }?.customCursor
        val ch2 = _slots.value.firstOrNull { it.slotId == HubSlotId.MOUSE_2 }?.customCursor
            ?: _slots.value.firstOrNull { it.slotId == HubSlotId.TABLET_2 }?.customCursor
        cursorOverlayManager?.setChannelCursor(1, ch1)
        cursorOverlayManager?.setChannelCursor(2, ch2)
    }

    fun toggleSimulatedAppClosed(packageName: String, appLabel: String) {
        val currentlyClosed = packageName in _simulatedClosedPackages.value
        if (currentlyClosed) {
            _simulatedClosedPackages.update { it - packageName }
            reconcileCoopState(activePackages = setOf(packageName), forceRestorePackages = setOf(packageName))
            _coopEvent.value = null
        } else {
            _simulatedClosedPackages.update { it + packageName }
            transitionPackageToCoop(packageName, appLabel)
        }
    }

    fun dismissCoopBanner() {
        _coopEvent.value = null
    }

    fun startCoopMonitor(scope: CoroutineScope) {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1200L)
                val active = detectForegroundOrRunningPackages()
                if (active != null) {
                    reconcileCoopState(activePackages = active, forceRestorePackages = emptySet())
                }
            }
        }
    }

    fun stopCoopMonitor() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun detectForegroundOrRunningPackages(): Set<String>? {
        val engineActive = engineClient.queryActiveForegroundPackages()
        val a11yPkg = KodaAccessibilityService.currentForegroundPackage.value
        if (engineActive != null) {
            val combined = if (!a11yPkg.isNullOrBlank()) engineActive + a11yPkg else engineActive
            return combined - _simulatedClosedPackages.value
        }

        val active = HashSet<String>()
        if (!a11yPkg.isNullOrBlank()) {
            active.add(a11yPkg)
        }
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usm != null) {
            val end = System.currentTimeMillis()
            val begin = end - 600_000L
            val events = runCatching { usm.queryEvents(begin, end) }.getOrNull()
            if (events != null) {
                val ev = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(ev)
                    if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                        active.add(ev.packageName)
                    } else if (ev.eventType == UsageEvents.Event.ACTIVITY_STOPPED) {
                        active.remove(ev.packageName)
                    }
                }
            }
        }
        return if (active.isNotEmpty()) {
            active - _simulatedClosedPackages.value
        } else {
            null
        }
    }

    fun transitionPackageToCoop(packageName: String, appLabel: String) {
        var affected = false
        _slots.update { list ->
            list.map { slot ->
                if (slot.targetAppPackage == packageName && slot.routingMode != RoutingMode.COOP) {
                    affected = true
                    slot.copy(routingMode = RoutingMode.COOP)
                } else slot
            }
        }
        if (affected) {
            _coopEvent.value = CoopBannerEvent(
                message = "App fechado — dispositivos em modo Coop",
                closedAppLabel = appLabel,
                timestampMs = System.currentTimeMillis()
            )
        }
    }

    private fun reconcileCoopState(activePackages: Set<String>, forceRestorePackages: Set<String>) {
        var closedLabel: String? = null
        val closedSet = _simulatedClosedPackages.value
        _slots.update { list ->
            list.map { slot ->
                val pkg = slot.targetAppPackage ?: return@map slot
                val isClosed = pkg in closedSet ||
                    (pkg !in activePackages && pkg !in forceRestorePackages)

                if (isClosed && slot.routingMode == RoutingMode.ROUTED) {
                    closedLabel = slot.targetAppLabel
                    slot.copy(routingMode = RoutingMode.COOP)
                } else if (!isClosed && slot.routingMode == RoutingMode.COOP) {
                    slot.copy(routingMode = RoutingMode.ROUTED)
                } else {
                    slot
                }
            }
        }
        closedLabel?.let { label ->
            _coopEvent.value = CoopBannerEvent(
                message = "App fechado — dispositivos em modo Coop",
                closedAppLabel = label,
                timestampMs = System.currentTimeMillis()
            )
        }
    }
}
