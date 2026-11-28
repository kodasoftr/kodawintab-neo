package com.example.hub

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/**
 * Standalone Accessibility bridge for Koda Wintab.
 *
 * Allows Koda's custom (.cur / .ani / .png) cursor driven by USB OTG Mouse or Graphics Tablet
 * to perform real system-wide clicks, drags, continuous drawing strokes, scrolls,
 * Back (right-click) and Home (middle-click) across the entire Android OS and third-party apps
 * WITHOUT requiring a PC or ADB.
 */
class KodaAccessibilityService : AccessibilityService() {

    private val gestureLock = Any()
    private var dragOngoing = false
    private var gestureInFlight = false
    private var activeGestureGeneration = 0L
    private var lastStrokeX = 0f
    private var lastStrokeY = 0f
    private val pendingMoveXs = FloatArray(16)
    private val pendingMoveYs = FloatArray(16)
    private var pendingMoveCount = 0
    private var pendingUp = false
    private var pendingUpX = 0f
    private var pendingUpY = 0f
    private var activeContinuation: GestureDescription.StrokeDescription? = null
    private var lastGestureDispatchMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceRef = WeakReference(this)
        _isServiceRunning.value = true
        (applicationContext as? com.example.KodaWintabApp)?.let { app ->
            app.cursorOverlayManager.attachAccessibilityWindowManager(
                getSystemService(WINDOW_SERVICE) as? WindowManager
            )
            app.ensureBackgroundDriverActive()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (!pkg.isNullOrBlank()) {
            _currentForegroundPackage.value = pkg
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        synchronized(gestureLock) {
            activeGestureGeneration++
            dragOngoing = false
            gestureInFlight = false
            pendingMoveCount = 0
            pendingUp = false
            activeContinuation = null
        }
        (applicationContext as? com.example.KodaWintabApp)?.cursorOverlayManager
            ?.attachAccessibilityWindowManager(null)
        serviceRef = null
        _isServiceRunning.value = false
        super.onDestroy()
    }

    fun isForegroundInOwnApp(): Boolean {
        val app = applicationContext as? com.example.KodaWintabApp
        if (app != null) {
            return app.isAppInForeground
        }
        return _currentForegroundPackage.value == packageName
    }

    fun dispatchPointerDown(x: Float, y: Float) {
        val sx = x.coerceAtLeast(1f)
        val sy = y.coerceAtLeast(1f)
        synchronized(gestureLock) {
            val nowMs = android.os.SystemClock.uptimeMillis()
            if (gestureInFlight && nowMs - lastGestureDispatchMs > 650L) {
                gestureInFlight = false
                dragOngoing = false
                activeContinuation = null
            }
            lastStrokeX = sx
            lastStrokeY = sy
            pendingMoveCount = 0
            pendingUp = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val path = Path().apply {
                    moveTo(sx, sy)
                    lineTo(sx + 0.35f, sy + 0.35f)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0L, 14L, true)
                activeContinuation = stroke
                dragOngoing = true
                gestureInFlight = true
                lastGestureDispatchMs = nowMs
                dispatchGestureLocked(GestureDescription.Builder().addStroke(stroke).build())
            } else {
                dragOngoing = true
            }
        }
    }

    fun dispatchPointerMove(x: Float, y: Float) {
        val sx = x.coerceAtLeast(1f)
        val sy = y.coerceAtLeast(1f)
        synchronized(gestureLock) {
            if (!dragOngoing) return
            val nowMs = android.os.SystemClock.uptimeMillis()
            if (gestureInFlight && nowMs - lastGestureDispatchMs > 650L) {
                gestureInFlight = false
            }
            val refX = if (pendingMoveCount > 0) pendingMoveXs[pendingMoveCount - 1] else lastStrokeX
            val refY = if (pendingMoveCount > 0) pendingMoveYs[pendingMoveCount - 1] else lastStrokeY
            if (kotlin.math.hypot(sx - refX, sy - refY) < 0.5f) {
                return
            }
            if (pendingMoveCount < pendingMoveXs.size) {
                pendingMoveXs[pendingMoveCount] = sx
                pendingMoveYs[pendingMoveCount] = sy
                pendingMoveCount++
            } else {
                pendingMoveXs[pendingMoveXs.size - 1] = sx
                pendingMoveYs[pendingMoveYs.size - 1] = sy
            }
            if (!gestureInFlight && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flushPendingLocked()
            }
        }
    }

