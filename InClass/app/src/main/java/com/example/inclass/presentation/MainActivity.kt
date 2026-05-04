package com.example.inclass.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import androidx.wear.compose.ui.tooling.preview.WearPreviewFontScales
import com.example.inclass.presentation.theme.InClassTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

private const val PREFS_NAME = "health_measure_prefs"
private const val KEY_LAST_DURATION_SECONDS = "lastDurationSeconds"
private const val KEY_LAST_CALORIES = "lastCalories"
private const val KEY_LAST_DISTANCE = "lastDistance"
private const val KEY_COMPLETED_SESSIONS = "completedSessions"
private const val READ_HEART_RATE_PERMISSION = "android.permission.health.READ_HEART_RATE"

private val BackgroundColor = Color(0xFF050505)
private val PrimaryOrange = Color(0xFFFF9800)
private val MutedTextColor = Color(0xFFBDBDBD)
private val DisabledButtonColor = Color(0xFF2A2A2A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WearApp()
        }
    }
}

@Composable
fun WearApp() {
    InClassTheme {
        HealthMeasureScreen()
    }
}

@Composable
private fun HealthMeasureScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var heartRate by remember { mutableIntStateOf(0) }
    var calories by remember { mutableStateOf(0.0) }
    var distanceKm by remember { mutableStateOf(0.0) }
    var completedSessions by remember {
        mutableIntStateOf(prefs.getInt(KEY_COMPLETED_SESSIONS, 0))
    }
    var workoutStatus by remember { mutableStateOf(WorkoutStatus.Idle) }
    var usesHealthServices by remember { mutableStateOf(false) }
    var hasHealthPermissions by remember {
        mutableStateOf(context.hasRequiredHealthPermissions())
    }
    var pendingStartAfterPermission by remember { mutableStateOf(false) }

    val healthServicesManager = remember(context) {
        HealthServicesWorkoutManager(
            context = context,
            onMetrics = { update ->
                update.elapsedSeconds?.let { healthSeconds ->
                    if (healthSeconds > elapsedSeconds) {
                        elapsedSeconds = healthSeconds
                    }
                }
                update.heartRate?.let { heartRate = it }
                update.calories?.let { calories = it }
                update.distanceKm?.let { distanceKm = it }
            },
            onStatusChanged = { status ->
                workoutStatus = status
                if (status == WorkoutStatus.Idle) {
                    usesHealthServices = false
                }
            },
        )
    }
    val isRunning = workoutStatus == WorkoutStatus.Running

    fun resetCurrentWorkout() {
        elapsedSeconds = 0
        heartRate = 0
        calories = 0.0
        distanceKm = 0.0
        usesHealthServices = false
        workoutStatus = WorkoutStatus.Idle
    }

    fun startFallbackWorkout() {
        usesHealthServices = false
        workoutStatus = WorkoutStatus.Running
        if (heartRate == 0) {
            heartRate = nextHeartRate(elapsedSeconds, heartRate)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grantResults ->
        hasHealthPermissions = requiredHealthPermissions().all { permission ->
            grantResults[permission] == true ||
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }

        if (pendingStartAfterPermission && !hasHealthPermissions) {
            pendingStartAfterPermission = false
            startFallbackWorkout()
        }
    }

    fun startWorkout() {
        if (workoutStatus == WorkoutStatus.Running) return

        val fromPaused = workoutStatus == WorkoutStatus.Paused
        if (!hasHealthPermissions) {
            pendingStartAfterPermission = true
            permissionLauncher.launch(requiredHealthPermissions())
            return
        }

        coroutineScope.launch {
            val healthServicesStarted = healthServicesManager.startOrResume(fromPaused)
            if (healthServicesStarted) {
                usesHealthServices = true
                workoutStatus = WorkoutStatus.Running
            } else {
                startFallbackWorkout()
            }
        }
    }

    DisposableEffect(healthServicesManager) {
        onDispose {
            healthServicesManager.dispose()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasHealthPermissions) {
            permissionLauncher.launch(requiredHealthPermissions())
        }
    }

    LaunchedEffect(pendingStartAfterPermission, hasHealthPermissions) {
        if (pendingStartAfterPermission && hasHealthPermissions) {
            pendingStartAfterPermission = false
            startWorkout()
        }
    }

    LaunchedEffect(isRunning, usesHealthServices) {
        while (isRunning) {
            delay(1_000)
            elapsedSeconds += 1

            if (!usesHealthServices) {
                calories = elapsedSeconds * 0.08
                distanceKm = elapsedSeconds * 0.002
            }

            if (!usesHealthServices && (heartRate == 0 || elapsedSeconds % 2 == 0)) {
                heartRate = nextHeartRate(elapsedSeconds, heartRate)
            }
        }
    }

    HealthMeasureContent(
        elapsedSeconds = elapsedSeconds,
        heartRate = heartRate,
        calories = calories,
        distanceKm = distanceKm,
        completedSessions = completedSessions,
        isStartEnabled = workoutStatus != WorkoutStatus.Running,
        pauseResetText = if (workoutStatus == WorkoutStatus.Paused) "RESET" else "PAUSE",
        isPauseResetEnabled = workoutStatus == WorkoutStatus.Running ||
            (workoutStatus == WorkoutStatus.Paused && elapsedSeconds > 0),
        isEndEnabled = elapsedSeconds > 0,
        onStart = {
            startWorkout()
        },
        onPause = {
            if (workoutStatus == WorkoutStatus.Running) {
                if (usesHealthServices) {
                    coroutineScope.launch {
                        healthServicesManager.pause()
                    }
                }
                workoutStatus = WorkoutStatus.Paused
            }
        },
        onReset = {
            if (usesHealthServices) {
                coroutineScope.launch {
                    healthServicesManager.end()
                }
            }
            resetCurrentWorkout()
        },
        onEnd = {
            if (elapsedSeconds > 0) {
                val nextSessionCount = completedSessions + 1

                prefs.edit()
                    .putInt(KEY_LAST_DURATION_SECONDS, elapsedSeconds)
                    .putInt(KEY_LAST_CALORIES, calories.roundToInt())
                    .putFloat(KEY_LAST_DISTANCE, distanceKm.toFloat())
                    .putInt(KEY_COMPLETED_SESSIONS, nextSessionCount)
                    .apply()

                completedSessions = nextSessionCount
            }

            if (usesHealthServices) {
                coroutineScope.launch {
                    healthServicesManager.end()
                }
            }
            resetCurrentWorkout()
        },
    )
}

