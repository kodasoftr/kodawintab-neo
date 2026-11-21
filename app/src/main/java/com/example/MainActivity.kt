package com.example

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PointerIcon
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon as ComposePointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.hub.CursorRenderMode
import com.example.service.KodaHubForegroundService
import com.example.ui.KodaViewModel
import com.example.ui.screens.ActivationScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.HubScreen
import com.example.ui.screens.PrivilegeStatusScreen
import com.example.ui.screens.TabletDriverScreen
import com.example.ui.theme.KodaWintabTheme
import com.example.usb.UsbPeripheralKind

object KodaRoutes {
    const val ROUTE_ACTIVATION = "activation"
    const val ROUTE_PRIVILEGES = "privileges"
    const val ROUTE_HOME = "home"
    const val ROUTE_MESA = "mesa"
    const val ROUTE_HUB = "hub"
}

class MainActivity : ComponentActivity() {

    private var terminateReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KodaHubForegroundService.setUserTerminatedFlag(this, false)
        enableEdgeToEdge()

        terminateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == KodaHubForegroundService.ACTION_APP_TERMINATED) {
                    finishAndRemoveTask()
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            terminateReceiver,
            IntentFilter(KodaHubForegroundService.ACTION_APP_TERMINATED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        (application as? KodaWintabApp)?.usbCoordinator?.onNewUsbIntent(intent)

        setContent {
            KodaWintabTheme {
                KodaWintabRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        (application as? KodaWintabApp)?.usbCoordinator?.onNewUsbIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val app = application as? KodaWintabApp ?: return
        app.displayManager.refreshDisplays()
        app.syncDisplayStateEverywhere(app.displayManager.getDisplayOrDefault(0))
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        forwardPointerToCursorOverlay(ev)
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        forwardPointerToCursorOverlay(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun forwardPointerToCursorOverlay(ev: MotionEvent) {
        val tool = if (ev.pointerCount > 0) ev.getToolType(0) else MotionEvent.TOOL_TYPE_UNKNOWN
        val isStylus = tool == MotionEvent.TOOL_TYPE_STYLUS ||
            tool == MotionEvent.TOOL_TYPE_ERASER ||
            (ev.source and InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS
        val isMouse = tool == MotionEvent.TOOL_TYPE_MOUSE ||
            (ev.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
        if (isStylus || isMouse) {
            val rx = ev.rawX
            val ry = ev.rawY
            if (!rx.isFinite() || !ry.isFinite()) return
            val app = application as? KodaWintabApp ?: return
            val visible = ev.actionMasked != MotionEvent.ACTION_HOVER_EXIT &&
                ev.actionMasked != MotionEvent.ACTION_CANCEL
            app.cursorOverlayManager.updateCursorPosition(
                x = rx,
                y = ry,
                visible = visible,
                fromTablet = isStylus,
                channel = 1,
                directSource = true
            )
        }
    }

    override fun onDestroy() {
        terminateReceiver?.let { runCatching { unregisterReceiver(it) } }
        terminateReceiver = null
        super.onDestroy()
    }
}

@Composable
fun KodaWintabRoot(
    viewModel: KodaViewModel = viewModel(
        factory = KodaViewModel.factory(
            LocalContext.current.applicationContext as android.app.Application
        )
    )
) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val errorPopup by viewModel.errorPopup.collectAsStateWithLifecycle()
    val usbDevices by viewModel.usbDevices.collectAsStateWithLifecycle()
    val usbEventLog by viewModel.usbEventLog.collectAsStateWithLifecycle()
    val displays by viewModel.displays.collectAsStateWithLifecycle()
    val hubSlots by viewModel.hubSlots.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val coopEvent by viewModel.coopEvent.collectAsStateWithLifecycle()
    val simulatedClosedPackages by viewModel.simulatedClosedPackages.collectAsStateWithLifecycle()
    val selectedSlot by viewModel.selectedSlot.collectAsStateWithLifecycle()
    val tabletConfig by viewModel.tabletConfig.collectAsStateWithLifecycle()
    val availableProfiles by viewModel.availableProfiles.collectAsStateWithLifecycle()
    val telemetryMesa1 by viewModel.telemetryMesa1.collectAsStateWithLifecycle()
    val hexFrames by viewModel.hexFrames.collectAsStateWithLifecycle()
    val statusToast by viewModel.statusToast.collectAsStateWithLifecycle()
    val cursorSnapshot by viewModel.cursorSnapshot.collectAsStateWithLifecycle()

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshOverlayPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val nullAndroidPointerIcon = remember(context) {
        runCatching { PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL) }.getOrNull()
    }
    val shouldHideNativeCompose = cursorSnapshot.activeCursor != null &&
        !cursorSnapshot.isPaused &&
        cursorSnapshot.mode == CursorRenderMode.SIMULATED &&
        nullAndroidPointerIcon != null

    // Apply native Android View PointerIcon in Native mode, OR hide native arrow (TYPE_NULL) in Simulated mode
    LaunchedEffect(cursorSnapshot.mode, cursorSnapshot.activeCursor, cursorSnapshot.isPaused) {
        val active = cursorSnapshot.activeCursor
        view.pointerIcon = when {
            active == null || cursorSnapshot.isPaused -> null
            cursorSnapshot.mode == CursorRenderMode.NATIVE -> active.pointerIcon
            else -> nullAndroidPointerIcon
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(statusToast) {
        statusToast?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearToast()
        }
    }

    LaunchedEffect(coopEvent) {
        coopEvent?.let { ev ->
            snackbarHostState.showSnackbar(ev.message)
        }
    }

    val initialDestination = remember {
        if (viewModel.isPermanentlyActivated()) {
            KodaRoutes.ROUTE_HOME
        } else {
            KodaRoutes.ROUTE_ACTIVATION
        }
    }

    val rootModifier = Modifier
        .fillMaxSize()
        .let { base ->
            if (shouldHideNativeCompose) {
                base.pointerHoverIcon(
                    icon = ComposePointerIcon(nullAndroidPointerIcon),
                    overrideDescendants = true
                )
            } else {
                base
            }
        }

    Scaffold(
        modifier = rootModifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = initialDestination,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(KodaRoutes.ROUTE_ACTIVATION) {
                ActivationScreen(
                    engineState = engineState,
                    errorPopup = errorPopup,
                    onSilentPoll = { viewModel.silentPollEngineConnection() },
                    onVerifyConnection = { viewModel.verifyEngineConnection(showErrorPopupOnFail = true) },
                    onSelectTransportMode = { viewModel.setAdbTransportMode(it) },
                    onWifiPortChanged = { viewModel.setWifiPort(it) },
                    onKeepTerminalOpenChanged = { viewModel.setKeepTerminalOpenMode(it) },
                    onRefreshNetworkIp = { viewModel.refreshLocalNetworkIp() },
                    onDismissErrorPopup = { viewModel.dismissErrorPopup() },
                    onActivatedOpenApp = {
                        viewModel.startHubServiceSafely()
                        navController.navigate(KodaRoutes.ROUTE_HOME) {
                            popUpTo(KodaRoutes.ROUTE_ACTIVATION) { inclusive = true }
                        }
                    },
                    onEnterDiagnostic = {
                        viewModel.enterDiagnosticMode()
                        navController.navigate(KodaRoutes.ROUTE_HOME)
                    }
                )
            }

            composable(KodaRoutes.ROUTE_HOME) {
                HomeScreen(
                    engineState = engineState,
                    telemetry = telemetryMesa1,
                    usbDevices = usbDevices,
                    displays = displays,
                    hubSlots = hubSlots,
                    onOpenMesa = { navController.navigate(KodaRoutes.ROUTE_MESA) },
                    onOpenHub = { navController.navigate(KodaRoutes.ROUTE_HUB) },
                    onOpenActivation = {
                        if (engineState.permanentlyActivated || engineState.isActive || viewModel.isPermanentlyActivated()) {
                            navController.navigate(KodaRoutes.ROUTE_PRIVILEGES)
                        } else {
                            navController.navigate(KodaRoutes.ROUTE_ACTIVATION)
                        }
                    }
                )
            }

            composable(KodaRoutes.ROUTE_PRIVILEGES) {
                PrivilegeStatusScreen(
                    engineState = engineState,
                    overlayGranted = cursorSnapshot.overlayPermissionGranted,
                    usbDeviceCount = usbDevices.size,
                    onBack = { navController.popBackStack() },
                    onSilentReconnect = {
                        viewModel.silentPollEngineConnection()
                        viewModel.refreshOverlayPermission()
                    },
                    onGrantOverlayViaAdb = { viewModel.grantOverlayViaAdb() },
                    onOpenOverlaySettings = {
                        runCatching {
                            val intent = Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        }
                    }
                )
            }

            composable(KodaRoutes.ROUTE_MESA) {
                TabletDriverScreen(
                    selectedSlot = selectedSlot,
                    tabletConfig = tabletConfig,
                    availableProfiles = availableProfiles,
                    telemetry = telemetryMesa1,
                    hexFrames = hexFrames,
                    displays = displays,
                    installedApps = installedApps,
                    connectedTabletCount = usbDevices.count { it.kind == UsbPeripheralKind.TABLET },
                    cursorSnapshot = cursorSnapshot,
                    onBack = { navController.popBackStack() },
                    onSelectSlot = { viewModel.selectTabletSlot(it) },
                    onSelectProfile = { viewModel.selectProfile(it) },
                    onUpdateConfig = { viewModel.updateTabletConfig(it) },
                    onApplyPressurePreset = { pts, label -> viewModel.applyPressureCurvePreset(pts, label) },
                    onUpdatePressurePoint = { idx, x, y -> viewModel.updatePressurePoint(idx, x, y) },
                    onResetPressureCurve = { viewModel.resetPressureCurve() },
                    onFeedTestPacket = { x, y, p, down -> viewModel.feedSimulatedUgeePacket(x, y, p, down) },
                    onApplyCalibration = { minX, maxX, minY, maxY ->
                        viewModel.applyFourCornerCalibration(minX, maxX, minY, maxY)
                    },
                    onResetFactoryCalibration = { viewModel.resetCalibrationToFactory() },
                    onResetTabletTab = { viewModel.resetTabletTab() },
                    onResetPenTab = { viewModel.resetPenTab() },
                    onResetBindingsTab = { viewModel.resetBindingsTab() },
                    onResetFiltersTab = { viewModel.resetFiltersTab() },
                    onResetOutputTab = { viewModel.resetOutputTab() },
                    onResetAllTabs = { viewModel.resetAllTabs() },
                    onActivateAutoDpi = { viewModel.activateAutoDpi() },
                    onOpenCursorHub = { navController.navigate(KodaRoutes.ROUTE_HUB) }
                )
            }

            composable(KodaRoutes.ROUTE_HUB) {
                HubScreen(
                    usbDevices = usbDevices,
                    usbEventLog = usbEventLog,
                    hubSlots = hubSlots,
                    installedApps = installedApps,
                    coopEvent = coopEvent,
                    simulatedClosedPackages = simulatedClosedPackages,
                    cursorSnapshot = cursorSnapshot,
                    engineActive = engineState.isActive,
                    onBack = { navController.popBackStack() },
                    onRefreshDevices = { viewModel.refreshUsbAndDisplays() },
                    onRequestUsbPermission = { viewModel.requestUsbPermission(it) },
                    onAssignPhysicalDevice = { slot, key, name -> viewModel.assignHubDevice(slot, key, name) },
                    onAssignCombinedDevices = { slot, k1, n1, k2, n2 ->
                        viewModel.assignHubCombinedDevices(slot, k1, n1, k2, n2)
                    },
                    onAssignTargetApp = { slot, app -> viewModel.assignHubTargetApp(slot, app) },
                    onImportCustomCursor = { slot, uri -> viewModel.importCustomCursor(context, slot, uri) },
                    onClearCustomCursor = { slot -> viewModel.clearCustomCursor(slot) },
                    onUpdateCursorHotspot = { slot, hx, hy -> viewModel.updateSlotCursorHotspot(slot, hx, hy) },
                    onSelectCursorMode = { viewModel.setCursorRenderMode(it) },
                    onChangeCursorScale = { viewModel.setCursorScale(it) },
                    onGrantOverlayViaAdb = { viewModel.grantOverlayViaAdb() },
                    onToggleSimulatedAppClosed = { pkg, label -> viewModel.toggleSimulatedAppClosed(pkg, label) },
                    onDismissCoopBanner = { viewModel.dismissCoopBanner() }
                )
            }
        }
    }
}