    fun dispatchPointerUp(x: Float, y: Float) {
        val sx = x.coerceAtLeast(1f)
        val sy = y.coerceAtLeast(1f)
        synchronized(gestureLock) {
            if (!dragOngoing) return
            pendingUp = true
            pendingUpX = sx
            pendingUpY = sy
            if (!gestureInFlight) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activeContinuation != null) {
                    flushPendingLocked()
                } else {
                    dragOngoing = false
                    pendingUp = false
                    pendingMoveCount = 0
                    dispatchTapLocked(sx, sy)
                }
            }
        }
    }

    fun cancelActiveStroke() {
        var releaseX = 0f
        var releaseY = 0f
        var shouldRelease = false
        synchronized(gestureLock) {
            if (!dragOngoing) {
                pendingMoveCount = 0
                pendingUp = false
                return
            }
            releaseX = lastStrokeX.coerceAtLeast(1f)
            releaseY = lastStrokeY.coerceAtLeast(1f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activeContinuation != null && !gestureInFlight) {
                pendingUp = true
                pendingUpX = releaseX
                pendingUpY = releaseY
                flushPendingLocked()
            } else {
                activeGestureGeneration++
                dragOngoing = false
                gestureInFlight = false
                pendingMoveCount = 0
                pendingUp = false
                activeContinuation = null
                shouldRelease = true
            }
        }
        if (shouldRelease && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // No continuation API below O
        }
    }

    private fun buildBatchedPath(
        startX: Float,
        startY: Float,
        endX: Float?,
        endY: Float?
    ): Pair<Path, Pair<Float, Float>> {
        val path = Path().apply { moveTo(startX, startY) }
        var curX = startX
        var curY = startY
        var totalDist = 0f
        for (i in 0 until pendingMoveCount) {
            val nx = pendingMoveXs[i]
            val ny = pendingMoveYs[i]
            totalDist += kotlin.math.hypot(nx - curX, ny - curY)
            path.lineTo(nx, ny)
            curX = nx
            curY = ny
        }
        if (endX != null && endY != null) {
            totalDist += kotlin.math.hypot(endX - curX, endY - curY)
            if (totalDist < 0.35f) {
                curX += 0.35f
                curY += 0.35f
                path.lineTo(curX, curY)
            } else {
                path.lineTo(endX, endY)
                curX = endX
                curY = endY
            }
        } else if (totalDist < 0.35f) {
            curX += 0.35f
            curY += 0.35f
            path.lineTo(curX, curY)
        }
        return path to (curX to curY)
    }

    private fun flushPendingLocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val prev = activeContinuation ?: run {
            dragOngoing = false
            pendingUp = false
            pendingMoveCount = 0
            return
        }
        if (pendingUp) {
            val ux = pendingUpX
            val uy = pendingUpY
            val (path, _) = buildBatchedPath(lastStrokeX, lastStrokeY, ux, uy)
            pendingUp = false
            pendingMoveCount = 0
            dragOngoing = false
            activeContinuation = null
            val finalStroke = runCatching { prev.continueStroke(path, 0L, 10L, false) }.getOrNull()
            if (finalStroke != null) {
                gestureInFlight = true
                lastGestureDispatchMs = android.os.SystemClock.uptimeMillis()
                dispatchGestureLocked(GestureDescription.Builder().addStroke(finalStroke).build())
            } else {
                gestureInFlight = false
            }
            return
        }
        if (pendingMoveCount > 0 && dragOngoing) {
            val durationMs = (pendingMoveCount * 6L).coerceIn(10L, 26L)
            val (path, endPt) = buildBatchedPath(lastStrokeX, lastStrokeY, null, null)
            pendingMoveCount = 0
            val contStroke = runCatching { prev.continueStroke(path, 0L, durationMs, true) }.getOrNull()
            if (contStroke != null) {
                activeContinuation = contStroke
                lastStrokeX = endPt.first
                lastStrokeY = endPt.second
                gestureInFlight = true
                lastGestureDispatchMs = android.os.SystemClock.uptimeMillis()
                dispatchGestureLocked(GestureDescription.Builder().addStroke(contStroke).build())
            } else {
                activeContinuation = null
                dragOngoing = false
                gestureInFlight = false
            }
        }
    }

    fun dispatchTap(x: Float, y: Float) {
        val sx = x.coerceAtLeast(1f)
        val sy = y.coerceAtLeast(1f)
        synchronized(gestureLock) {
            if (gestureInFlight || dragOngoing) return
            dispatchTapLocked(sx, sy)
        }
    }

    private fun dispatchTapLocked(sx: Float, sy: Float) {
        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(sx + 0.5f, sy + 0.5f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 24L)
        gestureInFlight = true
        lastGestureDispatchMs = android.os.SystemClock.uptimeMillis()
        dispatchGestureLocked(GestureDescription.Builder().addStroke(stroke).build())
    }

    fun dispatchScroll(x: Float, y: Float, wheelDelta: Float) {
        if (wheelDelta == 0f) return
        val sx = x.coerceAtLeast(40f)
        val sy = y.coerceAtLeast(120f)
        val dy = (wheelDelta * 180f).coerceIn(-480f, 480f)
        val endY = (sy + dy).coerceAtLeast(20f)
        synchronized(gestureLock) {
            if (gestureInFlight || dragOngoing) return
            val path = Path().apply {
                moveTo(sx, sy)
                lineTo(sx, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 120L)
            gestureInFlight = true
            lastGestureDispatchMs = android.os.SystemClock.uptimeMillis()
            dispatchGestureLocked(GestureDescription.Builder().addStroke(stroke).build())
        }
    }

    fun performSystemBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun performSystemHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    private fun dispatchGestureLocked(gesture: GestureDescription) {
        activeGestureGeneration++
        val gen = activeGestureGeneration
        val dispatched = runCatching {
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        synchronized(gestureLock) {
                            if (gen != activeGestureGeneration) return
                            gestureInFlight = false
                            if (pendingUp || (pendingMoveCount > 0 && dragOngoing)) {
                                flushPendingLocked()
                            }
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        synchronized(gestureLock) {
                            if (gen != activeGestureGeneration) return
                            gestureInFlight = false
                            dragOngoing = false
                            pendingMoveCount = 0
                            pendingUp = false
                            activeContinuation = null
                        }
                    }
                },
                null
            )
        }.getOrDefault(false)

        if (!dispatched) {
            gestureInFlight = false
            dragOngoing = false
            pendingMoveCount = 0
            pendingUp = false
            activeContinuation = null
        }
    }

    companion object {
        private var serviceRef: WeakReference<KodaAccessibilityService>? = null

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _currentForegroundPackage = MutableStateFlow<String?>(null)
        val currentForegroundPackage: StateFlow<String?> = _currentForegroundPackage.asStateFlow()

        fun activeInstance(): KodaAccessibilityService? = serviceRef?.get()

        fun isEnabledInSettings(context: Context): Boolean {
            if (_isServiceRunning.value) return true
            val enabled = runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            }.getOrNull() ?: return false
            val target = ComponentName(context, KodaAccessibilityService::class.java)
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            while (splitter.hasNext()) {
                val componentName = ComponentName.unflattenFromString(splitter.next())
                if (componentName == target) return true
            }
            return false
        }
    }
}
