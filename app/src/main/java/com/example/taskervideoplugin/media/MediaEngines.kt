package com.example.taskervideoplugin.media

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Size
import android.view.Surface
import com.example.taskervideoplugin.R
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max

object FileHelper {
    fun file(path: String?, name: String?, format: String?): File {
        val requestedPath = path?.trim()?.takeIf(String::isNotEmpty) ?: "Tasker/recordings"
        val dir = File(requestedPath).let {
            if (it.isAbsolute) it else File(Environment.getExternalStorageDirectory(), requestedPath.trim('/'))
        }
        check(dir.exists() || dir.mkdirs()) { "Could not create directory: ${dir.absolutePath}" }
        val extension = format?.trim()?.trimStart('.')?.lowercase()?.takeIf(String::isNotEmpty) ?: "mp4"
        val baseName = name?.trim()?.takeIf(String::isNotEmpty) ?: "capture"
        return File(dir, "$baseName.$extension")
    }

    fun existingFile(path: String?): File {
        require(!path.isNullOrBlank()) { "Video path is required" }
        val requested = File(path)
        val file = if (requested.isAbsolute) requested else File(Environment.getExternalStorageDirectory(), path.trim('/'))
        require(file.isFile && file.length() > 0) { "Video does not exist or is empty: ${file.absolutePath}" }
        return file
    }
}

data class Recording(
    val id: String,
    val file: File,
    val resolution: String,
    val started: Long = System.currentTimeMillis(),
    var paused: Boolean = false,
    var pausedAt: Long = 0,
    var pausedDuration: Long = 0
)

object CameraLensSelector {
    fun choose(context: Context, facing: String?): String {
        val manager = context.getSystemService(CameraManager::class.java)
        val wanted = if (facing.equals("front", true)) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        return manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wanted
        } ?: error("No ${facing ?: "back"} camera is available")
    }
}

object ResolutionSelector {
    private val resolutionPattern = Regex("^\\s*(\\d+)\\s*[xX×]\\s*(\\d+)")

    fun parseDimensions(value: String?): Pair<Int, Int>? {
        val match = value?.let(resolutionPattern::find) ?: return null
        val width = match.groupValues[1].toIntOrNull() ?: return null
        val height = match.groupValues[2].toIntOrNull() ?: return null
        return if (width > 0 && height > 0) width to height else null
    }

    fun parse(value: String?): Size? {
        val (width, height) = parseDimensions(value) ?: return null
        return Size(width, height)
    }

    fun closest(requested: Size?, available: Array<Size>): Size {
        require(available.isNotEmpty()) { "Camera reports no supported output sizes" }
        if (requested == null) return available.maxBy { it.width.toLong() * it.height }
        return available.minBy {
            abs(it.width.toLong() * it.height - requested.width.toLong() * requested.height) +
                abs(it.width - requested.width) * 1_000L + abs(it.height - requested.height) * 1_000L
        }
    }
}

private data class ActiveRecording(
    val info: Recording,
    val recorder: MediaRecorder,
    val camera: CameraDevice,
    val session: CameraCaptureSession,
    val context: Context
)

private data class PreparedRecorder(val recorder: MediaRecorder, val size: Size)

