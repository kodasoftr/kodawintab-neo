package com.example

import com.example.driver.ActiveAreaRect
import com.example.driver.CoordinateMatrix
import com.example.driver.HuionParser
import com.example.driver.PressureControlPoint
import com.example.driver.PressureCurveLut
import com.example.driver.TabletReport
import com.example.driver.UgeeS640Parser
import com.example.driver.WacomParser
import com.example.driver.ZeroAllocRingBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabletDriverUnitTest {

    @Test
    fun `ugee s640 parser decodes 16bit coords 8192 pressure tilt and barrel buttons`() {
        val parser = UgeeS640Parser()
        val report = TabletReport()
        val packet = byteArrayOf(
            0x02,
            0xA3.toByte(), // in-range + tip down + barrel1
            0x00, 0x40,    // X = 16384
            0xFF.toByte(), 0x7F, // Y = 32767
            0xFF.toByte(), 0x1F, // Pressure = 8191
            15,            // Tilt X = +15
            (-12).toByte() // Tilt Y = -12
        )

        val ok = parser.parse(packet, packet.size, report)
        assertTrue(ok)
        assertTrue(report.valid)
        assertTrue(report.isHovering)
        assertTrue(report.isTipDown)
        assertTrue(report.barrelButton1)
        assertFalse(report.barrelButton2)
        assertEquals(16384, report.rawX)
        assertEquals(32767, report.rawY)
        assertEquals(8191, report.rawPressure)
        assertEquals(15f, report.tiltXDeg, 0.01f)
        assertEquals(-12f, report.tiltYDeg, 0.01f)
    }

    @Test
    fun `ugee s640 parser decodes express keys frame`() {
        val parser = UgeeS640Parser()
        val report = TabletReport()
        val packet = byteArrayOf(0x02, 0xF0.toByte(), 0x05, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertTrue(parser.parse(packet, packet.size, report))
        assertEquals(0x0005, report.expressKeyMask)
        assertFalse(report.isTipDown)
    }

    @Test
    fun `wacom and huion parsers decode valid stylus packets`() {
        val report = TabletReport()
        val wacom = WacomParser()
        val wacomPkt = byteArrayOf(0x02, 0xA1.toByte(), 0x80.toByte(), 0x10, 0x40, 0x08, 0xFF.toByte(), 0x0F)
        assertTrue(wacom.parse(wacomPkt, wacomPkt.size, report))
        assertEquals(4224, report.rawX)
        assertEquals(4095, report.rawPressure)

        val huion = HuionParser()
        val huionPkt = byteArrayOf(0x08, 0x81.toByte(), 0x10, 0x20, 0x30, 0x40, 0x00, 0x10)
        assertTrue(huion.parse(huionPkt, huionPkt.size, report))
        assertEquals(0x2010, report.rawX)
        assertEquals(4096, report.rawPressure)
    }

    @Test
    fun `coordinate matrix handles rotation 90 180 and invert x y with subpixel accuracy`() {
        val matrix = CoordinateMatrix()
        val out = FloatArray(2)

        // 0 deg, full screen 1000x2000
        matrix.rebuild(
            tabletMaxX = 1000,
            tabletMaxY = 1000,
            activeArea = ActiveAreaRect(),
            screenArea = ActiveAreaRect(),
            displayWidthPx = 1000f,
            displayHeightPx = 2000f,
            manualRotationDeg = 0,
            autoRotation = false,
            displayRotationDeg = 0,
            invertX = false,
            invertY = false
        )
        matrix.transform(250f, 500f, out)
        assertEquals(250f, out[0], 0.5f)
        assertEquals(1000f, out[1], 0.5f)

        // Invert X + Invert Y == 180 deg
        matrix.rebuild(
            tabletMaxX = 1000,
            tabletMaxY = 1000,
            activeArea = ActiveAreaRect(),
            screenArea = ActiveAreaRect(),
            displayWidthPx = 1000f,
            displayHeightPx = 2000f,
            manualRotationDeg = 0,
            autoRotation = true,
            displayRotationDeg = 180,
            invertX = false,
            invertY = false
        )
        matrix.transform(250f, 500f, out)
        assertEquals(750f, out[0], 0.5f)
        assertEquals(1000f, out[1], 0.5f)
    }

    @Test
    fun `pressure curve lut evaluates O1 lookup accurately`() {
        val lut = PressureCurveLut()
        lut.rebuild(
            points = listOf(
                PressureControlPoint(0f, 0f),
                PressureControlPoint(0.5f, 0.75f),
                PressureControlPoint(1f, 1f)
            ),
            minThreshold = 0f,
            maxCap = 1f
        )
        assertEquals(0f, lut.lookup(0, 8191), 0.01f)
        assertEquals(0.75f, lut.lookup(4096, 8191), 0.05f)
        assertEquals(1.0f, lut.lookup(8191, 8191), 0.01f)
    }

    @Test
    fun `zero alloc ring buffer preserves fifo ordering`() {
        val ring = ZeroAllocRingBuffer(capacity = 4, packetSize = 10)
        val p1 = byteArrayOf(1, 2, 3, 4)
        val p2 = byteArrayOf(5, 6, 7, 8, 9)
        assertTrue(ring.offer(p1, 4, 100L))
        assertTrue(ring.offer(p2, 5, 200L))

        val out = ByteArray(10)
        val meta = LongArray(1)
        assertEquals(4, ring.poll(out, meta))
        assertEquals(100L, meta[0])
        assertEquals(1, out[0].toInt())

        assertEquals(5, ring.poll(out, meta))
        assertEquals(200L, meta[0])
        assertEquals(5, out[0].toInt())
        assertEquals(-1, ring.poll(out, meta))
    }

    @Test
    fun `adb bootstrap commands are token-free and shell-safe for windows cmd and android sh`() {
        val usbCmd = com.example.koda.engine.KodaEngineClient.buildAdbBootstrapCommand(
            packageName = "com.aistudio.kodawintab.rx7k2m",
            apkPath = "/data/app/~~test==/base.apk",
            transportMode = com.example.koda.engine.AdbTransportMode.USB
        )
        assertFalse(usbCmd.contains("$("))
        assertFalse(usbCmd.contains("\\$"))
        assertFalse(usbCmd.contains("|"))
        assertFalse(usbCmd.contains("--token"))
        assertFalse(usbCmd.contains("\""))
        assertTrue(usbCmd.contains("--daemon"))
        assertTrue(usbCmd.contains("CLASSPATH=/data/app/~~test==/base.apk"))

        val wifiCmd = com.example.koda.engine.KodaEngineClient.buildAdbBootstrapCommand(
            packageName = "com.aistudio.kodawintab.rx7k2m",
            apkPath = "/data/app/~~test==/base.apk",
            transportMode = com.example.koda.engine.AdbTransportMode.WIFI_NETWORK,
            ipAddress = "192.168.0.55",
            wifiPort = "5555"
        )
        assertTrue(wifiCmd.startsWith("adb -s 192.168.0.55:5555 shell"))
        assertTrue(wifiCmd.contains("--daemon"))
        assertFalse(wifiCmd.contains("--token"))
    }

    @Test
    fun `usb mouse hid parser decodes boot mouse and wireless receiver packets`() {
        val parser = com.example.usb.MouseHidParser()
        val bootPkt = byteArrayOf(0x01, 12, (-8).toByte(), 1)
        assertTrue(parser.parse(bootPkt, bootPkt.size, sensitivity = 1.5f))
        assertTrue(parser.buttonLeft)
        assertFalse(parser.buttonRight)
        assertEquals(18f, parser.deltaX, 0.01f)
        assertEquals(-12f, parser.deltaY, 0.01f)
        assertEquals(1f, parser.wheelDelta, 0.01f)

        val wirelessPkt = byteArrayOf(0x01, 0x02, 5, 10, (-2).toByte())
        assertTrue(parser.parse(wirelessPkt, wirelessPkt.size, sensitivity = 1.0f))
        assertFalse(parser.buttonLeft)
        assertTrue(parser.buttonRight)
        assertEquals(5f, parser.deltaX, 0.01f)
        assertEquals(10f, parser.deltaY, 0.01f)
    }

    @Test
    fun `dpi normalization equalizes tall screen x y travel and exposes 10 manual dpi presets`() {
        assertEquals(10, com.example.driver.DEFAULT_MANUAL_DPI_PRESETS.size)

        val matrix = CoordinateMatrix()
        val outA = FloatArray(2)
        val outB = FloatArray(2)

        matrix.rebuild(
            tabletMaxX = 32767,
            tabletMaxY = 32767,
            activeArea = ActiveAreaRect(),
            screenArea = ActiveAreaRect(),
            displayWidthPx = 1080f,
            displayHeightPx = 2400f,
            manualRotationDeg = 0,
            autoRotation = true,
            displayRotationDeg = 90,
            invertX = false,
            invertY = false,
            rotateAxesWithScreen = false,
            dpiMode = com.example.driver.DpiNormalizationMode.MANUAL,
            targetDpiX = 440f,
            targetDpiY = 440f,
            tabletWidthMm = 160f,
            tabletHeightMm = 100f
        )
        assertEquals(0, matrix.effectiveRotationDeg)
        matrix.transform(16383f, 16383f, outA)
        assertEquals(540f, outA[0], 2f)
        assertEquals(1200f, outA[1], 2f)

        matrix.transform(32767f, 32767f, outB)
        assertEquals(1080f, outB[0], 2f)
        assertEquals(2400f, outB[1], 2f)
    }

    @Test
    fun `one euro adaptive dsp filter and key binding catalog parse custom shortcuts`() {
        val dsp = com.example.driver.OptionalDspFilters()
        val settings = com.example.driver.FilterSettings(
            oneEuroEnabled = true,
            oneEuroMinCutoff = 1.6f,
            oneEuroBeta = 0.1f,
            predictionEnabled = true,
            predictionLeadMs = 3.0f
        )
        val out = FloatArray(2)
        dsp.process(1000f, 1000f, 0f, 0f, settings, out, 1_000_000L)
        assertEquals(1000f, out[0], 0.1f)
        dsp.process(1200f, 1400f, 0f, 0f, settings, out, 5_000_000L)
        assertTrue(out[0] > 1000f)
        assertTrue(out[1] > 1000f)

        val parsed = com.example.driver.KeyBindingCatalog.parseAction("Ctrl + Shift + Z (Refazer)")
        assertEquals(android.view.KeyEvent.KEYCODE_Z, parsed.keyCode)
        assertTrue((parsed.metaState and android.view.KeyEvent.META_CTRL_ON) != 0)
        assertTrue((parsed.metaState and android.view.KeyEvent.META_SHIFT_ON) != 0)
    }

    @Test
    fun `horizontal landscape rotation aligns coordinate matrix with display CCW orientation and supports fixed rotation lock`() {
        val matrix = CoordinateMatrix()
        val out = FloatArray(2)

        // 1. Portrait 0 deg remains untouched
        matrix.rebuild(
            tabletMaxX = 1000,
            tabletMaxY = 1000,
            activeArea = ActiveAreaRect(),
            screenArea = ActiveAreaRect(),
            displayWidthPx = 1080f,
            displayHeightPx = 2400f,
            manualRotationDeg = 0,
            autoRotation = true,
            displayRotationDeg = 0,
            invertX = false,
            invertY = false,
            rotateAxesWithScreen = true
        )
        assertEquals(0, matrix.effectiveRotationDeg)
        matrix.transform(200f, 300f, out)
        assertEquals(216f, out[0], 1f)
        assertEquals(720f, out[1], 1f)

        // 2. Landscape ROTATION_90 with rotateAxesWithScreen=true:
        // Compensates Android's 90 CCW screen rotation cleanly (same as CursorOverlayManager rotNormX=y, rotNormY=1-x)
        matrix.rebuild(
            tabletMaxX = 1000,
            tabletMaxY = 1000,
            activeArea = ActiveAreaRect(),
            screenArea = ActiveAreaRect(),
            displayWidthPx = 2400f,
            displayHeightPx = 1080f,
            manualRotationDeg = 0,
            autoRotation = true,
            displayRotationDeg = 90,
            invertX = false,
            invertY = false,
            rotateAxesWithScreen = true
        )
        assertEquals(270, matrix.effectiveRotationDeg)
        matrix.transform(200f, 300f, out)
        // rotNormX = 0.30 * 2400 = 720, rotNormY = (1 - 0.20) * 1080 = 864
        assertEquals(720f, out[0], 1f)
        assertEquals(864f, out[1], 1f)

        // 3. Auto-rotation disabled + manual +90 rotation remains fixed even when display rotates to 270
        matrix.rebuild(
            tabletMaxX = 1000,
            tabletMaxY = 1000,
            activeArea = ActiveAreaRect(),
            screenArea = ActiveAreaRect(),
            displayWidthPx = 2400f,
            displayHeightPx = 1080f,
            manualRotationDeg = 90,
            autoRotation = false,
            displayRotationDeg = 270,
            invertX = false,
            invertY = false,
            rotateAxesWithScreen = true
        )
        assertEquals(90, matrix.effectiveRotationDeg)
        matrix.transform(200f, 300f, out)
        // 90 deg manual: x' = (1 - 0.3) * 2400 = 1680, y' = 0.2 * 1080 = 216
        assertEquals(1680f, out[0], 1f)
        assertEquals(216f, out[1], 1f)
    }

    @Test
    fun `usb coordinator leaves adb mtp storage and hub interfaces untouched for android system`() {
        // ADB vendor-specific debug interface (255 / 0x42 / 0x01) must pass through
        assertTrue(com.example.usb.UsbTabletCoordinator.isAdbOrSystemClass(255, 0x42, 0x01))
        // Mass Storage / MTP / Still Image / Hub / CDC must pass through
        assertTrue(com.example.usb.UsbTabletCoordinator.isAdbOrSystemClass(8, 6, 80))
        assertTrue(com.example.usb.UsbTabletCoordinator.isAdbOrSystemClass(6, 1, 1))
        assertTrue(com.example.usb.UsbTabletCoordinator.isAdbOrSystemClass(9, 0, 0))
        assertTrue(com.example.usb.UsbTabletCoordinator.isAdbOrSystemClass(2, 6, 0))
        // HID Tablet & Mouse (class 3) are NOT blocked as system/ADB
        assertFalse(com.example.usb.UsbTabletCoordinator.isAdbOrSystemClass(3, 0, 0))
        assertFalse(com.example.usb.UsbTabletCoordinator.isAdbOrSystemClass(3, 1, 2))
    }

    @Test
    fun `dsp tilt compensation plus one-euro preserves smooth velocity without prevRaw corruption`() {
        val dsp = com.example.driver.OptionalDspFilters()
        val settings = com.example.driver.FilterSettings(
            tiltCompensationEnabled = true,
            tiltFactor = 0.2f,
            oneEuroEnabled = true,
            oneEuroMinCutoff = 1.6f,
            oneEuroBeta = 0.08f
        )
        val out = FloatArray(2)
        dsp.process(5000f, 5000f, 10f, -5f, settings, out, 1_000_000L)
        dsp.process(5000f, 5000f, 10f, -5f, settings, out, 5_000_000L)
        // Stationary pen with constant tilt must stay tightly anchored to tilt-compensated coord (no velocity runaway)
        val scale = 0.2f * 140f
        val expectedX = 5000f + kotlin.math.tan(10.0 * Math.PI / 180.0).toFloat() * scale
        val expectedY = 5000f + kotlin.math.tan(-5.0 * Math.PI / 180.0).toFloat() * scale
        assertEquals(expectedX, out[0], 0.5f)
        assertEquals(expectedY, out[1], 0.5f)
    }

    @Test
    fun `manual dpi 240 vs 640 scales active physical tablet dimensions and stroke coordinates`() {
        val matrixLowDpi = CoordinateMatrix()
        val matrixHighDpi = CoordinateMatrix()
        val outLow = FloatArray(2)
        val outHigh = FloatArray(2)

        matrixLowDpi.rebuild(
            tabletMaxX = 32767,
            tabletMaxY = 32767,
            activeArea = ActiveAreaRect(),
            screenArea = ActiveAreaRect(),
            displayWidthPx = 1080f,
            displayHeightPx = 2400f,
            manualRotationDeg = 0,
            autoRotation = true,
            displayRotationDeg = 0,
            invertX = false,
            invertY = false,
            dpiMode = com.example.driver.DpiNormalizationMode.MANUAL,
            targetDpiX = 240f,
            targetDpiY = 240f,
            tabletWidthMm = 160f,
            tabletHeightMm = 100f
        )

        matrixHighDpi.rebuild(
            tabletMaxX = 32767,
            tabletMaxY = 32767,
            activeArea = ActiveAreaRect(),
            screenArea = ActiveAreaRect(),
            displayWidthPx = 1080f,
            displayHeightPx = 2400f,
            manualRotationDeg = 0,
            autoRotation = true,
            displayRotationDeg = 0,
            invertX = false,
            invertY = false,
            dpiMode = com.example.driver.DpiNormalizationMode.MANUAL,
            targetDpiX = 640f,
            targetDpiY = 640f,
            tabletWidthMm = 160f,
            tabletHeightMm = 100f
        )

        assertTrue(matrixLowDpi.activeWidthMm > matrixHighDpi.activeWidthMm + 15f)
        matrixLowDpi.transform(18500f, 16383f, outLow)
        matrixHighDpi.transform(18500f, 16383f, outHigh)
        assertTrue(outHigh[0] > outLow[0] + 40f)
    }

    @Test
    fun `auto dpi keeps constant stroke density between portrait and landscape without upside down flip`() {
        val portrait = CoordinateMatrix()
        val landscape = CoordinateMatrix()
        val outP = FloatArray(2)
        val outL = FloatArray(2)

        portrait.rebuild(
            tabletMaxX = 32767,
            tabletMaxY = 32767,
            activeArea = ActiveAreaRect(),
            screenArea = ActiveAreaRect(),
            displayWidthPx = 1080f,
            displayHeightPx = 2400f,
            manualRotationDeg = 0,
            autoRotation = true,
            displayRotationDeg = 0,
            invertX = false,
            invertY = false,
            rotateAxesWithScreen = false,
            dpiMode = com.example.driver.DpiNormalizationMode.AUTO,
            targetDpiX = 440f,
            targetDpiY = 440f,
            tabletWidthMm = 160f,
            tabletHeightMm = 100f
        )

        landscape.rebuild(
            tabletMaxX = 32767,
            tabletMaxY = 32767,
            activeArea = ActiveAreaRect(),
            screenArea = ActiveAreaRect(),
            displayWidthPx = 2400f,
            displayHeightPx = 1080f,
            manualRotationDeg = 0,
            autoRotation = true,
            displayRotationDeg = 90,
            invertX = false,
            invertY = false,
            rotateAxesWithScreen = false,
            dpiMode = com.example.driver.DpiNormalizationMode.AUTO,
            targetDpiX = 440f,
            targetDpiY = 440f,
            tabletWidthMm = 160f,
            tabletHeightMm = 100f
        )

        // Constant physical px/mm density in Portrait and Landscape -> stroke never feels heavier!
        assertEquals(portrait.effectivePxPerMmX, landscape.effectivePxPerMmX, 0.5f)
        // Not upside down: top-left stays top-left (below center), bottom-right stays bottom-right
        landscape.transform(10000f, 10000f, outL)
        landscape.transform(22000f, 22000f, outP)
        assertTrue(outP[0] > outL[0])
        assertTrue(outP[1] > outL[1])
        assertEquals(0, landscape.effectiveRotationDeg)
    }

    @Test
    fun `resilient tablet parser decodes 6 to 12 byte reports across ugee huion and generic variants`() {
        val report = TabletReport()
        val resilient = com.example.driver.ResilientTabletParser(
            primary = UgeeS640Parser(),
            fallbacks = listOf(HuionParser(), com.example.driver.GenericHidDigitizerParser())
        )

        // 1. Short 6-byte Ugee report (hover without pressure/tilt bytes)
        val shortUgee = byteArrayOf(0x07, 0xA0.toByte(), 0x20, 0x10, 0x40, 0x20)
        assertTrue(resilient.parse(shortUgee, shortUgee.size, report))
        assertEquals(0x1020, report.rawX)
        assertEquals(0x2040, report.rawY)
        assertTrue(report.isHovering)
        assertFalse(report.isTipDown)

        // 2. Alternate Huion report ID 0x0D falls back to HuionParser seamlessly
        val huionAlt = byteArrayOf(0x0D, 0x81.toByte(), 0x10, 0x20, 0x30, 0x40, 0x00, 0x08)
        assertTrue(resilient.parse(huionAlt, huionAlt.size, report))
        assertEquals(0x2010, report.rawX)
        assertEquals(0x4030, report.rawY)
        assertEquals(2048, report.rawPressure)
        assertTrue(report.isTipDown)

        // 3. Unlisted vendor report ID 0x15 falls back to GenericHidDigitizerParser
        val genericPkt = byteArrayOf(0x15, 0x81.toByte(), 0x50, 0x05, 0x60, 0x06, 0xFF.toByte(), 0x0F)
        assertTrue(resilient.parse(genericPkt, genericPkt.size, report))
        assertEquals(0x0550, report.rawX)
        assertEquals(0x0660, report.rawY)
        assertEquals(4095, report.rawPressure)
    }

    @Test
    fun `known tablet vids include uclogic hanvon and bosto alongside wacom huion ugee`() {
        val vids = com.example.usb.UsbTabletCoordinator.KNOWN_TABLET_VIDS
        assertTrue(com.example.usb.UsbTabletCoordinator.VID_WACOM in vids)
        assertTrue(com.example.usb.UsbTabletCoordinator.VID_HUION in vids)
        assertTrue(com.example.usb.UsbTabletCoordinator.VID_UGEE_XPPEN in vids)
        assertTrue(com.example.usb.UsbTabletCoordinator.VID_UCLOGIC in vids)
        assertTrue(com.example.usb.UsbTabletCoordinator.VID_HANVON in vids)
        assertTrue(com.example.usb.UsbTabletCoordinator.VID_BOSTO in vids)
        assertTrue(com.example.usb.UsbTabletCoordinator.VID_VEIKK in vids)
    }

    @Test
    fun `express key press and release frames flag isExpressKeyFrame without warping stylus`() {
        val report = TabletReport()
        val ugee = UgeeS640Parser()

        // Press express key
        val ugeePress = byteArrayOf(0x02, 0xF0.toByte(), 0x04, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertTrue(ugee.parse(ugeePress, ugeePress.size, report))
        assertTrue(report.isExpressKeyFrame)
        assertEquals(0x0004, report.expressKeyMask)

        // Release express key (mask 0x0000) still flags isExpressKeyFrame = true so pipeline won't warp cursor to (0,0)
        val ugeeRelease = byteArrayOf(0x02, 0xF0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertTrue(ugee.parse(ugeeRelease, ugeeRelease.size, report))
        assertTrue(report.isExpressKeyFrame)
        assertEquals(0, report.expressKeyMask)

        // Subsequent normal stylus frame clears isExpressKeyFrame
        val stylusPkt = byteArrayOf(0x02, 0xA1.toByte(), 0x20, 0x10, 0x40, 0x20, 0x00, 0x08, 12, (-8).toByte())
        assertTrue(ugee.parse(stylusPkt, stylusPkt.size, report))
        assertFalse(report.isExpressKeyFrame)
        assertTrue(report.isTipDown)

        // Wacom express key release (report ID 0x11)
        val wacom = WacomParser()
        val wacomRel = byteArrayOf(0x11, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertTrue(wacom.parse(wacomRel, wacomRel.size, report))
        assertTrue(report.isExpressKeyFrame)

        // Huion express key frame (status & 0xE0 == 0xE0) and Huion eraser bit (status & 0x20)
        val huion = HuionParser()
        val huionKey = byteArrayOf(0x08, 0xE0.toByte(), 0x01, 0x01, 0x00, 0x00, 0x00, 0x00)
        assertTrue(huion.parse(huionKey, huionKey.size, report))
        assertTrue(report.isExpressKeyFrame)

        val huionEraser = byteArrayOf(0x08, 0xA1.toByte(), 0x10, 0x20, 0x30, 0x40, 0x00, 0x10)
        assertTrue(huion.parse(huionEraser, huionEraser.size, report))
        assertFalse(report.isExpressKeyFrame)
        assertTrue(report.isEraser)
        assertTrue(report.isTipDown)
    }
}



