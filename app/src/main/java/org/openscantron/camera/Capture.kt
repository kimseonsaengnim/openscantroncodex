package org.openscantron.camera

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.openscantron.domain.SheetLayout
import org.openscantron.omr.Recognizer
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

class Capture(private val context: Context, private val lifecycle: LifecycleOwner, private val preview: PreviewView,
              private val layout: SheetLayout,
              private val update: (String, Boolean) -> Unit, private val error: (String) -> Unit) {
    private val main = ContextCompat.getMainExecutor(context)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    @Volatile private var closed = false
    private var torch = false
    private var front = false
    private var taking = false
    private val recognizer = Recognizer()

    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (closed) return@addListener
            runCatching {
                provider = future.get()
                bind()
            }.onFailure { error("Camera is unavailable. Close other camera apps or import a photo.") }
        }, main)
    }

    private fun bind() {
        val provider = requireNotNull(provider)
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        check(provider.hasCamera(selector))
        val feed = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
        val shutter = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
        preview.display?.rotation?.let { shutter.targetRotation = it }
        val analyzer = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        var last = 0L
        analyzer.setAnalyzer(analysisExecutor) { frame ->
            try {
                val time = android.os.SystemClock.elapsedRealtime()
                if (time - last >= 500) {
                    last = time
                    val quality = frameQuality(frame)
                    main.execute { if (!closed) update(quality.first, quality.second) }
                }
            } finally { frame.close() }
        }
        provider.unbindAll()
        camera = provider.bindToLifecycle(lifecycle, selector, feed, shutter, analyzer)
        imageCapture = shutter
        torch = false
    }

    fun switch() {
        val next = if (!front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        if (provider?.hasCamera(next) != true) {
            error("No second camera is available.")
            return
        }
        front = !front
        runCatching(::bind).onFailure { error("Could not switch cameras. Use gallery import.") }
    }

    fun torch() {
        val camera = camera
        if (camera?.cameraInfo?.hasFlashUnit() != true) {
            error("This camera has no torch.")
            return
        }
        torch = !torch
        camera.cameraControl.enableTorch(torch)
    }

    fun take(onPhoto: (File) -> Unit, onFinished: () -> Unit) {
        val shutter = imageCapture
        if (shutter == null || taking) { onFinished(); return }
        taking = true
        preview.display?.rotation?.let { shutter.targetRotation = it }
        val target = runCatching { File.createTempFile("sheet-", ".jpg", context.cacheDir) }.getOrElse {
            taking = false
            error("Could not create a capture file. Free storage and try again.")
            onFinished()
            return
        }
        shutter.takePicture(ImageCapture.OutputFileOptions.Builder(target).build(), main,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    taking = false
                    if (!closed) onPhoto(target) else target.delete()
                    onFinished()
                }
                override fun onError(exception: ImageCaptureException) {
                    taking = false
                    target.delete()
                    if (!closed) error("Capture was interrupted. Retry or import a photo.")
                    onFinished()
                }
            })
    }

    fun close() {
        closed = true
        provider?.unbindAll()
        analysisExecutor.shutdown()
    }

    private fun frameQuality(frame: ImageProxy): Pair<String, Boolean> {
        val plane = frame.planes[0]
        val buffer = plane.buffer
        val width = frame.width
        val height = frame.height
        val step = (minOf(width, height) / 55).coerceAtLeast(2)
        var samples = 0
        var total = 0
        var dark = 0
        var edges = 0
        for (y in step until height - step step step) for (x in step until width - step step step) {
            val index = y * plane.rowStride + x * plane.pixelStride
            if (index + step * plane.pixelStride >= buffer.limit()) continue
            val value = buffer[index].toInt() and 255
            val right = buffer[index + step * plane.pixelStride].toInt() and 255
            samples++
            total += value
            if (value < 96) dark++
            if (abs(value - right) > 36) edges++
        }
        if (samples == 0 || total / samples < 105) return "Add even light or turn on the torch." to false
        if (dark < samples * .012 || edges < samples * .021) return "Fill the guide with a sharp, marked sheet." to false
        if (dark > samples * .38) return "Center the sheet and reduce shadows." to false
        val sample = (maxOf(width, height) / 850).coerceAtLeast(1)
        val reducedWidth = width / sample
        val reducedHeight = height / sample
        val gray = ByteArray(reducedWidth * reducedHeight) { index ->
            val row = index / reducedWidth * sample
            val col = index % reducedWidth * sample
            buffer.get(row * plane.rowStride + col * plane.pixelStride)
        }
        val aligned = runCatching { recognizer.previewAligned(reducedWidth, reducedHeight, gray, layout) }.getOrDefault(false)
        if (!aligned) return "Center the selected template and bring all four squares into view." to false
        return "Sheet aligned. Hold steady." to true
    }
}