@Composable
private fun HealthMeasureContent(
    elapsedSeconds: Int,
    heartRate: Int,
    calories: Double,
    distanceKm: Double,
    completedSessions: Int,
    isStartEnabled: Boolean,
    pauseResetText: String,
    isPauseResetEnabled: Boolean,
    isEndEnabled: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onReset: () -> Unit,
    onEnd: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundColor)
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Health Measure",
            color = MutedTextColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = formatTimer(elapsedSeconds),
            color = Color.White,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(8.dp))

        HealthMetricLine(label = "Heart", value = formatHeartRate(heartRate))
        HealthMetricLine(label = "Calories", value = formatCalories(calories))
        HealthMetricLine(label = "Distance", value = formatDistance(distanceKm))
        HealthMetricLine(label = "Sessions", value = completedSessions.toString())

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HealthActionButton(
                text = "START",
                enabled = isStartEnabled,
                onClick = onStart,
            )
            HealthActionButton(
                text = pauseResetText,
                enabled = isPauseResetEnabled,
                onClick = {
                    if (pauseResetText == "RESET") {
                        onReset()
                    } else {
                        onPause()
                    }
                },
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        HealthActionButton(
            text = "END",
            enabled = isEndEnabled,
            onClick = onEnd,
        )
    }
}

@Composable
private fun HealthMetricLine(label: String, value: String) {
    Row(
        modifier = Modifier.width(142.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label:",
            color = Color.White,
            fontSize = 13.sp,
            maxLines = 1,
        )
        Text(
            text = value,
            color = PrimaryOrange,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun HealthActionButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    CompactButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.width(70.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = PrimaryOrange,
            contentColor = Color.Black,
            disabledContainerColor = DisabledButtonColor,
            disabledContentColor = MutedTextColor,
        ),
        label = {
            Text(
                text = text,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        },
    )
}

private fun nextHeartRate(elapsedSeconds: Int, currentHeartRate: Int): Int {
    val warmupBoost = (elapsedSeconds / 8).coerceAtMost(25)
    val target = 82 + warmupBoost + Random.nextInt(-8, 18)
    val smoothed = if (currentHeartRate == 0) {
        target
    } else {
        (currentHeartRate * 2 + target) / 3
    }

    return smoothed.coerceIn(70, 150)
}

private fun formatTimer(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

private fun formatHeartRate(heartRate: Int): String =
    if (heartRate > 0) {
        "$heartRate bpm"
    } else {
        "-- bpm"
    }

private fun formatCalories(calories: Double): String =
    "${calories.roundToInt()} cal"

private fun formatDistance(distanceKm: Double): String =
    String.format(Locale.US, "%.2f km", distanceKm)

private fun Context.hasRequiredHealthPermissions(): Boolean =
    requiredHealthPermissions().all { permission ->
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

private fun requiredHealthPermissions(): Array<String> =
    buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        if (Build.VERSION.SDK_INT >= 36) {
            add(READ_HEART_RATE_PERMISSION)
        } else {
            add(Manifest.permission.BODY_SENSORS)
        }
    }.toTypedArray()

@WearPreviewDevices
@WearPreviewFontScales
@Composable
fun DefaultPreview() {
    WearApp()
}

// TODO: Move ExerciseClient ownership into a foreground service for production workouts.
