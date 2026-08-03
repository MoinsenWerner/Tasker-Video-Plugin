package com.example.taskervideoplugin.tasker

import com.joaomgcd.taskerpluginlibrary.input.TaskerInputField
import com.joaomgcd.taskerpluginlibrary.input.TaskerInputRoot
import com.joaomgcd.taskerpluginlibrary.output.TaskerOutputObject
import com.joaomgcd.taskerpluginlibrary.output.TaskerOutputVariable

@TaskerInputRoot
class CameraInput @JvmOverloads constructor(
    @field:TaskerInputField("camera", labelResIdName = "camera") var camera: String? = "back",
    @field:TaskerInputField("resolution", labelResIdName = "resolution") var resolution: String? = "1920x1080",
    @field:TaskerInputField("path", labelResIdName = "path") var path: String? = "Tasker/recordings",
    @field:TaskerInputField("fileName", labelResIdName = "file_name") var fileName: String? = "capture",
    @field:TaskerInputField("format", labelResIdName = "format") var format: String? = "mp4",
    @field:TaskerInputField("recordingId", labelResIdName = "recording_id") var recordingId: String? = null,
    @field:TaskerInputField("stopAndSave", labelResIdName = "stop_and_save") var stopAndSave: Boolean = false,
    @field:TaskerInputField("targetPath", labelResIdName = "target_path") var targetPath: String? = null,
    @field:TaskerInputField("baseName", labelResIdName = "base_name") var baseName: String? = "frame",
    @field:TaskerInputField("frameRate", labelResIdName = "frame_rate") var frameRate: String? = null,
    @field:TaskerInputField("frames", labelResIdName = "frames") var frames: String? = null,
    @field:TaskerInputField("sceneName", labelResIdName = "scene_name") var sceneName: String? = null,
    @field:TaskerInputField("elementName", labelResIdName = "element_name") var elementName: String? = null,
    @field:TaskerInputField("audioOperation", labelResIdName = "audio_operation") var audioOperation: String? = "start"
)

@TaskerOutputObject
class MediaOutput(
    @get:TaskerOutputVariable("video", labelResIdName = "video_path", htmlLabelResIdName = "video_description") var video: String?,
    @get:TaskerOutputVariable("path", labelResIdName = "path", htmlLabelResIdName = "path_description") var path: String?,
    @get:TaskerOutputVariable("format", labelResIdName = "format", htmlLabelResIdName = "format_description") var format: String?,
    @get:TaskerOutputVariable("auflösung", labelResIdName = "resolution", htmlLabelResIdName = "resolution_description") var resolution: String?,
    @get:TaskerOutputVariable("duration_ms", labelResIdName = "duration_ms", htmlLabelResIdName = "duration_description") var durationMs: Long = 0,
    @get:TaskerOutputVariable("duration_sec", labelResIdName = "duration_sec", htmlLabelResIdName = "duration_description") var durationSec: Long = 0,
    @get:TaskerOutputVariable("duration_min", labelResIdName = "duration_min", htmlLabelResIdName = "duration_description") var durationMin: Long = 0,
    @get:TaskerOutputVariable("duration_mmss", labelResIdName = "duration_mmss", htmlLabelResIdName = "duration_description") var durationMmss: String = "00:00",
    @get:TaskerOutputVariable("recording_id", labelResIdName = "recording_id", htmlLabelResIdName = "recording_id_description") var recordingId: String? = null
)
