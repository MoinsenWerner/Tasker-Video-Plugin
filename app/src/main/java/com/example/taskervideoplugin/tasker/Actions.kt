package com.example.taskervideoplugin.tasker

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.example.taskervideoplugin.ActivityConfigTasker
import com.example.taskervideoplugin.databinding.ActivityCameraConfigBinding
import com.example.taskervideoplugin.media.CameraController
import com.example.taskervideoplugin.media.FrameExtractor
import com.example.taskervideoplugin.media.Recording
import com.joaomgcd.taskerpluginlibrary.action.TaskerPluginRunnerAction
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfig
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfigHelper
import com.joaomgcd.taskerpluginlibrary.input.TaskerInput
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResult
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResultSucess
import com.joaomgcd.taskerpluginlibrary.runner.TaskerOutputRename
import com.joaomgcd.taskerpluginlibrary.runner.TaskerOutputRenames
import java.io.File

abstract class BaseRunner : TaskerPluginRunnerAction<CameraInput, MediaOutput>() {
    abstract val outputPrefix: String
    override val notificationProperties get() = NotificationProperties(iconResId = com.example.taskervideoplugin.R.drawable.plugin)

    fun out(file: File, resolution: String?, durationMs: Long = 0, recordingId: String? = null) = MediaOutput(
        file.path,
        file.parentFile?.path,
        file.extension,
        resolution,
        durationMs,
        durationMs / 1000,
        durationMs / 60000,
        "%02d:%02d".format(durationMs / 60000, (durationMs / 1000) % 60),
        recordingId
    )

    override fun addOutputVariableRenames(
        context: Context,
        input: TaskerInput<CameraInput>,
        renames: TaskerOutputRenames
    ) {
        listOf("video", "path", "format", "auflösung", "duration_ms", "duration_sec", "duration_min", "duration_mmss", "recording_id")
            .forEach { renames.add(TaskerOutputRename(it, "$outputPrefix$it")) }
    }
}

class StartVideoRunner : BaseRunner() {
    override val outputPrefix = "vasrt_"
    override fun run(context: Context, input: TaskerInput<CameraInput>) = TaskerPluginResultSucess(input.regular.run {
        val recording = CameraController.start(context, recordingId, camera, resolution, path, fileName, format)
        out(recording.file, recording.resolution, recordingId = recording.id)
    })
}

class PauseVideoRunner : BaseRunner() {
    override val outputPrefix = "vap_"
    override fun run(context: Context, input: TaskerInput<CameraInput>) = TaskerPluginResultSucess(input.regular.run {
        val result = CameraController.pause(recordingId ?: error("recordingId required"), stopAndSave)
        if (result is Pair<*, *>) {
            val recording = result.first as Recording
            out(recording.file, recording.resolution, result.second as Long, recording.id)
        } else {
            result as Recording
            out(result.file, result.resolution, recordingId = result.id)
        }
    })
}

class ResumeVideoRunner : BaseRunner() {
    override val outputPrefix = "vaf_"
    override fun run(context: Context, input: TaskerInput<CameraInput>) = TaskerPluginResultSucess(input.regular.run {
        val recording = CameraController.resume(recordingId ?: error("recordingId required"))
        out(recording.file, recording.resolution, recordingId = recording.id)
    })
}

class StopVideoRunner : BaseRunner() {
    override val outputPrefix = "vastp_"
    override fun run(context: Context, input: TaskerInput<CameraInput>) = TaskerPluginResultSucess(input.regular.run {
        val (recording, durationMs) = CameraController.stop(recordingId ?: error("recordingId required"))
        out(recording.file, recording.resolution, durationMs, recording.id)
    })
}

class TakePhotoRunner : BaseRunner() {
    override val outputPrefix = "fa_"
    override fun run(context: Context, input: TaskerInput<CameraInput>) = TaskerPluginResultSucess(input.regular.run {
        val (file, actualResolution) = CameraController.photo(context, camera, path, fileName, format, resolution)
        out(file, actualResolution)
    })
}

class VideoToFramesRunner : BaseRunner() {
    override val outputPrefix = "vtf_"
    override fun run(context: Context, input: TaskerInput<CameraInput>): TaskerPluginResult<MediaOutput> = input.regular.run {
        FrameExtractor.extract(path, targetPath, baseName, format, frameRate?.toDoubleOrNull(), frames?.toIntOrNull())
        TaskerPluginResultSucess(MediaOutput(path, targetPath, format, null))
    }
}

