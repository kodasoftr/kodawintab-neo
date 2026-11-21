package com.example

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.driver.TabletProfileRepository
import com.example.hub.CursorDecoder
import com.example.hub.HubManager
import com.example.hub.HubSlotId
import com.example.koda.engine.KodaEngineClient
import com.example.service.KodaHubForegroundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Koda Wintab", appName)
    }

    @Test
    fun `tablet profile repository resolves Ugee S640 by VID PID`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = TabletProfileRepository(context)
        val ugee = repo.findByVidPid(0x28BD, 0x0913)
        assertNotNull(ugee)
        assertEquals("Ugee S640", ugee?.name)
        assertEquals(8191, ugee?.maxPressure)
        assertTrue(repo.getAllProfiles().size >= 4)
    }

    @Test
    fun `cursor decoder parses synthetic windows cur with exact hotspot`() {
        val w = 16
        val h = 16
        val headerSize = 40
        val imgBytes = w * h * 4
        val totalDib = headerSize + imgBytes
        val cur = ByteBuffer.allocate(6 + 16 + totalDib).order(ByteOrder.LITTLE_ENDIAN)
        // ICONDIR
        cur.putShort(0)
        cur.putShort(2) // CUR
        cur.putShort(1) // 1 image
        // ICONDIRENTRY
        cur.put(w.toByte())
        cur.put(h.toByte())
        cur.put(0)
        cur.put(0)
        cur.putShort(7) // hotspotX = 7
        cur.putShort(11) // hotspotY = 11
        cur.putInt(totalDib)
        cur.putInt(22)
        // BITMAPINFOHEADER
        cur.putInt(headerSize)
        cur.putInt(w)
        cur.putInt(h * 2)
        cur.putShort(1)
        cur.putShort(32)
        cur.putInt(0)
        cur.putInt(imgBytes)
        cur.position(22 + headerSize)
        repeat(w * h) {
            cur.putInt(0xFF00E5FF.toInt())
        }

        val decoded = CursorDecoder.decodeBytes(cur.array(), "aim_cross.cur")
        assertNotNull(decoded)
        assertEquals(7f, decoded?.hotspotX ?: -1f, 0.01f)
        assertEquals(11f, decoded?.hotspotY ?: -1f, 0.01f)
        assertEquals("CUR", decoded?.sourceFormat)
    }

    @Test
    fun `hub manager supports simultaneous combined mouse plus tablet attribution on mouse 1`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val hub = HubManager(context, KodaEngineClient(context))
        hub.assignCombinedDevices(
            slotId = HubSlotId.MOUSE_1,
            primaryKey = "046D:C08B:1",
            primaryName = "Logitech G203 Mouse",
            secondaryKey = "28BD:0913:2",
            secondaryName = "Ugee S640"
        )
        val slot = hub.slots.value.first { it.slotId == HubSlotId.MOUSE_1 }
        assertEquals("Logitech G203 Mouse + Ugee S640", slot.deviceSummaryLabel())
    }

    @Test
    fun `foreground service pause and terminate actions set expected shutdown flag`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        KodaHubForegroundService.setUserTerminatedFlag(context, false)
        val controller = Robolectric.buildService(KodaHubForegroundService::class.java).create()
        controller.startCommand(0, 1) // normal start
        val terminateIntent = Intent(context, KodaHubForegroundService::class.java)
            .setAction(KodaHubForegroundService.ACTION_TERMINATE)
        controller.get().onStartCommand(terminateIntent, 0, 2)
        assertTrue(KodaHubForegroundService.wasUserTerminated(context))
    }

    @Test
    fun `unified cursor channel shares cur across mouse 1 and mesa 1 separately from channel 2`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val overlay = com.example.hub.CursorOverlayManager(context)
        val hub = HubManager(context, KodaEngineClient(context), overlay)

        val bmp1 = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
        val cur1 = com.example.hub.DecodedCursor("ch1_arrow.cur", bmp1, 2f, 2f, null, "CUR")
        val bmp2 = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
        val cur2 = com.example.hub.DecodedCursor("ch2_star.ani", bmp2, 4f, 4f, null, "ANI")

        hub.assignCustomCursor(HubSlotId.MOUSE_1, cur1)
        hub.assignCustomCursor(HubSlotId.MOUSE_2, cur2)

        val slots = hub.slots.value.associateBy { it.slotId }
        assertEquals("ch1_arrow.cur", slots[HubSlotId.MOUSE_1]?.customCursor?.fileName)
        assertEquals("ch1_arrow.cur", slots[HubSlotId.TABLET_1]?.customCursor?.fileName)
        assertEquals("ch2_star.ani", slots[HubSlotId.MOUSE_2]?.customCursor?.fileName)
        assertEquals("ch2_star.ani", slots[HubSlotId.TABLET_2]?.customCursor?.fileName)
        assertEquals("ch1_arrow.cur", overlay.snapshot.value.channel1Cursor?.fileName)
        assertEquals("ch2_star.ani", overlay.snapshot.value.channel2Cursor?.fileName)
    }

    @Test
    fun `permanent adb activation persists even after service Encerrar shutdown`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("koda_engine_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("adb_permanently_activated", true)
            .commit()

        val client = KodaEngineClient(context)
        assertTrue(client.isPermanentlyActivated())

        val terminateIntent = Intent(context, KodaHubForegroundService::class.java)
            .setAction(KodaHubForegroundService.ACTION_TERMINATE)
        val controller = Robolectric.buildService(KodaHubForegroundService::class.java).create()
        controller.get().onStartCommand(terminateIntent, 0, 1)

        // Encerrar sets user terminated flag, but permanent ADB permission flag stays intact
        assertTrue(KodaHubForegroundService.wasUserTerminated(context))
        assertTrue(KodaEngineClient(context).isPermanentlyActivated())
    }

    @Test
    fun `cursor overlay follows global pointer coordinates on magnetic field hover and pen pressure`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val overlay = com.example.hub.CursorOverlayManager(context)
        val bmp = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
        val cur = com.example.hub.DecodedCursor("tablet_pen.cur", bmp, 1f, 1f, null, "CUR")
        overlay.setChannelCursor(1, cur)

        // 1. Stylus hovering inside magnetic field (pressure == 0)
        overlay.applyGlobalPointerSample(
            com.example.koda.engine.GlobalPointerSample(
                seq = 10L,
                hasAbs = true,
                absXNorm = 0.25f,
                absYNorm = 0.40f,
                deltaX = 0f,
                deltaY = 0f,
                pressureNorm = 0f,
                isStylus = true,
                isVisible = true
            ),
            displayWidthPx = 1000f,
            displayHeightPx = 2000f
        )
        assertEquals(250f, overlay.snapshot.value.cursorX, 1f)
        assertEquals(800f, overlay.snapshot.value.cursorY, 1f)
        assertTrue(overlay.snapshot.value.isVisible)
        assertTrue(overlay.snapshot.value.isFromTablet)

        // 2. Pen pressed with pressure (> 0) continues tracking X/Y seamlessly
        overlay.applyGlobalPointerSample(
            com.example.koda.engine.GlobalPointerSample(
                seq = 11L,
                hasAbs = true,
                absXNorm = 0.60f,
                absYNorm = 0.75f,
                deltaX = 0f,
                deltaY = 0f,
                pressureNorm = 0.65f,
                isStylus = true,
                isVisible = true
            ),
            displayWidthPx = 1000f,
            displayHeightPx = 2000f
        )
        assertEquals(600f, overlay.snapshot.value.cursorX, 1f)
        assertEquals(1500f, overlay.snapshot.value.cursorY, 1f)
        assertTrue(overlay.snapshot.value.isVisible)
    }

    @Test
    fun `cursor overlay matches landscape tablet rotation and skips duplicate mouse getevent deltas`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val overlay = com.example.hub.CursorOverlayManager(context)
        val bmp = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
        val cur = com.example.hub.DecodedCursor("mouse_arrow.cur", bmp, 2f, 2f, null, "CUR")
        overlay.setChannelCursor(1, cur)
        overlay.setTabletRotationConfig(autoRotateWithScreen = true, manualRotationDeg = 0)

        // Stylus hover in Landscape 90° matches CoordinateMatrix (rotNormX = y, rotNormY = 1 - x)
        overlay.applyGlobalPointerSample(
            com.example.koda.engine.GlobalPointerSample(
                seq = 100L,
                hasAbs = true,
                absXNorm = 0.20f,
                absYNorm = 0.30f,
                deltaX = 0f,
                deltaY = 0f,
                pressureNorm = 0f,
                isStylus = true,
                isVisible = true
            ),
            displayWidthPx = 2400f,
            displayHeightPx = 1080f,
            displayRotationDeg = 90
        )
        assertEquals(720f, overlay.snapshot.value.cursorX, 1f)
        assertEquals(864f, overlay.snapshot.value.cursorY, 1f)

        // Direct USB mouse / MotionEvent update suppresses duplicate raw getevent REL deltas
        overlay.updateCursorPosition(x = 500f, y = 400f, visible = true, fromTablet = false, channel = 1, directSource = true)
        overlay.applyGlobalPointerSample(
            com.example.koda.engine.GlobalPointerSample(
                seq = 101L,
                hasAbs = false,
                absXNorm = 0f,
                absYNorm = 0f,
                deltaX = 50f,
                deltaY = 20f,
                pressureNorm = 0f,
                isStylus = false,
                isVisible = true
            ),
            displayWidthPx = 2400f,
            displayHeightPx = 1080f,
            displayRotationDeg = 90
        )
        assertEquals(500f, overlay.snapshot.value.cursorX, 0.5f)
        assertEquals(400f, overlay.snapshot.value.cursorY, 0.5f)
    }

    @Test
    fun `cuj activation error popup fullscreen blank sheet calibration and hub combined flow`() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("developer_signature_text").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("verify_connection_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // Error popup appears with clear error explanation when ADB is inactive
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodes(hasTestTag("error_popup_ok_button"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("error_popup_ok_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        // Diagnostic flow into Home -> Mesa
        composeTestRule.onNodeWithTag("enter_diagnostic_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("home_mesa_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("otd_tab_tablet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("reset_all_tabs_button").assertIsDisplayed()

        // Toggle auto-rotation off (Mesa Fixa) & tap manual +90 rotate button
        composeTestRule.onNodeWithTag("auto_rotation_switch").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("manual_rotate_tablet_90_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // Launch Full-Screen Blank White Sheet stroke test, test clear & back buttons
        composeTestRule.onNodeWithTag("open_blank_sheet_button").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodes(hasTestTag("blank_sheet_delete_stroke_button"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("blank_sheet_delete_stroke_button").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("blank_sheet_back_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        // Launch Full-Screen 5-Point Guided Calibration Intro -> Factory Reset -> Start -> 1 point at a time
        composeTestRule.onNodeWithTag("open_fullscreen_calibration_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("calibration_factory_reset_button").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("calibration_start_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("calibration_confirm_step_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("calibration_back_button").performClick()
        composeTestRule.waitForIdle()

        // Tablet tab reset
        composeTestRule.onNodeWithTag("reset_tab_tablet_button").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        // Pen tab reset
        composeTestRule.onNodeWithTag("otd_tab_pen").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("reset_tab_pen_button").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        // Open Bindings tab -> pick manual keyboard shortcut on Express Key 1 & reset Bindings tab
        composeTestRule.onNodeWithTag("otd_tab_bindings").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("binding_express_key_2").performScrollTo()
        composeTestRule.onNodeWithTag("binding_express_key_0").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodes(hasTestTag("key_pick_A"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("key_pick_A").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("shortcut_confirm_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("reset_tab_bindings_button").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        // Filters tab reset
        composeTestRule.onNodeWithTag("otd_tab_filters").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("reset_tab_filters_button").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        // Open Output tab -> verify Auto DPI + Manual DPI 10 presets + custom X/Y DPI fields + Output reset + Global Reset All
        composeTestRule.onNodeWithTag("otd_tab_output").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("auto_dpi_button").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("dpi_mode_manual_chip").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("manual_dpi_preset_440").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("manual_dpi_x_input").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("manual_dpi_y_input").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("reset_tab_output_button").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("reset_all_tabs_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("mesa_back_button").performClick()
        composeTestRule.waitForIdle()

        // Open Hub & verify Global Cursor Modes + Mouse 1 / Mesa 1 slots
        composeTestRule.onNodeWithTag("home_hub_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("cursor_mode_native").assertIsDisplayed()
        composeTestRule.onNodeWithTag("cursor_mode_simulated").assertIsDisplayed()
        composeTestRule.onNodeWithTag("hub_lazy_column").performScrollToNode(hasTestTag("hub_slot_mouse_1"))
        composeTestRule.onNodeWithTag("hub_slot_mouse_1").assertIsDisplayed()
    }

    @Test
    fun `permanent activation retains direct USB OTG mode after PC unplug and Developer Options off`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("koda_engine_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("adb_permanently_activated", true)
            .putInt("adb_server_uid", 2000)
            .commit()

        val client = KodaEngineClient(context)
        assertTrue(client.isPermanentlyActivated())
        assertTrue(client.state.value.permanentlyActivated)

        val result = kotlinx.coroutines.runBlocking { client.verifyConnection() }
        // Even if Shell socket is stopped when Developer Options is turned OFF, permanent OTG activation stays intact
        assertTrue(result.permanentlyActivated)
        assertTrue(result.lastCheckMessage.contains("USB OTG Direto", ignoreCase = true))
    }
}
