package com.handtracking.app

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.os.Bundle
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: HandOverlayView
    private lateinit var modeButton: Button
    private lateinit var flipButton: Button
    private lateinit var webView: WebView
    private lateinit var centerButton: Button

    // Giroscópio (modo 3D): a barra e os menus ficam parados no espaço.
    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    private var hasRotationSensor = false
    private val rotationMatrix = FloatArray(9)
    private val rotationValues = FloatArray(4)
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            var values = event.values
            if (values.size > 4) {
                System.arraycopy(values, 0, rotationValues, 0, 4)
                values = rotationValues
            }
            SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
            overlay.setGyro(rotationMatrix.clone())
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // YouTube dentro do menu vermelho (só no modo normal).
    private var webShown = false
    private var webLoaded = false
    private var webX = 0f
    private var webY = 0f
    private var webW = 0f
    private var webH = 0f
    private var webR = 0f
    private var webOutlineRadius = 0f
    private var webDownTime = 0L
    private lateinit var cameraExecutor: ExecutorService

    private var handLandmarker: HandLandmarker? = null

    // true = modo VR Box (tela dividida, câmera traseira, tela deitada).
    // Para o app abrir direto no modo normal, troque este valor padrão para false.
    @Volatile private var vrMode = true
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    @Volatile private var analysis: ImageAnalysis? = null

    // Bitmaps e matriz reaproveitados a cada frame (evita alocar memória 30x por segundo).
    // São 2 bitmaps de saída que se alternam, para a tela não desenhar um frame
    // enquanto ele está sendo reescrito.
    private var frameBitmap: Bitmap? = null
    private val frameBuffers = arrayOfNulls<Bitmap>(2)
    private var frameBufferIndex = 0
    private val transform = Matrix()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    // Se a tela girar de um lado deitado para o outro, avisa a câmera.
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            analysis?.targetRotation = currentRotation()
            overlay.setDisplayRotation(currentRotation())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        vrMode = savedInstanceState?.getBoolean(KEY_VR_MODE, true) ?: true
        lensFacing = savedInstanceState?.getInt(KEY_LENS, CameraSelector.LENS_FACING_BACK)
            ?: CameraSelector.LENS_FACING_BACK
        requestedOrientation = orientationForMode()

        setContentView(R.layout.activity_main)
        // Mantém a tela acesa (dentro do VR Box você não toca na tela).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        overlay = findViewById(R.id.overlay)
        modeButton = findViewById(R.id.modeButton)
        setupWebView()
        setupSensors()
        cameraExecutor = Executors.newSingleThreadExecutor()

        flipButton = findViewById(R.id.flipButton)
        flipButton.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT)
                CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
            overlay.clear()
            startCamera()
        }

        // Botão do cantinho: alterna entre VR Box (tela dividida) e modo normal.
        modeButton.setOnClickListener {
            vrMode = !vrMode
            // VR Box usa a câmera traseira; o modo normal volta para a frontal.
            lensFacing = if (vrMode) CameraSelector.LENS_FACING_BACK
            else CameraSelector.LENS_FACING_FRONT
            requestedOrientation = orientationForMode()
            applyModeUi()
            startCamera()
        }

        applyModeUi()

        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .registerDisplayListener(displayListener, null)

        setupHandLandmarker()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_VR_MODE, vrMode)
        outState.putInt(KEY_LENS, lensFacing)
    }

    private fun setupSensors() {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager = sm
        // Sem ímã (bússola): mais estável dentro de casa.
        // 1º giroscópio (melhor), 2º giroscópio/bússola do Android, 3º só acelerômetro + bússola.
        rotationSensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        hasRotationSensor = rotationSensor != null
        // Aviso rápido para saber qual sensor o seu celular está usando.
        Toast.makeText(this, "Sensor 3D: " + (rotationSensor?.name ?: "nenhum"), Toast.LENGTH_LONG).show()
        overlay.setDisplayRotation(currentRotation())

        centerButton = findViewById(R.id.centerButton)
        centerButton.visibility = if (hasRotationSensor) View.VISIBLE else View.GONE
        centerButton.setOnClickListener { overlay.recenter() }
    }

    private fun setupWebView() {
        webView = findViewById(R.id.youtubeView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.setBackgroundColor(Color.BLACK)
        // Cantos arredondados, igual ao menu.
        webView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, webOutlineRadius)
            }
        }
        webView.clipToOutline = true

        // O overlay diz onde o YouTube deve ficar e manda a pinça como toque.
        overlay.onWebPanel = { visible, x, y, w, h, r -> updateWebPanel(visible, x, y, w, h, r) }
        overlay.onWebTouch = { action, x, y -> sendWebTouch(action, x, y) }
    }

    private fun updateWebPanel(visible: Boolean, x: Float, y: Float, w: Float, h: Float, radius: Float) {
        if (!visible) {
            if (webShown) {
                webShown = false
                webView.post {
                    // Pausa o vídeo ao fechar o menu.
                    webView.evaluateJavascript(
                        "document.querySelectorAll('video').forEach(function(v){v.pause();})", null
                    )
                    webView.visibility = View.GONE
                }
            }
            return
        }
        if (webShown && x == webX && y == webY && w == webW && h == webH && radius == webR) return
        webShown = true
        webX = x; webY = y; webW = w; webH = h; webR = radius
        webView.post {
            val lp = webView.layoutParams
            if (lp.width != w.toInt() || lp.height != h.toInt()) {
                lp.width = w.toInt()
                lp.height = h.toInt()
                webView.layoutParams = lp
            }
            webView.translationX = x
            webView.translationY = y
            if (radius != webOutlineRadius) {
                webOutlineRadius = radius
                webView.invalidateOutline()
            }
            if (webView.visibility != View.VISIBLE) webView.visibility = View.VISIBLE
            if (!webLoaded) {
                webLoaded = true
                webView.loadUrl("https://m.youtube.com")
            }
        }
    }

    /** Transforma a pinça em um toque de dedo no YouTube. */
    private fun sendWebTouch(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) webDownTime = now
        val event = MotionEvent.obtain(webDownTime, now, action, x, y, 0)
        webView.dispatchTouchEvent(event)
        event.recycle()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webShown && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        sensorManager?.unregisterListener(sensorListener)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        val sensor = rotationSensor
        if (sensor != null) {
            sensorManager?.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun orientationForMode(): Int =
        if (vrMode) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

    private fun applyModeUi() {
        // No VR Box a imagem da câmera é desenhada pelo overlay (uma vez para cada olho),
        // então o PreviewView normal fica escondido.
        previewView.visibility = if (vrMode) View.GONE else View.VISIBLE
        overlay.vrMode = vrMode
        // No VR Box a câmera é sempre a traseira, então o botão de virar câmera some.
        flipButton.visibility = if (vrMode) View.GONE else View.VISIBLE
        modeButton.text = if (vrMode) "Modo normal" else "Modo VR Box"
        overlay.clear()
    }

    @Suppress("DEPRECATION")
    private fun currentRotation(): Int = windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0

    private fun buildLandmarker(delegate: Delegate): HandLandmarker {
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath("hand_landmarker.task")
                    .setDelegate(delegate)
                    .build()
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result: HandLandmarkerResult, input ->
                overlay.setResults(result, input.width, input.height)
            }
            .setErrorListener { error ->
                runOnUiThread { Toast.makeText(this, error.message, Toast.LENGTH_SHORT).show() }
            }
            .build()
        return HandLandmarker.createFromOptions(this, options)
    }

    private fun setupHandLandmarker() {
        // Tenta usar a GPU (bem mais rápido). Se o celular não suportar, cai para a CPU.
        handLandmarker = try {
            buildLandmarker(Delegate.GPU)
        } catch (e: Exception) {
            try {
                buildLandmarker(Delegate.CPU)
            } catch (e2: Exception) {
                Toast.makeText(this, R.string.model_error, Toast.LENGTH_LONG).show()
                null
            }
        }
    }

    private fun startCamera() {
        // Modo 3D (menu parado no espaço) só funciona com a câmera traseira.
        overlay.worldMode = hasRotationSensor && lensFacing == CameraSelector.LENS_FACING_BACK

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            // A tela pode ter sido recriada (giro de orientação) enquanto a câmera preparava.
            if (isDestroyed || isFinishing) return@addListener

            val provider = providerFuture.get()

            val analysisCase = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(currentRotation())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(cameraExecutor) { image -> detect(image) } }
            analysis = analysisCase

            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                provider.unbindAll()
                if (vrMode) {
                    // VR Box: só a análise; a imagem é desenhada pelo overlay nos dois olhos.
                    provider.bindToLifecycle(this, selector, analysisCase)
                } else {
                    val preview = Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    provider.bindToLifecycle(this, selector, preview, analysisCase)
                }
            } catch (e: Exception) {
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun detect(imageProxy: ImageProxy) {
        val landmarker = handLandmarker
        if (landmarker == null) {
            imageProxy.close()
            return
        }

        val w = imageProxy.width
        val h = imageProxy.height
        val rotation = imageProxy.imageInfo.rotationDegrees

        // Reaproveita o bitmap do frame se o tamanho não mudou.
        var frame = frameBitmap
        if (frame == null || frame.width != w || frame.height != h) {
            frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            frameBitmap = frame
        }
        imageProxy.use { frame.copyPixelsFromBuffer(it.planes[0].buffer) }

        // Tamanho final depois da rotação.
        val outW = if (rotation % 180 == 0) w else h
        val outH = if (rotation % 180 == 0) h else w

        frameBufferIndex = 1 - frameBufferIndex
        var prepared = frameBuffers[frameBufferIndex]
        if (prepared == null || prepared.width != outW || prepared.height != outH) {
            prepared = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            frameBuffers[frameBufferIndex] = prepared
        }

        // Rotação + espelho (câmera frontal) em uma única operação, sem criar bitmap novo.
        transform.reset()
        transform.postTranslate(-w / 2f, -h / 2f)
        transform.postRotate(rotation.toFloat())
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            transform.postScale(-1f, 1f)
        }
        transform.postTranslate(outW / 2f, outH / 2f)
        Canvas(prepared).drawBitmap(frame, transform, null)

        // No VR Box, manda o frame para a tela desenhar nos dois olhos.
        if (vrMode) overlay.setFrame(prepared, overlay.currentRotation())

        landmarker.detectAsync(BitmapImageBuilder(prepared).build(), SystemClock.uptimeMillis())
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.stopLoading()
        webView.destroy()
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .unregisterDisplayListener(displayListener)
        cameraExecutor.shutdown()
        handLandmarker?.close()
    }

    private companion object {
        const val KEY_VR_MODE = "vr_mode"
        const val KEY_LENS = "lens_facing"
    }
}