object CameraController {
    private const val CAMERA_TIMEOUT_SECONDS = 15L
    private val recordings = ConcurrentHashMap<String, ActiveRecording>()
    private val cameraThread = HandlerThread("TaskerCamera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    @Synchronized
    fun start(
        context: Context,
        id: String?,
        camera: String?,
        resolution: String?,
        path: String?,
        name: String?,
        format: String?
    ): Recording {
        requirePermissions(context, includeAudio = true)
        startCameraService(context, includeAudio = true)
        val recordingId = id?.trim()?.takeIf(String::isNotEmpty) ?: System.currentTimeMillis().toString()
        require(recordings[recordingId] == null) { "Recording $recordingId is already active" }

        val manager = context.getSystemService(CameraManager::class.java)
        val cameraId = CameraLensSelector.choose(context, camera)
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(MediaRecorder::class.java)
            ?: error("Camera does not support video recording")
        val output = FileHelper.file(path, name, format)
        val prepared = prepareRecorder(context, output, ResolutionSelector.parse(resolution), sizes)
        val selectedSize = prepared.size
        val recorder = prepared.recorder

        val cameraDevice = try {
            openCamera(manager, cameraId)
        } catch (error: Throwable) {
            recorder.release()
            throw error
        }
        val session = try {
            createRecordingSession(cameraDevice, recorder.surface)
        } catch (error: Throwable) {
            cameraDevice.close()
            recorder.release()
            throw error
        }

        try {
            recorder.start()
        } catch (error: Throwable) {
            session.close()
            cameraDevice.close()
            recorder.release()
            output.delete()
            throw IllegalStateException("Camera opened, but MediaRecorder could not start", error)
        }

        val info = Recording(recordingId, output, "${selectedSize.width}x${selectedSize.height}")
        recordings[recordingId] = ActiveRecording(info, recorder, cameraDevice, session, context.applicationContext)
        return info
    }

    @Synchronized
    @SuppressLint("NewApi")
    fun pause(id: String, stop: Boolean): Any {
        if (stop) return stop(id)
        val active = recordings[id] ?: error("Recording $id not found")
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) { "Pause requires Android 7.0 or newer" }
        if (!active.info.paused) {
            active.recorder.pause()
            active.info.paused = true
            active.info.pausedAt = System.currentTimeMillis()
        }
        return active.info
    }

