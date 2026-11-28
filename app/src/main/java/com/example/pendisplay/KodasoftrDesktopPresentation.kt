package com.example.pendisplay

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.hub.InstalledAppEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kodasoftr Desktop Environment projected exclusively onto the Pen Display VirtualDisplay
 * via android.app.Presentation, leaving the phone screen completely free.
 */
class KodasoftrDesktopPresentation(
    outerContext: Context,
    display: Display,
    private val resolutionLabel: String,
    private val boundAppsProvider: () -> List<InstalledAppEntry>,
    private val onLaunchApp: (InstalledAppEntry) -> Unit
) : Presentation(outerContext, display) {

    private var desktopCanvasView: KodasoftrDesktopCanvasView? = null
    private var appMenuOverlay: LinearLayout? = null
    private var clockView: TextView? = null
    private var statusBadgeView: TextView? = null
    private var menuVisible = false

    private val clockHandler = Handler(Looper.getMainLooper())
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val clockRunnable = object : Runnable {
        override fun run() {
            clockView?.text = timeFormatter.format(Date())
            clockHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#080A14"))
        }

        val canvasView = KodasoftrDesktopCanvasView(context, resolutionLabel, display.displayId)
        desktopCanvasView = canvasView
        root.addView(
            canvasView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Expandable Kodasoftr Start App Menu (above taskbar)
        val menu = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE14182B"))
            setPadding(24, 20, 24, 20)
            visibility = View.GONE
        }
        appMenuOverlay = menu
        val menuParams = FrameLayout.LayoutParams(420, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = 20
            bottomMargin = 78
        }
        root.addView(menu, menuParams)

        // Bottom Kodasoftr Taskbar
        val taskbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#E6101322"))
            setPadding(18, 8, 18, 8)
        }

        val startBtn = Button(context).apply {
            text = "Kodasoftr"
            setTextColor(Color.parseColor("#00E5FF"))
            setBackgroundColor(Color.parseColor("#24294A"))
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { toggleAppMenu() }
        }
        taskbar.addView(startBtn)

        val spacer = View(context)
        taskbar.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))

        val statusBadge = TextView(context).apply {
            text = "Display #${display.displayId} · $resolutionLabel"
            setTextColor(Color.parseColor("#00E676"))
            textSize = 12f
            setPadding(16, 4, 16, 4)
        }
        statusBadgeView = statusBadge
        taskbar.addView(statusBadge)

        val clock = TextView(context).apply {
            text = timeFormatter.format(Date())
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setPadding(16, 4, 8, 4)
        }
        clockView = clock
        taskbar.addView(clock)

        val taskbarParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            64
        ).apply {
            gravity = Gravity.BOTTOM
        }
        root.addView(taskbar, taskbarParams)

        setContentView(root)
        rebuildAppMenu()
    }

    override fun onStart() {
        super.onStart()
        clockHandler.removeCallbacks(clockRunnable)
        clockHandler.post(clockRunnable)
    }

    fun updateStylusCursor(normX: Float, normY: Float, pressure: Float, isDown: Boolean) {
        desktopCanvasView?.updateStylus(normX, normY, pressure, isDown)
    }

    fun refreshAppMenu() {
        rebuildAppMenu()
    }

    private fun toggleAppMenu() {
        menuVisible = !menuVisible
        if (menuVisible) rebuildAppMenu()
        appMenuOverlay?.visibility = if (menuVisible) View.VISIBLE else View.GONE
    }

    private fun rebuildAppMenu() {
        val menu = appMenuOverlay ?: return
        menu.removeAllViews()

        val header = TextView(context).apply {
            text = "KODASOFTR · APPS DA MESA"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 12)
        }
        menu.addView(header)

        val apps = boundAppsProvider().take(8)
        if (apps.isEmpty()) {
            val empty = TextView(context).apply {
                text = "Vincule apps no Koda App Router para abrir em tela cheia aqui."
                setTextColor(Color.parseColor("#A0A6C4"))
                textSize = 12f
            }
            menu.addView(empty)
        } else {
            apps.forEach { app ->
                val itemBtn = Button(context).apply {
                    text = app.label
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#1E223D"))
                    setOnClickListener {
                        menuVisible = false
                        menu.visibility = View.GONE
                        onLaunchApp(app)
                    }
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 8
                }
                menu.addView(itemBtn, lp)
            }
        }
    }

    override fun onStop() {
        clockHandler.removeCallbacks(clockRunnable)
        super.onStop()
    }

    private class KodasoftrDesktopCanvasView(
        context: Context,
        private val resolutionLabel: String,
        private val displayId: Int
    ) : View(context) {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 19, 20, 36)
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(26, 0, 229, 255)
            strokeWidth = 1.5f
        }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 243, 244, 251)
            textSize = 48f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 0, 229, 255)
            textSize = 20f
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }
        private val cursorRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E676")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val cursorDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            style = Paint.Style.FILL
        }

        private val badgeRect = RectF()
        private var stylusNormX = 0.5f
        private var stylusNormY = 0.5f
        private var stylusPressure = 0f
        private var stylusDown = false

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val fw = w.toFloat().coerceAtLeast(1f)
            val fh = h.toFloat().coerceAtLeast(1f)
            bgPaint.shader = LinearGradient(
                0f, 0f, fw, fh,
                Color.parseColor("#090B16"),
                Color.parseColor("#171433"),
                Shader.TileMode.CLAMP
            )
        }

        fun updateStylus(nx: Float, ny: Float, pressure: Float, isDown: Boolean) {
            stylusNormX = nx.coerceIn(0f, 1f)
            stylusNormY = ny.coerceIn(0f, 1f)
            stylusPressure = pressure.coerceIn(0f, 1f)
            stylusDown = isDown
            postInvalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (width > 0 && height > 0) {
                stylusNormX = (event.x / width.toFloat()).coerceIn(0f, 1f)
                stylusNormY = (event.y / height.toFloat()).coerceIn(0f, 1f)
                stylusPressure = event.pressure.coerceIn(0f, 1f)
                stylusDown = event.actionMasked != MotionEvent.ACTION_UP &&
                    event.actionMasked != MotionEvent.ACTION_CANCEL
                invalidate()
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat().coerceAtLeast(1f)
            val h = height.toFloat().coerceAtLeast(1f)

            if (bgPaint.shader == null) {
                bgPaint.shader = LinearGradient(
                    0f, 0f, w, h,
                    Color.parseColor("#090B16"),
                    Color.parseColor("#171433"),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, w, h, bgPaint)

            val step = 80f
            var x = step
            while (x < w) {
                canvas.drawLine(x, 0f, x, h, gridPaint)
                x += step
            }
            var y = step
            while (y < h) {
                canvas.drawLine(0f, y, w, y, gridPaint)
                y += step
            }

            // Center Kodasoftr Studio badge
            badgeRect.set(w * 0.5f - 260f, h * 0.42f - 70f, w * 0.5f + 260f, h * 0.42f + 70f)
            canvas.drawRoundRect(badgeRect, 24f, 24f, cardPaint)

            canvas.drawText("KODASOFTR DESKTOP", w * 0.5f, h * 0.42f - 8f, titlePaint)
            canvas.drawText(
                "Pen Display #$displayId · $resolutionLabel · 8192 WinTab",
                w * 0.5f,
                h * 0.42f + 30f,
                subPaint
            )

            // Live Stylus crosshair
            val cx = stylusNormX * w
            val cy = stylusNormY * h
            val ringRadius = 12f + stylusPressure * 24f
            cursorRingPaint.color = if (stylusDown) Color.parseColor("#00E676") else Color.parseColor("#00E5FF")
            canvas.drawCircle(cx, cy, ringRadius, cursorRingPaint)
            canvas.drawCircle(cx, cy, 4f, cursorDotPaint)
        }
    }
}
