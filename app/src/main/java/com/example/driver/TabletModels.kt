package com.example.driver

import com.squareup.moshi.JsonClass

enum class ParserType {
    UGEE_S640,
    WACOM,
    HUION,
    XP_PEN,
    GENERIC_HID
}

enum class TabletBrand(val displayName: String) {
    UGEE("Ugee"),
    WACOM("Wacom"),
    HUION("Huion"),
    XP_PEN("XP-Pen"),
    GENERIC("HID Genérico")
}

enum class OutputMode {
    ABSOLUTE,
    RELATIVE
}

enum class DpiNormalizationMode(val label: String) {
    OFF("Nativo (Sem ajuste)"),
    AUTO("DPI Automática (Tela × Mesa)"),
    MANUAL("DPI Manual (X / Y)")
}

data class ManualDpiPreset(
    val id: String,
    val label: String,
    val dpiX: Int,
    val dpiY: Int
)

val DEFAULT_MANUAL_DPI_PRESETS: List<ManualDpiPreset> = listOf(
    ManualDpiPreset("dpi_160", "160 DPI · MDPI Base", 160, 160),
    ManualDpiPreset("dpi_240", "240 DPI · HDPI Compacto", 240, 240),
    ManualDpiPreset("dpi_300", "300 DPI · Tablet Gráfico", 300, 300),
    ManualDpiPreset("dpi_320", "320 DPI · XHDPI Padrão", 320, 320),
    ManualDpiPreset("dpi_380", "380 DPI · Tela 6.1\"", 380, 380),
    ManualDpiPreset("dpi_420", "420 DPI · FHD+ Equilibrado", 420, 420),
    ManualDpiPreset("dpi_440", "440 DPI · AMOLED Padrão", 440, 440),
    ManualDpiPreset("dpi_480", "480 DPI · XXHDPI Alta Precisão", 480, 480),
    ManualDpiPreset("dpi_520", "520 DPI · QHD+ Estúdio", 520, 520),
    ManualDpiPreset("dpi_640", "640 DPI · XXXHDPI Ultra", 640, 640)
)

enum class TabletSlotId(val label: String, val virtualDeviceId: Int) {
    GLOBAL("Driver Global", 105),
    MESA_1("Mesa 1", 105),
    MESA_2("Mesa 2", 106)
}

@JsonClass(generateAdapter = true)
data class UsbControlTransferCmd(
    val requestType: Int,
    val request: Int,
    val value: Int,
    val index: Int,
    val dataHex: String
)

@JsonClass(generateAdapter = true)
data class TabletProfileJson(
    val id: String,
    val name: String,
    val brand: String,
    val vendorId: Int,
    val productIds: List<Int>,
    val widthMm: Float,
    val heightMm: Float,
    val maxX: Int,
    val maxY: Int,
    val maxPressure: Int,
    val lpi: Int,
    val pollingRateHz: Int,
    val interfaceNumber: Int,
    val endpointAddress: Int,
    val parserType: String,
    val initControlTransfers: List<UsbControlTransferCmd> = emptyList(),
    val expressKeysCount: Int = 0,
    val supportsTilt: Boolean = false,
    val isPenDisplay: Boolean = false,
    val nativeWidthPx: Int = 0,
    val nativeHeightPx: Int = 0,
    val nativeDpi: Int = 0
)

data class PressureControlPoint(
    val inputNorm: Float,
    val outputNorm: Float
)

data class ActiveAreaRect(
    val leftNorm: Float = 0f,
    val topNorm: Float = 0f,
    val widthNorm: Float = 1f,
    val heightNorm: Float = 1f
)

data class FilterSettings(
    val antiJitterEnabled: Boolean = false,
    val antiJitterRadius: Float = 2.5f,
    val smoothingEnabled: Boolean = false,
    val smoothingAlpha: Float = 0.35f,
    val oneEuroEnabled: Boolean = false,
    val oneEuroMinCutoff: Float = 1.6f,
    val oneEuroBeta: Float = 0.08f,
    val tiltCompensationEnabled: Boolean = false,
    val tiltFactor: Float = 0.25f,
    val hysteresisEnabled: Boolean = false,
    val hysteresisThreshold: Float = 4.0f,
    val predictionEnabled: Boolean = false,
    val predictionLeadMs: Float = 3.0f
)

data class PenBindings(
    val tipAction: String = "Toque / Clique Primário",
    val button1Action: String = "Botão Direito / Apagar",
    val button2Action: String = "Borracha / Pan",
    val expressKeyActions: Map<Int, String> = mapOf(
        0 to "Ctrl + Z (Desfazer)",
        1 to "Ctrl + Y (Refazer)",
        2 to "B (Pincel)",
        3 to "E (Borracha)",
        4 to "Espaço (Pan)",
        5 to "Ctrl + + (Zoom +)",
        6 to "Ctrl + - (Zoom -)",
        7 to "Alt (Conta-gotas)"
    )
)

