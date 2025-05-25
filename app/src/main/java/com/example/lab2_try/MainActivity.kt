package com.example.lab2_try

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.lab2_try.ui.theme.Lab2_tryTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var stopwatchService: StopwatchService? = null
    private var isBound by mutableStateOf(false)

    private val _timeDisplay = mutableStateOf("00:00:00.00")

    private val _stopwatchState = mutableStateOf(StopwatchService.StopwatchState.IDLE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as StopwatchService.LocalBinder
            stopwatchService = binder.getService()
            isBound = true
            Log.d("MainActivity", "Service connected")

            lifecycleScope.launch {
                stopwatchService?.timeMillis?.collect { millis ->
                }
            }
            lifecycleScope.launch {
                stopwatchService?.serviceState?.collect { state ->
                    if (state != StopwatchService.StopwatchState.IDLE && stopwatchService?.isServiceInForeground == true) {
                        stopwatchService?.stopForegroundService()
                        Log.d("MainActivity", "Service taken out of foreground as Activity became visible")
                    }
                }
            }

            if (stopwatchService?.isServiceInForeground == true) {
                stopwatchService?.stopForegroundService()
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            Log.d("MainActivity", "Service disconnected")
            isBound = false
            stopwatchService = null

            _timeDisplay.value = "00:00:00.00"
            _stopwatchState.value = StopwatchService.StopwatchState.IDLE
        }
    }


    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d("MainActivity", "Notification permission GRANTED")

                if (stopwatchService?.serviceState?.value != StopwatchService.StopwatchState.IDLE && isBound) {
                    stopwatchService?.startForegroundService()
                }
            } else {
                Log.d("MainActivity", "Notification permission DENIED")
                Toast.makeText(this, "Notification permission denied. Stopwatch may not work correctly in background.", Toast.LENGTH_LONG).show()
            }
        }

    private fun tryStartForegroundServiceWithPermissionCheck() {
        if (stopwatchService == null || !isBound) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d("MainActivity", "Notification permission already granted. Starting foreground service.")
                    stopwatchService?.startForegroundService()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    // TODO: Показати пояснення користувачу, чому потрібен дозвіл (наприклад, у діалозі)
                    Log.d("MainActivity", "Showing rationale for notification permission.")
                    Toast.makeText(this, "Please grant notification permission to show stopwatch progress in background.", Toast.LENGTH_LONG).show()
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    Log.d("MainActivity", "Requesting notification permission.")
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {

            Log.d("MainActivity", "Pre-Tiramisu. Starting foreground service directly.")
            stopwatchService?.startForegroundService()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.d("MainActivity", "onCreate")

        setContent {
            Lab2_tryTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->

                    val timeDisplayFromService by stopwatchService?.timeMillis?.collectAsStateWithLifecycle(initialValue = 0L) ?: remember { mutableStateOf(0L) }
                    val serviceStateFromService by stopwatchService?.serviceState?.collectAsStateWithLifecycle(initialValue = StopwatchService.StopwatchState.IDLE) ?: remember { mutableStateOf(StopwatchService.StopwatchState.IDLE) }

                    StopwatchScreen(
                        modifier = Modifier.padding(innerPadding),
                        timeDisplay = stopwatchService?.formatTime(timeDisplayFromService) ?: "00:00:00.00",
                        serviceState = serviceStateFromService,
                        onStartClick = {
                            Log.d("MainActivity", "Start clicked")

                            Intent(this, StopwatchService::class.java).also { intent ->
                                startService(intent)
                            }
                            stopwatchService?.startStopwatch()
                        },
                        onPauseClick = {
                            Log.d("MainActivity", "Pause clicked")
                            stopwatchService?.pauseStopwatch()
                        },
                        onResetClick = {
                            Log.d("MainActivity", "Reset clicked")
                            stopwatchService?.resetStopwatch()
                        },
                        isServiceBound = isBound
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d("MainActivity", "onStart - Binding to service")
        Intent(this, StopwatchService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d("MainActivity", "onStop")
        if (isBound) {
            val currentServiceState = stopwatchService?.serviceState?.value

            if (currentServiceState != null && currentServiceState != StopwatchService.StopwatchState.IDLE) {
                Log.d("MainActivity", "Activity stopping, timer active. Attempting to start foreground service.")
                tryStartForegroundServiceWithPermissionCheck()
            }
            Log.d("MainActivity", "Unbinding from service in onStop")
            unbindService(connection)
            isBound = false
        }
    }

    override fun onDestroy() {
        Log.d("MainActivity", "onDestroy")

        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        super.onDestroy()
    }
}

@Composable
fun StopwatchScreen(
    modifier: Modifier = Modifier,
    timeDisplay: String,
    serviceState: StopwatchService.StopwatchState,
    onStartClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResetClick: () -> Unit,
    isServiceBound: Boolean
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = timeDisplay,
            fontSize = 48.sp,
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = {
                    if (serviceState == StopwatchService.StopwatchState.RUNNING) {
                        onPauseClick()
                    } else {
                        onStartClick()
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = isServiceBound
            ) {
                Text(
                    when (serviceState) {
                        StopwatchService.StopwatchState.RUNNING -> "Пауза"
                        StopwatchService.StopwatchState.PAUSED -> "Продовжити"
                        StopwatchService.StopwatchState.IDLE -> "Старт"
                    }
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Button(
                onClick = { onResetClick() },
                modifier = Modifier.weight(1f),
                enabled = isServiceBound && serviceState != StopwatchService.StopwatchState.IDLE
            ) {
                Text("Скинути")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun StopwatchScreenPreview() {
    Lab2_tryTheme {
        StopwatchScreen(
            timeDisplay = "00:10:30.50",
            serviceState = StopwatchService.StopwatchState.RUNNING,
            onStartClick = {},
            onPauseClick = {},
            onResetClick = {},
            isServiceBound = true
        )
    }
}