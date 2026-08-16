package com.example.taskervideoplugin.media

import io.microshow.rxffmpeg.RxFFmpegInvoke
import java.io.File

object VideoAudioExtractor {
    fun normalizedFormat(value: String?): String {
        val format = value?.trim()?.trimStart('.')?.lowercase()
        require(format == "mp3" || format == "obb") { "Als Audioformat ist nur MP3 oder OBB erlaubt" }
        return requireNotNull(format)
    }

    fun command(source: File, output: File, format: String): Array<String> = when (format) {
        "mp3" -> arrayOf("ffmpeg", "-y", "-i", source.absolutePath, "-vn", "-map", "0:a:0", "-c:a", "libmp3lame", "-q:a", "2", "-f", "mp3", output.absolutePath)
        "obb" -> arrayOf("ffmpeg", "-y", "-i", source.absolutePath, "-vn", "-map", "0:a:0", "-c:a", "vorbis", "-strict", "experimental", "-q:a", "5", "-f", "ogg", output.absolutePath)
        else -> error("Unsupported audio format: $format")
    }

    fun extract(videoPath: String?, targetPath: String?, fileName: String?, requestedFormat: String?): File {
        val source = FileHelper.existingFile(videoPath)
        val format = normalizedFormat(requestedFormat)
        val output = FileHelper.file(targetPath, fileName ?: "audio", format)
        if (output.exists()) check(output.delete()) { "Vorhandene Ausgabedatei konnte nicht ersetzt werden: ${output.absolutePath}" }
        val result = RxFFmpegInvoke.getInstance().runCommand(command(source, output, format), null)
        check(result == 0 && output.isFile && output.length() > 0) {
            "Die Audiospur konnte nicht extrahiert werden (FFmpeg-Code $result). Das Video muss eine Audiospur enthalten."
        }
        return output
    }
}
