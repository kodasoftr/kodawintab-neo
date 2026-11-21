package com.example.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TargetDisplayInfo(
    val displayId: Int,
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val rotationDeg: Int,
    val modeLabel: String,
    val xdpi: Float = 420f,
    val ydpi: Float = 420f,
    val densityDpi: Int = 420
)

/**
 * Tracks all Android displays via DisplayManager (Internal screen, Samsung DeX, Android PC/Desktop
 * mode, External Monitor / Mirror) and watches live screen rotation + width/height/DPI changes
 * permanently at application scope.
 */
class DisplayTargetManager(private val context: Context) {

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _displays = MutableStateFlow<List<TargetDisplayInfo>>(emptyList())
    val displays: StateFlow<List<TargetDisplayInfo>> = _displays.asStateFlow()

    private val _currentRotationDeg = MutableStateFlow(0)
    val currentRotationDeg: StateFlow<Int> = _currentRotationDeg.asStateFlow()

    private var onDisplayChangedCallback: ((TargetDisplayInfo) -> Unit)? = null
    private var orientationListener: OrientationEventListener? = null
    private var isListening = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refreshDisplays()
        override fun onDisplayRemoved(displayId: Int) = refreshDisplays()
        override fun onDisplayChanged(displayId: Int) = refreshDisplays()
    }

    fun startListening(onDisplayChanged: (TargetDisplayInfo) -> Unit) {
        onDisplayChangedCallback = onDisplayChanged
        if (!isListening) {
            isListening = true
            runCatching { displayManager?.registerDisplayListener(displayListener, mainHandler) }
            runCatching {
                val listener = object : OrientationEventListener(context) {
                    private var lastPostedAt = 0L
                    override fun onOrientationChanged(orientation: Int) {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastPostedAt >= 80L) {
                            lastPostedAt = now
                            refreshDisplays()
                        }
                    }
                }
                if (listener.canDetectOrientation()) {
                    listener.enable()
                }
                orientationListener = listener
            }
        }
        refreshDisplays()
    }

    fun updateCallback(onDisplayChanged: (TargetDisplayInfo) -> Unit) {
        onDisplayChangedCallback = onDisplayChanged
    }

    fun stopListening() {
        isListening = false
        runCatching { displayManager?.unregisterDisplayListener(displayListener) }
        runCatching { orientationListener?.disable() }
        orientationListener = null
        onDisplayChangedCallback = null
    }

    @Suppress("DEPRECATION")
    fun refreshDisplays() {
        val prevDefault = _displays.value.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY }
        val mgr = displayManager
        val list = if (mgr != null) {
            runCatching {
                mgr.displays.map { d ->
                    val metrics = DisplayMetrics()
                    d.getRealMetrics(metrics)
                    val rotDeg = when (d.rotation) {
                        Surface.ROTATION_90 -> 90
                        Surface.ROTATION_180 -> 180
                        Surface.ROTATION_270 -> 270
                        else -> 0
                    }
                    val modeLabel = when {
                        d.displayId == Display.DEFAULT_DISPLAY -> "Tela Interna"
                        d.name.contains("DeX", ignoreCase = true) -> "Samsung DeX"
                        (d.flags and Display.FLAG_PRESENTATION) != 0 -> "Modo PC / Monitor Externo"
                        else -> "Display Secundário"
                    }
                    val safeDpi = metrics.densityDpi.coerceIn(120, 960)
                    val xdpi = metrics.xdpi.takeIf { it in 72f..1200f } ?: safeDpi.toFloat()
                    val ydpi = metrics.ydpi.takeIf { it in 72f..1200f } ?: safeDpi.toFloat()
                    TargetDisplayInfo(
                        displayId = d.displayId,
                        name = d.name.ifBlank { "Display ${d.displayId}" },
                        widthPx = metrics.widthPixels.coerceAtLeast(320),
                        heightPx = metrics.heightPixels.coerceAtLeast(320),
                        rotationDeg = rotDeg,
                        modeLabel = modeLabel,
                        xdpi = xdpi,
                        ydpi = ydpi,
                        densityDpi = safeDpi
                    )
                }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val finalList = list.ifEmpty {
            listOf(TargetDisplayInfo(0, "Tela Interna", 1080, 1920, 0, "Tela Interna"))
        }
        _displays.value = finalList

        val curDefault = finalList.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY } ?: finalList.first()
        _currentRotationDeg.value = curDefault.rotationDeg

        val changed = prevDefault == null ||
            prevDefault.rotationDeg != curDefault.rotationDeg ||
            prevDefault.widthPx != curDefault.widthPx ||
            prevDefault.heightPx != curDefault.heightPx ||
            prevDefault.densityDpi != curDefault.densityDpi
        if (changed) {
            onDisplayChangedCallback?.invoke(curDefault)
        }
    }

    fun getDisplayOrDefault(displayId: Int): TargetDisplayInfo {
        val list = _displays.value
        return list.firstOrNull { it.displayId == displayId }
            ?: list.firstOrNull()
            ?: TargetDisplayInfo(0, "Tela Interna", 1080, 1920, 0, "Tela Interna")
    }
}
