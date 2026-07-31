package com.example.taskervideoplugin.tasker

import android.app.AlertDialog
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import com.example.taskervideoplugin.databinding.ActivityCameraConfigBinding
import com.example.taskervideoplugin.media.CameraLensSelector
import com.example.taskervideoplugin.toToast
import java.io.File

class ConfigUiEnhancer(
    private val activity: Config<*, *>,
    private val binding: ActivityCameraConfigBinding,
    private val fields: Set<Field>,
    private val taskerVariables: List<String>
) {
    private var folderTarget: EditText? = null
    private var fileTarget: EditText? = null
    private var resolutionSpinner: Spinner? = null
    private var manualResolution: EditText? = null
    private var manualResolutionRow: View? = null

    fun install() {
        installResolutionSelector()
        installCameraSelector()
        installFormatSelector()
        installFrameModeSelector()
        installAudioOperationSelector()

        if (Field.PATH in fields) {
            decorateInput(binding.path, folderButton = Field.FRAME_RATE !in fields, fileButton = Field.FRAME_RATE in fields)
        }
        if (Field.TARGET_PATH in fields) decorateInput(binding.targetPath, folderButton = true)
        listOf(
            Field.FILE_NAME to binding.fileName,
            Field.RECORDING_ID to binding.recordingId,
            Field.BASE_NAME to binding.baseName,
            Field.SCENE_NAME to binding.sceneName,
            Field.ELEMENT_NAME to binding.elementName
        ).filter { it.first in fields }.forEach { (_, input) -> decorateInput(input) }
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode !in setOf(FOLDER_REQUEST, FILE_REQUEST) || resultCode != android.app.Activity.RESULT_OK) return false
        val uri = data?.data ?: return true
        data.flags.and(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION).let { flags ->
            runCatching { activity.contentResolver.takePersistableUriPermission(uri, flags) }
        }
        val path = uri.toExternalStoragePath(requestCode == FOLDER_REQUEST)
        if (path == null) {
            "Dieser Ordneranbieter liefert keinen lokalen Dateipfad. Bitte internen Speicher verwenden.".toToast(activity)
        } else {
            if (requestCode == FOLDER_REQUEST) folderTarget?.setText(path) else fileTarget?.setText(path)
        }
        return true
    }

    private fun installCameraSelector() {
        if (Field.CAMERA !in fields) return
        replaceWithSpinner(binding.camera, listOf("back", "front")) { selected ->
            binding.camera.setText(selected)
            refreshResolutions(selected)
        }
    }

    private fun installResolutionSelector() {
        if (Field.RESOLUTION !in fields) return
        val original = binding.resolution.text.toString()
        val parent = binding.resolution.parent as ViewGroup
        val index = parent.indexOfChild(binding.resolution)
        parent.removeView(binding.resolution)
        binding.resolution.visibility = View.GONE

        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        resolutionSpinner = Spinner(activity)
        manualResolution = EditText(activity).apply {
            hint = "Manuelle Auflösung, z.B. 1920x1080"
            setText(original)
        }
        container.addView(resolutionSpinner, matchWrap())
        container.addView(manualResolution, matchWrap())
        parent.addView(container, index, matchWrap())
        manualResolution?.let {
            manualResolutionRow = decorateInput(it).apply { visibility = View.GONE }
            it.onTextChanged { value -> binding.resolution.setText(value) }
        }
    }

    private fun refreshResolutions(cameraFacing: String) {
        val spinner = resolutionSpinner ?: return
        val current = binding.resolution.text.toString()
        val options = runCatching {
            val manager = activity.getSystemService(CameraManager::class.java)
            val id = CameraLensSelector.choose(activity, cameraFacing)
            val map = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: error("Keine Auflösungen verfügbar")
            val sizes = if (isPhotoAction()) map.getOutputSizes(ImageFormat.JPEG) else map.getOutputSizes(MediaRecorder::class.java)
            sizes.sortedByDescending { it.width.toLong() * it.height }
                .distinctBy { it.width to it.height }
                .take(10)
                .map { "${it.width}x${it.height}" }
        }.getOrElse {
            listOf("3840x2160", "2560x1440", "1920x1080", "1280x720", "640x480")
        } + "Manuell"

        spinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, options)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = options[position]
                val manual = selected == "Manuell"
                manualResolutionRow?.visibility = if (manual) View.VISIBLE else View.GONE
                binding.resolution.setText(if (manual) manualResolution?.text?.toString() else selected)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        spinner.setSelection(options.indexOf(current).takeIf { it >= 0 } ?: options.lastIndex)
    }

    private fun installFormatSelector() {
        if (Field.FORMAT !in fields) return
        val options = when {
            Field.FRAME_RATE in fields -> listOf("jpg", "jpeg", "png", "webp")
            isPhotoAction() -> listOf("jpg", "jpeg", "png")
            else -> listOf("mp4", "3gp")
        }
        replaceWithSpinner(binding.format, options) { binding.format.setText(it) }
    }

    private fun installFrameModeSelector() {
        if (Field.FRAME_RATE !in fields || Field.FRAMES !in fields) return
        val existingRate = binding.frameRate.text?.toString().orEmpty()
        val existingFrames = binding.frames.text?.toString().orEmpty()
        val parent = binding.frameRate.parent as ViewGroup
        val index = parent.indexOfChild(binding.frameRate)
        parent.removeView(binding.frameRate)
        parent.removeView(binding.frames)
        binding.frameRate.visibility = View.GONE
        binding.frames.visibility = View.GONE

        val row = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val mode = Spinner(activity)
        val value = EditText(activity).apply { hint = "Wert"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
        row.addView(mode, matchWrap())
        row.addView(value, matchWrap())
        parent.addView(row, index, matchWrap())
        decorateInput(value)
        val modes = listOf("Framerate", "Frames")
        mode.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, modes)
        mode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                value.hint = modes[position]
                if (position == 0) {
                    binding.frames.text = null
                    binding.frameRate.setText(value.text)
                } else {
                    binding.frameRate.text = null
                    binding.frames.setText(value.text)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val usesFrames = existingFrames.isNotBlank()
        value.setText(if (usesFrames) existingFrames else existingRate)
        value.onTextChanged { text ->
            if (mode.selectedItemPosition == 0) binding.frameRate.setText(text) else binding.frames.setText(text)
        }
        mode.setSelection(if (usesFrames) 1 else 0)
    }

    private fun installAudioOperationSelector() {
        if (Field.AUDIO_OPERATION !in fields) return
        replaceWithSpinner(binding.audioOperation, listOf("start", "stop")) { binding.audioOperation.setText(it) }
    }

    private fun decorateInput(input: EditText, folderButton: Boolean = false, fileButton: Boolean = false): View {
        val parent = input.parent as? ViewGroup ?: return input
        val index = parent.indexOfChild(input)
        if (index < 0) return input
        parent.removeView(input)
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(activity).apply {
            text = "%"
            contentDescription = "Tasker-Variable auswählen"
            setOnClickListener { showVariableDialog(input) }
        })
        if (folderButton) {
            row.addView(Button(activity).apply {
                text = "📁"
                contentDescription = "Ordner auswählen"
                setOnClickListener {
                    folderTarget = input
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    )
                    @Suppress("DEPRECATION") activity.startActivityForResult(intent, FOLDER_REQUEST)
                }
            })
        }
        if (fileButton) {
            row.addView(Button(activity).apply {
                text = "📄"
                contentDescription = "Videodatei auswählen"
                setOnClickListener {
                    fileTarget = input
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        type = "video/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    }
                    @Suppress("DEPRECATION") activity.startActivityForResult(intent, FILE_REQUEST)
                }
            })
        }
        parent.addView(row, index, matchWrap())
        return row
    }

    private fun showVariableDialog(target: TextView) {
        if (taskerVariables.isEmpty()) {
            "Tasker hat für diesen Task keine relevanten lokalen, Projekt- oder globalen Variablen übermittelt.".toToast(activity)
            return
        }
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        val filter = EditText(activity).apply { hint = "Variablen filtern" }
        val list = ListView(activity)
        container.addView(filter, matchWrap())
        container.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600))
        val dialog = AlertDialog.Builder(activity).setTitle("Tasker-Variable auswählen").setView(container).setNegativeButton("Abbrechen", null).create()
        fun update(query: String) {
            val filtered = taskerVariables.distinct().sorted().filter { it.contains(query, ignoreCase = true) }
            list.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, filtered)
            list.setOnItemClickListener { _, _, position, _ -> target.text = filtered[position]; dialog.dismiss() }
        }
        filter.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = update(s?.toString().orEmpty())
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        update("")
        dialog.show()
    }

    private fun replaceWithSpinner(input: EditText, options: List<String>, onSelected: (String) -> Unit) {
        val parent = input.parent as ViewGroup
        val index = parent.indexOfChild(input)
        val previous = input.text.toString()
        parent.removeView(input)
        input.visibility = View.GONE
        val spinner = Spinner(activity)
        spinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, options)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onSelected(options[position])
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        spinner.setSelection(options.indexOf(previous).takeIf { it >= 0 } ?: 0)
        parent.addView(spinner, index, matchWrap())
    }

    private fun isPhotoAction() = Field.CAMERA in fields && Field.RECORDING_ID !in fields

    private fun Uri.toExternalStoragePath(tree: Boolean): String? {
        val documentId = if (tree) DocumentsContract.getTreeDocumentId(this) else DocumentsContract.getDocumentId(this)
        val parts = documentId.split(':', limit = 2)
        if (!parts[0].equals("primary", true)) return null
        return File(Environment.getExternalStorageDirectory(), parts.getOrElse(1) { "" }).absolutePath
    }

    private fun EditText.onTextChanged(callback: (String) -> Unit) {
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = callback(s?.toString().orEmpty())
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
    }

    private fun matchWrap() = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    companion object {
        private const val FOLDER_REQUEST = 4102
        private const val FILE_REQUEST = 4103
    }
}