    @Synchronized
    @SuppressLint("NewApi")
    fun resume(id: String): Recording {
        val active = recordings[id] ?: error("Recording $id not found")
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) { "Resume requires Android 7.0 or newer" }
        if (active.info.paused) {
            active.recorder.resume()
            active.info.pausedDuration += System.currentTimeMillis() - active.info.pausedAt
            active.info.paused = false
            active.info.pausedAt = 0
        }
        return active.info
    }

    @Synchronized
    fun stop(id: String): Pair<Recording, Long> {
        val active = recordings.remove(id) ?: error("Recording $id not found")
        if (active.info.paused) {
            active.info.pausedDuration += System.currentTimeMillis() - active.info.pausedAt
        }
        var stopError: Throwable? = null
        try {
            active.recorder.stop()
        } catch (error: Throwable) {
            stopError = error
        } finally {
            active.session.close()
            active.camera.close()
            active.recorder.reset()
            active.recorder.release()
        }
        if (recordings.isEmpty()) active.context.stopService(Intent(active.context, CameraRecordingService::class.java))
        check(active.info.file.length() > 0 && stopError == null) {
            active.info.file.delete()
            "Recording failed before any playable video was written${stopError?.message?.let { ": $it" } ?: ""}"
        }
        val elapsed = max(0, System.currentTimeMillis() - active.info.started - active.info.pausedDuration)
        return active.info to elapsed
    }

    @Synchronized
    fun photo(
        context: Context,
        camera: String?,
        path: String?,
        name: String?,
        format: String?,
        resolution: String?
    ): Pair<File, String> {
        requirePermissions(context, includeAudio = false)
        startCameraService(context, includeAudio = false)
        val manager = context.getSystemService(CameraManager::class.java)
        val cameraId = CameraLensSelector.choose(context, camera)
        val sizes = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)
            ?: error("Camera does not support JPEG capture")
        val selectedSize = ResolutionSelector.closest(ResolutionSelector.parse(resolution), sizes)
        val output = FileHelper.file(path, name, format ?: "jpg")
        val device = openCamera(manager, cameraId)
        try {
            capturePhoto(device, selectedSize, output)
        } finally {
            device.close()
            if (recordings.isEmpty()) context.stopService(Intent(context, CameraRecordingService::class.java))
        }
        check(output.length() > 0) { "Camera returned an empty photo" }
        return output to "${selectedSize.width}x${selectedSize.height}"
    }

    private fun createRecorder(context: Context, output: File, size: Size): MediaRecorder {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        try {
            val isThreeGp = output.extension.equals("3gp", true) || output.extension.equals("3gpp", true)
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(if (isThreeGp) MediaRecorder.OutputFormat.THREE_GPP else MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(output.absolutePath)
            recorder.setVideoEncodingBitRate((size.width.toLong() * size.height * 5).coerceIn(2_000_000L, 20_000_000L).toInt())
            recorder.setVideoFrameRate(30)
            recorder.setVideoSize(size.width, size.height)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.prepare()
            return recorder
        } catch (error: Throwable) {
            recorder.release()
            throw error
        }
    }

    private fun prepareRecorder(context: Context, output: File, requested: Size?, available: Array<Size>): PreparedRecorder {
        val candidates = listOfNotNull(
            ResolutionSelector.closest(requested, available),
            ResolutionSelector.closest(Size(1920, 1080), available),
            ResolutionSelector.closest(Size(1280, 720), available),
            ResolutionSelector.closest(Size(640, 480), available)
        ).distinctBy { it.width to it.height }
        var lastError: Throwable? = null
        for (candidate in candidates) {
            try {
                return PreparedRecorder(createRecorder(context, output, candidate), candidate)
            } catch (error: Throwable) {
                lastError = error
                output.delete()
            }
        }
        throw IllegalStateException("MediaRecorder could not prepare any supported camera resolution", lastError)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(manager: CameraManager, cameraId: String): CameraDevice {
        val latch = CountDownLatch(1)
        val result = AtomicReference<CameraDevice>()
        val failure = AtomicReference<Throwable>()
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                result.set(camera)
                latch.countDown()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                failure.set(IllegalStateException("Camera was disconnected"))
                latch.countDown()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                failure.set(IllegalStateException("Could not open camera (error $error)"))
                latch.countDown()
            }
        }, cameraHandler)
        await(latch, "opening camera")
        failure.get()?.let { throw it }
        return result.get() ?: error("Camera did not open")
    }

    @Suppress("DEPRECATION")
    private fun createRecordingSession(camera: CameraDevice, surface: Surface): CameraCaptureSession {
        val latch = CountDownLatch(1)
        val result = AtomicReference<CameraCaptureSession>()
        val failure = AtomicReference<Throwable>()
        camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                try {
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    }.build()
                    session.setRepeatingRequest(request, null, cameraHandler)
                    result.set(session)
                } catch (error: Throwable) {
                    session.close()
                    failure.set(error)
                }
                latch.countDown()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                session.close()
                failure.set(IllegalStateException("Camera could not configure a recording session"))
                latch.countDown()
            }
        }, cameraHandler)
        await(latch, "configuring recording")
        failure.get()?.let { throw it }
        return result.get() ?: error("Recording session was not created")
    }

    @Suppress("DEPRECATION")
    private fun capturePhoto(camera: CameraDevice, size: Size, output: File) {
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        val imageLatch = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        reader.setOnImageAvailableListener({ source ->
            try {
                source.acquireLatestImage()?.use { image ->
                    val buffer = image.planes[0].buffer
                    val jpeg = ByteArray(buffer.remaining()).also(buffer::get)
                    writePhoto(jpeg, output)
                } ?: error("Camera returned no image")
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                imageLatch.countDown()
            }
        }, cameraHandler)

        val sessionLatch = CountDownLatch(1)
        camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                try {
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(reader.surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }.build()
                    session.capture(request, null, cameraHandler)
                } catch (error: Throwable) {
                    failure.set(error)
                    imageLatch.countDown()
                } finally {
                    sessionLatch.countDown()
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                failure.set(IllegalStateException("Camera could not configure a photo session"))
                sessionLatch.countDown()
                imageLatch.countDown()
            }
        }, cameraHandler)
        try {
            await(sessionLatch, "configuring photo capture")
            await(imageLatch, "capturing photo")
            failure.get()?.let { throw it }
        } finally {
            reader.close()
        }
    }

    private fun writePhoto(jpeg: ByteArray, output: File) {
        if (output.extension.equals("png", true)) {
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                ?: error("Could not decode camera image")
            FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } else {
            output.writeBytes(jpeg)
        }
    }

    private fun await(latch: CountDownLatch, operation: String) {
        check(latch.await(CAMERA_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Timed out while $operation" }
    }

    private fun requirePermissions(context: Context, includeAudio: Boolean) {
        require(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "Camera permission is not granted"
        }
        if (includeAudio) {
            require(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "Microphone permission is not granted"
            }
        }
    }

    private fun startCameraService(context: Context, includeAudio: Boolean) {
        val intent = Intent(context, CameraRecordingService::class.java).putExtra(CameraRecordingService.EXTRA_INCLUDE_AUDIO, includeAudio)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
    }
}

object FrameTiming {
    fun timestampsUs(durationMs: Long, fps: Double?, frames: Int?): List<Long> {
        require((fps != null) xor (frames != null)) { "Set exactly one of framerate or frames" }
        fps?.let { require(it > 0 && it <= 120) { "Framerate must be between 0 and 120" } }
        frames?.let { require(it > 0) { "Frames must be greater than zero" } }
        require(durationMs > 0) { "Video has no readable duration" }
        return if (frames != null) {
            List(frames) { index -> if (frames == 1) 0L else durationMs * 1_000L * index / frames }
        } else {
            val intervalUs = (1_000_000.0 / fps!!).toLong()
            generateSequence(0L) { it + intervalUs }.takeWhile { it < durationMs * 1_000L }.toList()
        }
    }
}

object FrameExtractor {
    fun extract(video: String?, target: String?, base: String?, format: String?, fps: Double?, frames: Int?): Int {

        val source = FileHelper.existingFile(video)
        val destination = FileHelper.file(target, ".probe", format ?: "jpg").parentFile
            ?: error("Invalid target directory")
        val extension = format?.trim()?.trimStart('.')?.lowercase() ?: "jpg"
        require(extension in setOf("jpg", "jpeg", "png", "webp")) { "Unsupported image format: $extension" }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: error("Video duration could not be read")
            val timestampsUs = FrameTiming.timestampsUs(durationMs, fps, frames)
            var written = 0
            timestampsUs.forEachIndexed { index, timestamp ->
                val bitmap = retriever.getFrameAtTime(timestamp, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: error("Could not decode frame ${index + 1}")
                val output = File(destination, "${base?.takeIf(String::isNotBlank) ?: "frame"}-${index + 1}.$extension")
                FileOutputStream(output).use {
                    check(bitmap.compress(compressFormat(extension), 95, it)) { "Could not write ${output.absolutePath}" }
                }
                bitmap.recycle()
                written++
            }
            return written
        } finally {
            retriever.release()
        }
    }

    private fun compressFormat(extension: String) = when (extension) {
        "png" -> Bitmap.CompressFormat.PNG
        "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        else -> Bitmap.CompressFormat.JPEG
    }
}

class CameraRecordingService : Service() {
    private lateinit var notification: Notification

    override fun onCreate() {
        super.onCreate()
        val channelId = "camera"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "Camera recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
        notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }.setContentTitle("Tasker camera active")
            .setContentText("Capturing camera media")
            .setSmallIcon(R.drawable.plugin)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (intent?.getBooleanExtra(EXTRA_INCLUDE_AUDIO, false) == true) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            startForeground(1, notification, type)
        } else {
            startForeground(1, notification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_INCLUDE_AUDIO = "includeAudio"
    }
}
