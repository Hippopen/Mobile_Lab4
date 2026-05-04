package com.example.inclass.presentation

import android.content.Context
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal enum class WorkoutStatus {
    Idle,
    Running,
    Paused,
}

internal data class HealthMetricsUpdate(
    val elapsedSeconds: Int? = null,
    val heartRate: Int? = null,
    val calories: Double? = null,
    val distanceKm: Double? = null,
)

internal class HealthServicesWorkoutManager(
    context: Context,
    private val onMetrics: (HealthMetricsUpdate) -> Unit,
    private val onStatusChanged: (WorkoutStatus) -> Unit,
) {
    private val appContext = context.applicationContext
    private val exerciseClient = HealthServices.getClient(appContext).exerciseClient

    private var updateCallback: ExerciseUpdateCallback? = null
    private var healthExerciseActive = false
    private var accumulatedCalories = 0.0
    private var accumulatedDistanceMeters = 0.0

    suspend fun startOrResume(fromPaused: Boolean): Boolean {
        if (fromPaused && healthExerciseActive) {
            return runCatching {
                exerciseClient.resumeExerciseAsync().awaitResult()
                onStatusChanged(WorkoutStatus.Running)
            }.isSuccess
        }

        return runCatching {
            accumulatedCalories = 0.0
            accumulatedDistanceMeters = 0.0
            exerciseClient.setUpdateCallback(appContext.mainExecutor, callback())
            exerciseClient.startExerciseAsync(createExerciseConfig()).awaitResult()
            healthExerciseActive = true
            onStatusChanged(WorkoutStatus.Running)
        }.onFailure {
            healthExerciseActive = false
            updateCallback?.let { callback ->
                exerciseClient.clearUpdateCallbackAsync(callback)
            }
            updateCallback = null
        }.isSuccess
    }

    suspend fun pause(): Boolean =
        runCatching {
            exerciseClient.pauseExerciseAsync().awaitResult()
            onStatusChanged(WorkoutStatus.Paused)
        }.isSuccess

    suspend fun end(): Boolean =
        runCatching {
            if (healthExerciseActive) {
                exerciseClient.endExerciseAsync().awaitResult()
            }
            clearCallback()
            healthExerciseActive = false
        }.isSuccess

    fun dispose() {
        if (healthExerciseActive) {
            exerciseClient.endExerciseAsync()
        }
        updateCallback?.let { exerciseClient.clearUpdateCallbackAsync(it) }
        updateCallback = null
        healthExerciseActive = false
    }

    private suspend fun createExerciseConfig(): ExerciseConfig {
        val capabilities = exerciseClient.getCapabilitiesAsync().awaitResult()
        val exerciseType = when {
            ExerciseType.RUNNING in capabilities.supportedExerciseTypes -> ExerciseType.RUNNING
            ExerciseType.WALKING in capabilities.supportedExerciseTypes -> ExerciseType.WALKING
            else -> error("No supported running or walking exercise type")
        }

        val supportedDataTypes =
            capabilities.getExerciseTypeCapabilities(exerciseType).supportedDataTypes
        val requestedDataTypes = buildSet<DataType<*, *>> {
            addIfSupported(DataType.HEART_RATE_BPM, supportedDataTypes)
            addIfSupported(DataType.CALORIES_TOTAL, supportedDataTypes)
            addIfSupported(DataType.CALORIES, supportedDataTypes)
            addIfSupported(DataType.DISTANCE_TOTAL, supportedDataTypes)
            addIfSupported(DataType.DISTANCE, supportedDataTypes)
        }

        if (requestedDataTypes.isEmpty()) {
            error("No supported Health Services data types")
        }

        return ExerciseConfig.builder(exerciseType)
            .setDataTypes(requestedDataTypes)
            .setIsAutoPauseAndResumeEnabled(false)
            .setIsGpsEnabled(false)
            .build()
    }

    private fun MutableSet<DataType<*, *>>.addIfSupported(
        dataType: DataType<*, *>,
        supportedDataTypes: Set<DataType<*, *>>,
    ) {
        if (dataType in supportedDataTypes) {
            add(dataType)
        }
    }

    private suspend fun clearCallback() {
        updateCallback?.let {
            exerciseClient.clearUpdateCallbackAsync(it).awaitResult()
        }
        updateCallback = null
    }

    private fun callback(): ExerciseUpdateCallback {
        updateCallback?.let { return it }

        return object : ExerciseUpdateCallback {
            override fun onRegistered() = Unit

            override fun onRegistrationFailed(throwable: Throwable) {
                healthExerciseActive = false
            }

            override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
                updateWorkoutStatus(update.exerciseStateInfo.state)
                updateMetrics(update)
            }

            override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit

            override fun onAvailabilityChanged(
                dataType: DataType<*, *>,
                availability: Availability,
            ) = Unit
        }.also {
            updateCallback = it
        }
    }

    private fun updateWorkoutStatus(state: ExerciseState) {
        when {
            state == ExerciseState.ACTIVE || state.isResuming -> {
                onStatusChanged(WorkoutStatus.Running)
            }
            state.isPaused -> {
                onStatusChanged(WorkoutStatus.Paused)
            }
            state.isEnding || state.isEnded -> {
                healthExerciseActive = false
                onStatusChanged(WorkoutStatus.Idle)
            }
        }
    }

    private fun updateMetrics(update: ExerciseUpdate) {
        val latestMetrics = update.latestMetrics
        val heartRate = latestMetrics.latestHeartRate()
        val calories = latestMetrics.latestCalories()
        val distanceKm = latestMetrics.latestDistanceKm()
        val elapsedSeconds =
            update.activeDurationCheckpoint?.activeDuration?.toSeconds()?.toInt()

        onMetrics(
            HealthMetricsUpdate(
                elapsedSeconds = elapsedSeconds,
                heartRate = heartRate,
                calories = calories,
                distanceKm = distanceKm,
            ),
        )
    }

    private fun DataPointContainer.latestHeartRate(): Int? =
        runCatching {
            getData(DataType.HEART_RATE_BPM)
                .lastOrNull()
                ?.value
                ?.roundToInt()
                ?.coerceIn(0, 250)
        }.getOrNull()

    private fun DataPointContainer.latestCalories(): Double {
        val totalCalories = runCatching {
            getData(DataType.CALORIES_TOTAL)?.total
        }.getOrNull()

        if (totalCalories != null) {
            accumulatedCalories = totalCalories
            return accumulatedCalories
        }

        val calorieDelta = runCatching {
            getData(DataType.CALORIES).sumOf { it.value }
        }.getOrDefault(0.0)

        accumulatedCalories += calorieDelta
        return accumulatedCalories
    }

    private fun DataPointContainer.latestDistanceKm(): Double {
        val totalDistanceMeters = runCatching {
            getData(DataType.DISTANCE_TOTAL)?.total
        }.getOrNull()

        if (totalDistanceMeters != null) {
            accumulatedDistanceMeters = totalDistanceMeters
            return accumulatedDistanceMeters / 1_000.0
        }

        val distanceDeltaMeters = runCatching {
            getData(DataType.DISTANCE).sumOf { it.value }
        }.getOrDefault(0.0)

        accumulatedDistanceMeters += distanceDeltaMeters
        return accumulatedDistanceMeters / 1_000.0
    }
}

private suspend fun <T> ListenableFuture<T>.awaitResult(): T =
    withContext(Dispatchers.IO) {
        get()
    }
