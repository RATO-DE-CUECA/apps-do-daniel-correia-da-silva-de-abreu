package com.handtracking.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.hypot
import kotlin.math.min

/**
 * Desenha o esqueleto da mão: linhas brancas e pontos azuis.
 * Também desenha objetos virtuais (bolinha e cubo) que ficam verdes quando o dedo indicador encosta.
 * No modo VR Box também desenha a imagem da câmera e repete tudo para os dois olhos.
 */
class HandOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    @Volatile private var result: HandLandmarkerResult? = null
    @Volatile private var imageWidth = 1
    @Volatile private var imageHeight = 1
    @Volatile private var frame: Bitmap? = null

    /** true = tela dividida (VR Box); false = modo normal (só o esqueleto por cima do preview). */
    @Volatile var vrMode: Boolean = false
        set(value) {
            field = value
            postInvalidate()
        }

    private val linePaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 6f
        isAntiAlias = true
        style = Paint.Style.STROKE
    }
    private val pointPaint = Paint().apply {
        color = Color.rgb(30, 60, 255)
        strokeWidth = 6f
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dividerPaint = Paint().apply {
        color = Color.DKGRAY
        strokeWidth = 4f
    }
    private val frameRect = RectF()

    // ---- Objetos virtuais (posições em pixels da imagem da câmera) ----
    private val objectPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(160, 0, 0, 0)
    }
    private val facePath = Path()

    private var sceneReady = false
    private var ballX = 0f
    private var ballY = 0f
    private var ballR = 0f
    private var cubeX = 0f   // canto esquerdo da face da frente
    private var cubeY = 0f   // topo da face da frente
    private var cubeS = 0f   // lado da face da frente
    private var cubeD = 0f   // "profundidade" desenhada do cubo
    private var ballTouched = false
    private var cubeTouched = false

    fun setResults(newResult: HandLandmarkerResult?, width: Int, height: Int) {
        result = newResult
        imageWidth = width
        imageHeight = height
        postInvalidate()
    }

    /** Usado só no modo VR Box: último frame da câmera (já girado/espelhado). */
    fun setFrame(bitmap: Bitmap) {
        frame = bitmap
        postInvalidate()
    }

    fun clear() {
        result = null
        frame = null
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val bmp = if (vrMode) frame else null
        val iw = (bmp?.width ?: imageWidth).toFloat()
        val ih = (bmp?.height ?: imageHeight).toFloat()
        updateScene(iw, ih)

        if (vrMode) {
            val half = width / 2f
            drawRegion(canvas, 0f, half, height.toFloat(), bmp, iw, ih)     // olho esquerdo
            drawRegion(canvas, half, half, height.toFloat(), bmp, iw, ih)   // olho direito
            canvas.drawLine(half, 0f, half, height.toFloat(), dividerPaint)
        } else {
            drawRegion(canvas, 0f, width.toFloat(), height.toFloat(), null, iw, ih)
        }
    }

    /** Calcula onde ficam a bolinha e o cubo e se o dedo indicador está encostando neles. */
    private fun updateScene(iw: Float, ih: Float) {
        sceneReady = iw > 1f && ih > 1f
        if (!sceneReady) return

        val u = min(iw, ih)

        ballX = iw * 0.25f
        ballY = ih * 0.5f
        ballR = u * 0.10f

        cubeS = u * 0.17f
        cubeD = cubeS * 0.35f
        val cubeCenterX = iw * 0.75f
        val cubeCenterY = ih * 0.5f
        cubeX = cubeCenterX - (cubeS + cubeD) / 2f
        cubeY = cubeCenterY - (cubeS - cubeD) / 2f

        var ballHit = false
        var cubeHit = false
        val res = result
        if (res != null) {
            for (hand in res.landmarks()) {
                if (hand.size <= INDEX_TIP) continue
                val fx = hand[INDEX_TIP].x() * iw
                val fy = hand[INDEX_TIP].y() * ih

                if (hypot(fx - ballX, fy - ballY) <= ballR * 1.1f) ballHit = true
                if (fx >= cubeX && fx <= cubeX + cubeS + cubeD &&
                    fy >= cubeY - cubeD && fy <= cubeY + cubeS
                ) cubeHit = true
            }
        }
        ballTouched = ballHit
        cubeTouched = cubeHit
    }

    /** Desenha (imagem opcional +) objetos + esqueleto dentro de uma região da tela. */
    private fun drawRegion(
        canvas: Canvas,
        left: Float,
        regionWidth: Float,
        regionHeight: Float,
        bmp: Bitmap?,
        iw: Float,
        ih: Float
    ) {
        // Mesma regra de encaixe do PreviewView (FIT_CENTER), dentro da região.
        val scale = min(regionWidth / iw, regionHeight / ih)
        val offsetX = left + (regionWidth - iw * scale) / 2f
        val offsetY = (regionHeight - ih * scale) / 2f

        if (bmp != null) {
            frameRect.set(offsetX, offsetY, offsetX + iw * scale, offsetY + ih * scale)
            canvas.drawBitmap(bmp, null, frameRect, framePaint)
        }

        if (sceneReady) {
            canvas.save()
            canvas.translate(offsetX, offsetY)
            canvas.scale(scale, scale)
            drawBall(canvas)
            drawCube(canvas)
            canvas.restore()
        }

        val res = result ?: return
        for (hand in res.landmarks()) {
            HandLandmarker.HAND_CONNECTIONS.forEach { connection ->
                val a = hand[connection.start()]
                val b = hand[connection.end()]
                canvas.drawLine(
                    a.x() * iw * scale + offsetX, a.y() * ih * scale + offsetY,
                    b.x() * iw * scale + offsetX, b.y() * ih * scale + offsetY,
                    linePaint
                )
            }
            for (point in hand) {
                canvas.drawCircle(
                    point.x() * iw * scale + offsetX,
                    point.y() * ih * scale + offsetY,
                    9f,
                    pointPaint
                )
            }
        }
    }

    private fun drawBall(canvas: Canvas) {
        objectPaint.color = if (ballTouched) Color.rgb(67, 160, 71) else Color.rgb(229, 57, 53)
        canvas.drawCircle(ballX, ballY, ballR, objectPaint)
        canvas.drawCircle(ballX, ballY, ballR, outlinePaint)
        // Brilho para parecer uma esfera.
        objectPaint.color = Color.argb(120, 255, 255, 255)
        canvas.drawCircle(ballX - ballR * 0.3f, ballY - ballR * 0.3f, ballR * 0.3f, objectPaint)
    }

    private fun drawCube(canvas: Canvas) {
        val front: Int
        val top: Int
        val side: Int
        if (cubeTouched) {
            front = Color.rgb(67, 160, 71)
            top = Color.rgb(129, 199, 132)
            side = Color.rgb(46, 125, 50)
        } else {
            front = Color.rgb(255, 152, 0)
            top = Color.rgb(255, 183, 77)
            side = Color.rgb(230, 81, 0)
        }
        val x = cubeX
        val y = cubeY
        val s = cubeS
        val d = cubeD

        // Face da frente.
        objectPaint.color = front
        canvas.drawRect(x, y, x + s, y + s, objectPaint)
        canvas.drawRect(x, y, x + s, y + s, outlinePaint)

        // Face de cima.
        objectPaint.color = top
        facePath.reset()
        facePath.moveTo(x, y)
        facePath.lineTo(x + s, y)
        facePath.lineTo(x + s + d, y - d)
        facePath.lineTo(x + d, y - d)
        facePath.close()
        canvas.drawPath(facePath, objectPaint)
        canvas.drawPath(facePath, outlinePaint)

        // Face da direita.
        objectPaint.color = side
        facePath.reset()
        facePath.moveTo(x + s, y)
        facePath.lineTo(x + s + d, y - d)
        facePath.lineTo(x + s + d, y + s - d)
        facePath.lineTo(x + s, y + s)
        facePath.close()
        canvas.drawPath(facePath, objectPaint)
        canvas.drawPath(facePath, outlinePaint)
    }

    private companion object {
        const val INDEX_TIP = 8   // ponta do dedo indicador no modelo de mão do MediaPipe
    }
}