class AudioBlockRunner : BaseRunner() {
    override val outputPrefix = "vab_"
    override fun run(context: Context, input: TaskerInput<CameraInput>) = TaskerPluginResultSucess(
        MediaOutput("Audio of other apps cannot be selectively blocked for legacy Tasker scenes by a third-party plugin; mute the scene player or choose stream in the scene.", null, null, null)
    )
}

abstract class Helper<R : BaseRunner>(config: TaskerPluginConfig<CameraInput>, private val cls: Class<R>) : TaskerPluginConfigHelper<CameraInput, MediaOutput, R>(config) {
    override val runnerClass = cls
    override val inputClass = CameraInput::class.java
    override val outputClass = MediaOutput::class.java
    override val defaultInput = CameraInput()
    override fun addToStringBlurb(input: TaskerInput<CameraInput>, blurbBuilder: StringBuilder) {
        blurbBuilder.append(input.regular.fileName ?: input.regular.recordingId ?: input.regular.path)
    }
}

enum class Field { CAMERA, RESOLUTION, PATH, FILE_NAME, FORMAT, RECORDING_ID, STOP_AND_SAVE, TARGET_PATH, BASE_NAME, FRAME_RATE, FRAMES, SCENE_NAME, ELEMENT_NAME, AUDIO_OPERATION }

abstract class Config<R : BaseRunner, H : Helper<R>> : ActivityConfigTasker<CameraInput, MediaOutput, R, H, ActivityCameraConfigBinding>() {
    abstract val visibleFields: Set<Field>
    private var uiEnhancer: ConfigUiEnhancer? = null

