package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.app.NotificationCompat
import com.example.KodaWintabApp
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

/**
 * Foreground Service keeping USB Host device claiming, high-priority reader threads,
 * Hub routing, and cursor overlay alive. Supports Pause/Resume and complete Red Terminate.
 */
class KodaHubForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isPaused = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification(paused = false)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            (application as? KodaWintabApp)?.recordNonFatalError(
                errorName = t.javaClass.simpleName,
                reason = "Falha ao iniciar serviço em primeiro plano: ${t.message ?: "permissão negada pelo sistema"}"
            )
            stopSelf()
            return
        }

        val app = application as KodaWintabApp
        runCatching { app.usbCoordinator.registerReceiver() }
        runCatching { app.pipelineMesa1.start() }
        runCatching { app.hubManager.startCoopMonitor(app.coopMonitorScope) }
        runCatching { app.startGlobalPointerStream() }

        serviceScope.launch {
            app.engineClient.verifyConnection()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_RESUME, ACTION_STOP -> {
                togglePauseResume()
            }
            ACTION_TERMINATE -> {
                performCompleteShutdown()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun togglePauseResume() {
        val app = application as? KodaWintabApp ?: return
        isPaused = !isPaused
        if (isPaused) {
            runCatching { app.pipelineMesa1.stop() }
            runCatching { app.pipelineMesa2.stop() }
            runCatching { app.cursorOverlayManager.setPaused(true) }
            runCatching { app.hubManager.stopCoopMonitor() }
            runCatching { app.stopGlobalPointerStream() }
        } else {
            runCatching { app.pipelineMesa1.start() }
            runCatching { app.cursorOverlayManager.setPaused(false) }
            runCatching { app.hubManager.startCoopMonitor(app.coopMonitorScope) }
            runCatching { app.startGlobalPointerStream() }
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(paused = isPaused))
    }

    private fun performCompleteShutdown() {
        // Force-stop ONLY the app UI/service/pipelines while keeping permanent ADB Shell Server & permissions untouched!
        setUserTerminatedFlag(this, true)
        val app = application as? KodaWintabApp
        runCatching { app?.stopGlobalPointerStream() }
        runCatching { app?.pipelineMesa1?.stop() }
        runCatching { app?.pipelineMesa2?.stop() }
        runCatching { app?.usbCoordinator?.unregisterReceiver() }
        runCatching { app?.hubManager?.stopCoopMonitor() }
        runCatching { app?.penDisplayManager?.stopPenDisplaySession() }
        runCatching { app?.cursorOverlayManager?.destroy() }
        // Close ONLY local TCP socket — never send CMD_SHUTDOWN so KodaShellServer stays ready forever
        runCatching { app?.engineClient?.closeConnection() }
        runCatching {
            sendBroadcast(Intent(ACTION_APP_TERMINATED).setPackage(packageName))
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
        serviceScope.cancel()
        stopSelf()

        // Ensure complete termination of any background app threads until user opens the app again
        val isRobolectric = Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
        if (!isRobolectric) {
            mainHandler.postDelayed({ exitProcess(0) }, 140L)
        }
    }

    override fun onDestroy() {
        val app = application as? KodaWintabApp
        runCatching { app?.stopGlobalPointerStream() }
        runCatching { app?.pipelineMesa1?.stop() }
        runCatching { app?.pipelineMesa2?.stop() }
        runCatching { app?.usbCoordinator?.unregisterReceiver() }
        runCatching { app?.hubManager?.stopCoopMonitor() }
        runCatching { app?.penDisplayManager?.stopPenDisplaySession() }
        runCatching { app?.cursorOverlayManager?.destroy() }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_hub_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_hub_desc)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(paused: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, KodaHubForegroundService::class.java).setAction(ACTION_PAUSE_RESUME),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val terminateIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, KodaHubForegroundService::class.java).setAction(ACTION_TERMINATE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pauseLabel = if (paused) "Retomar" else "Pausar"
        val terminateRedLabel = SpannableString("Encerrar").apply {
            setSpan(
                ForegroundColorSpan(Color.parseColor("#FF3B5C")),
                0,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        val title = if (paused) "Koda Wintab · Pausado" else "Koda Wintab · Hub Ativo"
        val body = if (paused) {
            "Driver em pausa. Toque em Retomar para voltar ou Encerrar para fechar tudo."
        } else {
            "Driver USB de alta precisão e cursor global ativos"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, pauseLabel, pauseIntent)
            .addAction(0, terminateRedLabel, terminateIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "koda_hub_channel"
        const val NOTIFICATION_ID = 2026
        const val ACTION_STOP = "com.aistudio.kodawintab.STOP_HUB_SERVICE"
        const val ACTION_PAUSE_RESUME = "com.aistudio.kodawintab.PAUSE_RESUME_HUB_SERVICE"
        const val ACTION_TERMINATE = "com.aistudio.kodawintab.TERMINATE_ALL"
        const val ACTION_APP_TERMINATED = "com.aistudio.kodawintab.APP_TERMINATED"
        private const val PREFS_NAME = "koda_service_prefs"
        private const val KEY_USER_TERMINATED = "user_terminated"

        fun setUserTerminatedFlag(context: Context, terminated: Boolean) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_USER_TERMINATED, terminated)
                .apply()
        }

        fun wasUserTerminated(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_USER_TERMINATED, false)
        }

        fun start(context: Context) {
            setUserTerminatedFlag(context, false)
            val intent = Intent(context, KodaHubForegroundService::class.java)
            context.startForegroundService(intent)
        }
    }
}

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            !KodaHubForegroundService.wasUserTerminated(context)
        ) {
            runCatching { KodaHubForegroundService.start(context) }
        }
    }
}
