package dev.sn.app.tools

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dev.sn.core.BaseTool
import dev.sn.core.ToolException
import dev.sn.core.schema
import dev.sn.core.stringOr
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Shared worker for camera and location callbacks.
 *
 * These APIs all take an Executor; creating one per call spawned a thread that
 * was never shut down, so a handful of photos leaked a handful of threads.
 */
private val callbackExecutor: Executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "sn-sensing").apply { isDaemon = true }
}

class LocationTool(private val context: Context) : BaseTool(
    name = "location_get",
    description = "Get the phone's current location as latitude and longitude. Prefer 'last', " +
        "which is instant; use 'fresh' only when an up-to-date fix genuinely matters.",
    parameters = schema {
        string("accuracy", "'last' returns the last known fix; 'fresh' waits for a new one.",
            enum = listOf("last", "fresh"))
    },
    category = "sensing",
) {
    override suspend fun call(arguments: JsonObject): JsonElement {
        context.requirePermission(Manifest.permission.ACCESS_FINE_LOCATION, "read your location")
        val manager = context.getSystemService(LocationManager::class.java)
            ?: throw ToolException("Location service unavailable.")

        val fresh = arguments.stringOr("accuracy", "last") == "fresh"
        val location = if (fresh) currentFix(manager) else lastFix(manager) ?: currentFix(manager)

        location ?: throw ToolException(
            "No location fix available. Check that Location is switched on in quick settings.",
        )

        return ok(
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "accuracy_metres" to location.accuracy.toDouble(),
            "altitude_metres" to location.altitude,
            "provider" to location.provider,
            "fixed_at" to formatTime(location.time),
            "age_seconds" to (System.currentTimeMillis() - location.time) / 1000,
        )
    }

    @Suppress("MissingPermission")
    private fun lastFix(manager: LocationManager): Location? =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .asSequence()
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

    @Suppress("MissingPermission")
    private suspend fun currentFix(manager: LocationManager): Location? {
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> throw ToolException("Location is switched off on the phone.")
        }

        // A GPS fix can take a while outdoors and never arrive indoors, so the
        // wait is bounded rather than open ended.
        return withTimeoutOrNull(45_000) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                manager.getCurrentLocation(
                    provider,
                    signal,
                    callbackExecutor,
                ) { location -> if (continuation.isActive) continuation.resume(location) }
            }
        }
    }
}

class CameraInfoTool(private val context: Context) : BaseTool(
    name = "camera_info",
    description = "List the phone's cameras and which way they face, for use with camera_photo.",
    category = "sensing",
) {
    override suspend fun call(arguments: JsonObject): JsonElement {
        val provider = cameraProvider(context)
        val cameras = buildList {
            if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) add("back")
            if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) add("front")
        }
        return listResult("cameras", cameras.map { ok("facing" to it) })
    }
}

class CameraPhotoTool(private val context: Context) : BaseTool(
    name = "camera_photo",
    description = "Take a photo with the phone's camera and save it on the device. " +
        "Returns the file path. The user confirms before the shutter fires.",
    parameters = schema {
        string("camera", "Which camera to use.", enum = listOf("back", "front"))
    },
    category = "sensing",
    consequential = true,
) {
    override suspend fun call(arguments: JsonObject): JsonElement {
        context.requirePermission(Manifest.permission.CAMERA, "use the camera")

        val facing = arguments.stringOr("camera", "back")
        val selector = if (facing == "front") {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val directory = File(context.getExternalFilesDir(null), "photos").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val target = File(directory, "sn-$stamp.jpg")

        withContext(Dispatchers.Main) {
            val provider = cameraProvider(context)
            val capture = ImageCapture.Builder().build()
            // CameraX binds to a lifecycle. There is no UI here, so a minimal
            // owner is driven to RESUMED for the duration of the shot and torn
            // down afterwards — otherwise the camera stays held open.
            val owner = TransientLifecycleOwner()

            try {
                provider.unbindAll()
                owner.start()
                provider.bindToLifecycle(owner, selector, capture)
                capture.takePictureTo(target)
            } catch (e: IllegalArgumentException) {
                throw ToolException("No $facing camera available on this phone.")
            } finally {
                provider.unbindAll()
                owner.stop()
            }
        }

        if (!target.exists() || target.length() == 0L) {
            throw ToolException(
                "The camera reported success but no image was written. Another app may be " +
                    "holding the camera — close it and try again.",
            )
        }
        return ok("saved" to target.absolutePath, "bytes" to target.length(), "camera" to facing)
    }
}

private suspend fun cameraProvider(context: Context): ProcessCameraProvider =
    withContext(Dispatchers.Main) {
        val future = ProcessCameraProvider.getInstance(context)
        suspendCancellableCoroutine { continuation ->
            future.addListener(
                {
                    runCatching { future.get() }
                        .onSuccess { continuation.resume(it) }
                        .onFailure {
                            continuation.resumeWithException(
                                ToolException("Camera unavailable: ${it.message}"),
                            )
                        }
                },
                callbackExecutor,
            )
        }
    }

private suspend fun ImageCapture.takePictureTo(target: File) =
    suspendCancellableCoroutine { continuation ->
        val options = ImageCapture.OutputFileOptions.Builder(target).build()
        takePicture(
            options,
            callbackExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onError(exception: ImageCaptureException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            ToolException("Could not take the photo: ${exception.message}"),
                        )
                    }
                }
            },
        )
    }

/** A lifecycle owner that exists only for the duration of one capture. */
private class TransientLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    fun start() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