data class TabletConfig(
    val slotId: TabletSlotId = TabletSlotId.GLOBAL,
    val profileId: String = "ugee_s640",
    val autoRotation: Boolean = true,
    val rotateTabletAxesWithScreen: Boolean = false,
    val manualRotationDeg: Int = 0, // 0, 90, 180, 270
    val invertX: Boolean = false,
    val invertY: Boolean = false,
    val activeArea: ActiveAreaRect = ActiveAreaRect(),
    val screenArea: ActiveAreaRect = ActiveAreaRect(),
    val lockAspectRatio: Boolean = true,
    val dpiMode: DpiNormalizationMode = DpiNormalizationMode.AUTO,
    val manualDpiX: Int = 420,
    val manualDpiY: Int = 420,
    val minPressureThreshold: Float = 0.01f,
    val maxPressureCap: Float = 1.0f,
    val pressureCurve: List<PressureControlPoint> = listOf(
        PressureControlPoint(0f, 0f),
        PressureControlPoint(0.25f, 0.25f),
        PressureControlPoint(0.50f, 0.50f),
        PressureControlPoint(0.75f, 0.75f),
        PressureControlPoint(1.0f, 1.0f)
    ),
    val filters: FilterSettings = FilterSettings(),
    val outputMode: OutputMode = OutputMode.ABSOLUTE,
    val relativeSensitivity: Float = 1.4f,
    val targetDisplayId: Int = 0,
    val targetAppPackage: String? = null,
    val bindings: PenBindings = PenBindings()
)

/**
 * Mutable pre-allocated struct used in the zero-allocation hot path.
 * Never allocate this per USB packet!
 */
class TabletReport {
    @JvmField var valid: Boolean = false
    @JvmField var rawX: Int = 0
    @JvmField var rawY: Int = 0
    @JvmField var maxX: Int = 32767
    @JvmField var maxY: Int = 32767
    @JvmField var rawPressure: Int = 0
    @JvmField var maxPressure: Int = 8191
    @JvmField var tiltXDeg: Float = 0f
    @JvmField var tiltYDeg: Float = 0f
    @JvmField var isHovering: Boolean = false
    @JvmField var isTipDown: Boolean = false
    @JvmField var isEraser: Boolean = false
    @JvmField var barrelButton1: Boolean = false
    @JvmField var barrelButton2: Boolean = false
    @JvmField var expressKeyMask: Int = 0
    @JvmField var isExpressKeyFrame: Boolean = false
    @JvmField var timestampNs: Long = 0L
    @JvmField var sequenceId: Long = 0L

    fun reset() {
        valid = false
        rawX = 0
        rawY = 0
        rawPressure = 0
        tiltXDeg = 0f
        tiltYDeg = 0f
        isHovering = false
        isTipDown = false
        isEraser = false
        barrelButton1 = false
        barrelButton2 = false
        expressKeyMask = 0
        isExpressKeyFrame = false
    }
}

data class HidHexFrame(
    val timestampMs: Long,
    val length: Int,
    val hexString: String,
    val sourceLabel: String
)

data class DriverTelemetry(
    val activeProfileName: String = "Ugee S640 (Standby)",
    val brand: TabletBrand = TabletBrand.UGEE,
    val pollingRateHz: Int = 0,
    val totalLatencyMs: Float = 0f,
    val parseLatencyUs: Float = 0f,
    val matrixLatencyUs: Float = 0f,
    val injectLatencyUs: Float = 0f,
    val droppedPackets: Long = 0L,
    val totalPackets: Long = 0L,
    val batchSize: Int = 1,
    val lastRawX: Int = 0,
    val lastRawY: Int = 0,
    val lastScreenX: Float = 0f,
    val lastScreenY: Float = 0f,
    val lastRawPressure: Int = 0,
    val maxNativePressure: Int = 8191,
    val lastCurvedPressure: Float = 0f,
    val lastTiltX: Float = 0f,
    val lastTiltY: Float = 0f,
    val isPenInRange: Boolean = false,
    val isTipDown: Boolean = false,
    val effectiveRotationDeg: Int = 0,
    val effectiveDpiX: Float = 420f,
    val effectiveDpiY: Float = 420f,
    val activeWidthMm: Float = 160f,
    val activeHeightMm: Float = 100f,
    val pxPerMmX: Float = 16.5f,
    val lastTriggeredShortcut: String = ""
)
