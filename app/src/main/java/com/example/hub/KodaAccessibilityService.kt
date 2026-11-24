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
    private var lastStrokeX = 0f
    private var lastStrokeY = 0f
    private var hasPendingMove = false
    private var pendingMoveX = 0f
    private var pendingMoveY = 0f
    private var pendingUp = false
    private var pendingUpX = 0f
    private var pendingUpY = 0f
    private var activeContinuation: GestureDescription.StrokeDescription? = null

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
            dragOngoing = false
            gestureInFlight = false
            hasPendingMove = false
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
            lastStrokeX = sx
            lastStrokeY = sy
            hasPendingMove = false
            pendingUp = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val path = Path().apply {
                    moveTo(sx, sy)
                    lineTo(sx + 0.25f, sy + 0.25f)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0L, 18L, true)
                activeContinuation = stroke
                dragOngoing = true
                gestureInFlight = true
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
            pendingMoveX = sx
            pendingMoveY = sy
            hasPendingMove = true
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
                    dispatchTapLocked(sx, sy)
                }
            }
        }
    }

    private fun flushPendingLocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val prev = activeContinuation ?: run {
            dragOngoing = false
            pendingUp = false
            hasPendingMove = false
            return
        }
        if (pendingUp) {
            val ux = pendingUpX
            val uy = pendingUpY
            pendingUp = false
            hasPendingMove = false
            dragOngoing = false
            activeContinuation = null
            val path = Path().apply {
                moveTo(lastStrokeX, lastStrokeY)
                lineTo(ux, uy)
            }
            val finalStroke = runCatching { prev.continueStroke(path, 0L, 12L, false) }.getOrNull()
            if (finalStroke != null) {
                gestureInFlight = true
                dispatchGestureLocked(GestureDescription.Builder().addStroke(finalStroke).build())
            } else {
                gestureInFlight = false
            }
            return
        }
        if (hasPendingMove && dragOngoing) {
            val mx = pendingMoveX
            val my = pendingMoveY
            hasPendingMove = false
            val path = Path().apply {
                moveTo(lastStrokeX, lastStrokeY)
                lineTo(mx, my)
            }
            val contStroke = runCatching { prev.continueStroke(path, 0L, 16L, true) }.getOrNull()
            if (contStroke != null) {
                activeContinuation = contStroke
                lastStrokeX = mx
                lastStrokeY = my
                gestureInFlight = true
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
        val stroke = GestureDescription.StrokeDescription(path, 0L, 28L)
        gestureInFlight = true
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
            dispatchGestureLocked(GestureDescription.Builder().addStroke(stroke).build())
        }
    }

    fun performSystemBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun performSystemHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    private fun dispatchGestureLocked(gesture: GestureDescription) {
        val dispatched = runCatching {
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        synchronized(gestureLock) {
                            gestureInFlight = false
                            if (pendingUp || (hasPendingMove && dragOngoing)) {
                                flushPendingLocked()
                            }
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        synchronized(gestureLock) {
                            gestureInFlight = false
                            dragOngoing = false
                            hasPendingMove = false
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
            hasPendingMove = false
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
