package com.handtracking.app

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import java.nio.ByteBuffer

/**
 * YouTube para o modo VR Box.
 *
 * No VR Box a tela mostra duas imagens (uma para cada olho), então um WebView normal não serve:
 * ele só pode aparecer UMA vez na tela. A solução é rodar o WebView em uma "tela virtual" escondida
 * (VirtualDisplay) e ir copiando os quadros dela para um Bitmap. O HandOverlayView desenha esse
 * Bitmap duas vezes, uma para cada olho. A pinça vira toque (touch) nessa tela virtual.
 */
class WebTexture(private val activity: Activity) {

    /** Último quadro do YouTube (o Bitmap pode ser mais largo que WIDTH; use só a parte 0..WIDTH). */
    @Volatile var latest: Bitmap? = null
        private set

    /** Chamado (em outra thread) sempre que chega um quadro novo. */
    @Volatile var onFrame: (() -> Unit)? = null

    private var thread: HandlerThread? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: WebPresentation? = null
    private val buffers = arrayOfNulls<Bitmap>(2)
    private var bufferIndex = 0
    private var downTime = 0L
    private var started = false
    private var loaded = false
    @Volatile private var active = false

    /** Liga a tela virtual (use na thread principal). Devolve false se o celular não deixou. */
    fun start(): Boolean {
        if (started) return true
        try {
            val t = HandlerThread("aether-web")
            t.start()
            thread = t

            val r = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2)
            reader = r
            r.setOnImageAvailableListener({ rd -> grab(rd) }, Handler(t.looper))

            val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val vd = dm.createVirtualDisplay("aether-web", WIDTH, HEIGHT, DPI, r.surface, 0)
            if (vd == null) {
                stop()
                return false
            }
            virtualDisplay = vd

            val p = WebPresentation(activity, vd.display)
            presentation = p
            p.show()   // cria o WebView dentro da tela virtual
            started = true
            if (!loaded) {
                loaded = true
                p.web?.loadUrl("https://m.youtube.com")
            }
            return true
        } catch (e: Exception) {
            stop()
            return false
        }
    }

    /** Liga/desliga o trabalho. Desligado: pausa o vídeo e para de copiar quadros (economiza bateria). */
    fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        val w = presentation?.web ?: return
        if (on) {
            w.onResume()
        } else {
            w.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause();})", null)
            w.onPause()
        }
    }

    /** Toque no YouTube virtual. x e y em pixels da tela virtual (0..WIDTH, 0..HEIGHT). Use na thread principal. */
    fun touch(action: Int, x: Float, y: Float) {
        val decor = presentation?.window?.decorView ?: return
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val event = MotionEvent.obtain(downTime, now, action, x, y, 0)
        decor.dispatchTouchEvent(event)
        event.recycle()
    }

    /** Desliga tudo e libera a memória (use na thread principal). */
    fun stop() {
        started = false
        active = false
        loaded = false
        val web = presentation?.web
        try { presentation?.dismiss() } catch (e: Exception) { }
        try { web?.destroy() } catch (e: Exception) { }
        presentation = null
        try { virtualDisplay?.release() } catch (e: Exception) { }
        virtualDisplay = null
        try { reader?.close() } catch (e: Exception) { }
        reader = null
        thread?.quitSafely()
        thread = null
        latest = null
    }

    /** Copia o quadro novo da tela virtual para um Bitmap (roda na thread "aether-web"). */
    private fun grab(rd: ImageReader) {
        val image = try {
            rd.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        try {
            if (!active) return   // menu fechado: descarta o quadro
            val plane = image.planes[0]
            val buf = plane.buffer
            val bw = plane.rowStride / plane.pixelStride
            val idx = 1 - bufferIndex
            var bmp = buffers[idx]
            if (bmp == null || bmp.width != bw || bmp.height != HEIGHT) {
                bmp = Bitmap.createBitmap(bw, HEIGHT, Bitmap.Config.ARGB_8888)
                buffers[idx] = bmp
            }
            buf.rewind()
            val needed = bw * HEIGHT * 4
            if (buf.remaining() >= needed) {
                bmp.copyPixelsFromBuffer(buf)
            } else {
                // Alguns celulares entregam a última linha mais curta: completa com zeros.
                val tmp = ByteBuffer.allocateDirect(needed)
                tmp.put(buf)
                tmp.rewind()
                bmp.copyPixelsFromBuffer(tmp)
            }
            bufferIndex = idx
            latest = bmp
        } catch (e: Exception) {
            return
        } finally {
            image.close()
        }
        onFrame?.invoke()
    }

    /** Janela que mostra o YouTube dentro da tela virtual. */
    private class WebPresentation(context: Context, display: Display) : Presentation(context, display) {
        var web: WebView? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val wv = WebView(context)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.mediaPlaybackRequiresUserGesture = false
            wv.webViewClient = WebViewClient()
            wv.webChromeClient = WebChromeClient()
            wv.setBackgroundColor(Color.BLACK)
            web = wv
            setContentView(wv)
        }
    }

    companion object {
        const val WIDTH = 1024    // tamanho da tela virtual do YouTube
        const val HEIGHT = 576    // 16:9
        const val DPI = 240
    }
}