    override fun inflateBinding(layoutInflater: LayoutInflater) = ActivityCameraConfigBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyVisibleFields()
        binding?.let {
            uiEnhancer = ConfigUiEnhancer(this, it, visibleFields, taskerHelper.relevantVariables.toList()).also(ConfigUiEnhancer::install)
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (uiEnhancer?.handleActivityResult(requestCode, resultCode, data) == true) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun assignFromInput(input: TaskerInput<CameraInput>) {
        input.regular.run {
            binding?.camera?.setText(camera)
            binding?.resolution?.setText(resolution)
            binding?.path?.setText(path)
            binding?.fileName?.setText(fileName)
            binding?.format?.setText(format)
            binding?.recordingId?.setText(recordingId)
            binding?.stopAndSave?.isChecked = stopAndSave
            binding?.targetPath?.setText(targetPath)
            binding?.baseName?.setText(baseName)
            binding?.frameRate?.setText(frameRate)
            binding?.frames?.setText(frames)
            binding?.sceneName?.setText(sceneName)
            binding?.elementName?.setText(elementName)
            binding?.audioOperation?.setText(audioOperation)
        }
    }

    override val inputForTasker get() = TaskerInput(CameraInput(
        camera = binding?.camera?.valueIfVisible(Field.CAMERA),
        resolution = binding?.resolution?.valueIfVisible(Field.RESOLUTION),
        path = binding?.path?.valueIfVisible(Field.PATH),
        fileName = binding?.fileName?.valueIfVisible(Field.FILE_NAME),
        format = binding?.format?.valueIfVisible(Field.FORMAT),
        recordingId = binding?.recordingId?.valueIfVisible(Field.RECORDING_ID),
        stopAndSave = Field.STOP_AND_SAVE in visibleFields && binding?.stopAndSave?.isChecked == true,
        targetPath = binding?.targetPath?.valueIfVisible(Field.TARGET_PATH),
        baseName = binding?.baseName?.valueIfVisible(Field.BASE_NAME),
        frameRate = binding?.frameRate?.valueIfVisible(Field.FRAME_RATE),
        frames = binding?.frames?.valueIfVisible(Field.FRAMES),
        sceneName = binding?.sceneName?.valueIfVisible(Field.SCENE_NAME),
        elementName = binding?.elementName?.valueIfVisible(Field.ELEMENT_NAME),
        audioOperation = binding?.audioOperation?.valueIfVisible(Field.AUDIO_OPERATION)
    ))

    private fun TextView.valueIfVisible(field: Field) = text?.toString()?.takeIf { field in visibleFields && it.isNotBlank() }

    private fun applyVisibleFields() = binding?.run {
        camera.visibility = visibilityFor(Field.CAMERA)
        resolution.visibility = visibilityFor(Field.RESOLUTION)
        path.visibility = visibilityFor(Field.PATH)
        fileName.visibility = visibilityFor(Field.FILE_NAME)
        format.visibility = visibilityFor(Field.FORMAT)
        recordingId.visibility = visibilityFor(Field.RECORDING_ID)
        stopAndSave.visibility = visibilityFor(Field.STOP_AND_SAVE)
        targetPath.visibility = visibilityFor(Field.TARGET_PATH)
        baseName.visibility = visibilityFor(Field.BASE_NAME)
        frameRate.visibility = visibilityFor(Field.FRAME_RATE)
        frames.visibility = visibilityFor(Field.FRAMES)
        sceneName.visibility = visibilityFor(Field.SCENE_NAME)
        elementName.visibility = visibilityFor(Field.ELEMENT_NAME)
        audioOperation.visibility = visibilityFor(Field.AUDIO_OPERATION)
    }

    private fun visibilityFor(field: Field) = if (field in visibleFields) View.VISIBLE else View.GONE
}

private val videoStartFields = setOf(Field.CAMERA, Field.RESOLUTION, Field.PATH, Field.FILE_NAME, Field.FORMAT, Field.RECORDING_ID)
private val recordingIdFields = setOf(Field.RECORDING_ID)
private val pauseFields = setOf(Field.RECORDING_ID, Field.STOP_AND_SAVE)
private val photoFields = setOf(Field.CAMERA, Field.PATH, Field.RESOLUTION, Field.FILE_NAME, Field.FORMAT)
private val framesFields = setOf(Field.PATH, Field.TARGET_PATH, Field.BASE_NAME, Field.FORMAT, Field.FRAME_RATE, Field.FRAMES)
private val audioFields = setOf(Field.SCENE_NAME, Field.ELEMENT_NAME, Field.AUDIO_OPERATION)

class StartVideoHelper(c: TaskerPluginConfig<CameraInput>) : Helper<StartVideoRunner>(c, StartVideoRunner::class.java)
class StartVideoActivity : Config<StartVideoRunner, StartVideoHelper>() { override val visibleFields = videoStartFields; override fun getNewHelper(config: TaskerPluginConfig<CameraInput>) = StartVideoHelper(config) }
class PauseVideoHelper(c: TaskerPluginConfig<CameraInput>) : Helper<PauseVideoRunner>(c, PauseVideoRunner::class.java)
class PauseVideoActivity : Config<PauseVideoRunner, PauseVideoHelper>() { override val visibleFields = pauseFields; override fun getNewHelper(config: TaskerPluginConfig<CameraInput>) = PauseVideoHelper(config) }
class ResumeVideoHelper(c: TaskerPluginConfig<CameraInput>) : Helper<ResumeVideoRunner>(c, ResumeVideoRunner::class.java)
class ResumeVideoActivity : Config<ResumeVideoRunner, ResumeVideoHelper>() { override val visibleFields = recordingIdFields; override fun getNewHelper(config: TaskerPluginConfig<CameraInput>) = ResumeVideoHelper(config) }
class StopVideoHelper(c: TaskerPluginConfig<CameraInput>) : Helper<StopVideoRunner>(c, StopVideoRunner::class.java)
class StopVideoActivity : Config<StopVideoRunner, StopVideoHelper>() { override val visibleFields = recordingIdFields; override fun getNewHelper(config: TaskerPluginConfig<CameraInput>) = StopVideoHelper(config) }
class TakePhotoHelper(c: TaskerPluginConfig<CameraInput>) : Helper<TakePhotoRunner>(c, TakePhotoRunner::class.java)
class TakePhotoActivity : Config<TakePhotoRunner, TakePhotoHelper>() { override val visibleFields = photoFields; override fun getNewHelper(config: TaskerPluginConfig<CameraInput>) = TakePhotoHelper(config) }
class VideoToFramesHelper(c: TaskerPluginConfig<CameraInput>) : Helper<VideoToFramesRunner>(c, VideoToFramesRunner::class.java)
class VideoToFramesActivity : Config<VideoToFramesRunner, VideoToFramesHelper>() { override val visibleFields = framesFields; override fun getNewHelper(config: TaskerPluginConfig<CameraInput>) = VideoToFramesHelper(config) }
class AudioBlockHelper(c: TaskerPluginConfig<CameraInput>) : Helper<AudioBlockRunner>(c, AudioBlockRunner::class.java)
class AudioBlockActivity : Config<AudioBlockRunner, AudioBlockHelper>() { override val visibleFields = audioFields; override fun getNewHelper(config: TaskerPluginConfig<CameraInput>) = AudioBlockHelper(config) }
