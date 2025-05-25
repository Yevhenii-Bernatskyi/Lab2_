package com.example.lab2_try

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit

class StopwatchService : Service() {

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private var startTime = 0L
    private var elapsedTime = 0L
    private var isRunning = false

    private val _timeMillis = MutableStateFlow(0L)
    val timeMillis: StateFlow<Long> = _timeMillis

    private val _serviceState = MutableStateFlow(StopwatchState.IDLE)
    val serviceState: StateFlow<StopwatchState> = _serviceState


    private lateinit var notificationManager: NotificationManager
    private var isForeground = false
    val isServiceInForeground: Boolean
        get() = isForeground


    companion object {
        const val ACTION_START = "com.example.lab2_try.START"
        const val ACTION_PAUSE = "com.example.lab2_try.PAUSE"
        const val ACTION_RESET = "com.example.lab2_try.RESET"

        private const val NOTIFICATION_CHANNEL_ID = "stopwatch_channel"
        private const val NOTIFICATION_CHANNEL_NAME = "Stopwatch Service"
        private const val NOTIFICATION_ID = 1
    }


    private val runnable = object : Runnable {
        private var lastNotificationUpdateTime = 0L
        private val NOTIFICATION_UPDATE_INTERVAL = 100L

        override fun run() {
            if (isRunning) {
                val currentTime = System.currentTimeMillis()
                val currentTotalTime = elapsedTime + (currentTime - startTime)
                _timeMillis.value = currentTotalTime

                if (isForeground) {

                    if (currentTime - lastNotificationUpdateTime >= NOTIFICATION_UPDATE_INTERVAL) {
                        updateNotification(formatTime(currentTotalTime))
                        lastNotificationUpdateTime = currentTime
                    }
                }
                handler.postDelayed(this, 50)
            }
        }
    }

    enum class StopwatchState {
        IDLE,
        RUNNING,
        PAUSED
    }

    override fun onCreate() {
        super.onCreate()

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_START -> {
                    startStopwatch()

                    if (isForeground) {
                        updateNotification(formatTime(_timeMillis.value))
                    }
                }
                ACTION_PAUSE -> {
                    pauseStopwatch()

                }
                ACTION_RESET -> {
                    resetStopwatch()
                    stopSelf()

                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        return super.onUnbind(intent)
    }


    inner class LocalBinder : Binder() {
        fun getService(): StopwatchService = this@StopwatchService
    }

    fun startStopwatch() {
        if (!isRunning) {
            startTime = System.currentTimeMillis()
            isRunning = true
            _serviceState.value = StopwatchState.RUNNING
            handler.post(runnable)
            if (!isForeground) {
                startForegroundService()
                Log.d("StopwatchService", "Foreground service started from startStopwatch")
            }
        }
    }

    fun pauseStopwatch() {
        if (isRunning) {
            handler.removeCallbacks(runnable)
            elapsedTime += System.currentTimeMillis() - startTime
            isRunning = false
            _serviceState.value = StopwatchState.PAUSED
            if (!isForeground) {
                startForegroundService()
            }
            updateNotification("Paused: ${formatTime(_timeMillis.value)}")
        }
    }

    fun resetStopwatch() {
        handler.removeCallbacks(runnable)
        isRunning = false
        startTime = 0L
        elapsedTime = 0L
        _timeMillis.value = 0L
        _serviceState.value = StopwatchState.IDLE
        stopForegroundService()
    }

    fun formatTime(timeMillis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(timeMillis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(timeMillis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(timeMillis) % 60
        val milliseconds = (timeMillis % 1000) / 10
        return String.format("%02d:%02d:%02d.%02d", hours, minutes, seconds, milliseconds)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Channel for Stopwatch Foreground Service"
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): NotificationCompat.Builder {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        val pauseResumeAction = if (isRunning) {
            val pauseIntent = Intent(this, StopwatchService::class.java).apply { action = ACTION_PAUSE }
            val pausePendingIntent = PendingIntent.getService(this, 1, pauseIntent, pendingIntentFlags)
            NotificationCompat.Action(R.drawable.ic_pause, "Pause", pausePendingIntent)
        } else {
            val resumeIntent = Intent(this, StopwatchService::class.java).apply { action = ACTION_START }
            val resumePendingIntent = PendingIntent.getService(this, 2, resumeIntent, pendingIntentFlags)
            NotificationCompat.Action(R.drawable.ic_play, "Resume", resumePendingIntent)
        }

        val resetIntent = Intent(this, StopwatchService::class.java).apply { action = ACTION_RESET }
        val resetPendingIntent = PendingIntent.getService(this, 3, resetIntent, pendingIntentFlags)
        val resetAction = NotificationCompat.Action(R.drawable.ic_stop, "Reset", resetPendingIntent)


        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Stopwatch Active")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentIntent(pendingIntent)
            .addAction(pauseResumeAction)
            .addAction(resetAction)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    fun startForegroundService() {
        if (!isForeground) {
            val notification = buildNotification("Stopwatch is running...").build()
            startForeground(NOTIFICATION_ID, notification)
            isForeground = true
        }
    }

    private fun updateNotification(contentText: String) {
        if (isForeground) {
            val notification = buildNotification(contentText).build()
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    fun stopForegroundService() {
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(runnable)
        stopForegroundService()
    }
}