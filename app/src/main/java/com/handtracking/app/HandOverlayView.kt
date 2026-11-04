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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Desenha o esqueleto da mão: linhas brancas e pontos azuis.
 * Também desenha objetos virtuais (bolinha e cubo):
 *  - verde  = o dedo indicador está encostando;
 *  - azul   = você está segurando com a pinça (polegar + indicador) e pode arrastar.
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

    // ---- Objetos virtuais ----
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
    private val pinchPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.YELLOW
    }
    private val facePath = Path()

    // Posição do CENTRO de cada objeto, de 0 a 1 (fração da imagem da câmera).
    private var ballNX = 0.25f
    private var ballNY = 0.5f
    private var cubeNX = 0.75f
    private var cubeNY = 0.5f

    // Geometria em pixels da imagem da câmera (recalculada a cada desenho).
    private var sceneReady = false
    private var ballX = 0f
    private var ballY = 0f
    private var ballR = 0f
    private var cubeCX = 0f  // centro do cubo
    private var cubeCY = 0f
    private var cubeX = 0f   // canto esquerdo da face da frente
    private var cubeY = 0f   // topo da face da frente
    private var cubeS = 0f   // lado da face da frente
    private var cubeD = 0f   // "profundidade" desenhada do cubo

    // Estado de cada objeto: 0 = normal, 1 = dedo encostando, 2 = segurado.
    private var ballState = 0
    private var cubeState = 0

    // Pegar e mover.
    private var grabbed = NONE
    private var grabOffsetX = 0f
    private var grabOffsetY = 0f
    private val pinchX = FloatArray(MAX_HANDS)
    private val pinchY = FloatArray(MAX_HANDS)
    private var pinchCount = 0

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
        grabbed = NONE
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

    /** Converte as posições (0 a 1) dos objetos em pixels da imagem. */
    private fun applyGeometry(iw: Float, ih: Float) {
        val u = min(iw, ih)
        ballR = u * 0.10f
        cubeS = u * 0.17f
        cubeD = cubeS * 0.35f

        ballX = ballNX * iw
        ballY = ballNY * ih
        cubeCX = cubeNX * iw
        cubeCY = cubeNY * ih
        cubeX = cubeCX - (cubeS + cubeD) / 2f
        cubeY = cubeCY - (cubeS - cubeD) / 2f
    }

    /**
     * Calcula onde estão os objetos, se o dedo indicador encosta neles
     * e se a pinça está segurando algum (e então move o objeto).
     */
    private fun updateScene(iw: Float, ih: Float) {
        sceneReady = iw > 1f && ih > 1f
        if (!sceneReady) return

        applyGeometry(iw, ih)

        var ballTouch = false
        var cubeTouch = false
        pinchCount = 0

        val res = result
        if (res != null) {
            for (hand in res.landmarks()) {
                if (hand.size <= MIDDLE_MCP) continue

                // Dedo indicador encostando?
                val fx = hand[INDEX_TIP].x() * iw
                val fy = hand[INDEX_TIP].y() * ih
                if (hypot(fx - ballX, fy - ballY) <= ballR * 1.1f) ballTouch = true
                if (fx >= cubeX && fx <= cubeX + cubeS + cubeD &&
                    fy >= cubeY - cubeD && fy <= cubeY + cubeS
                ) cubeTouch = true

                // Pinça: distância polegar-indicador comparada com o tamanho da mão.
                val tx = hand[THUMB_TIP].x() * iw
                val ty = hand[THUMB_TIP].y() * ih
                val handSize = hypot(
                    (hand[WRIST].x() - hand[MIDDLE_MCP].x()) * iw,
                    (hand[WRIST].y() - hand[MIDDLE_MCP].y()) * ih
                )
                if (handSize > 1f && pinchCount < MAX_HANDS) {
                    val ratio = hypot(tx - fx, ty - fy) / handSize
                    // Histerese: precisa fechar mais para pegar do que para soltar.
                    val limit = if (grabbed != NONE) PINCH_RELEASE else PINCH_START
                    if (ratio < limit) {
                        pinchX[pinchCount] = (tx + fx) / 2f
                        pinchY[pinchCount] = (ty + fy) / 2f
                        pinchCount++
                    }
                }
            }
        }

        if (grabbed == NONE) {
            // Tenta pegar o objeto mais perto do ponto de pinça.
            var best = NONE
            var bestDist = Float.MAX_VALUE
            var bestIdx = -1
            val cubeReach = (cubeS + cubeD) / 2f * 1.25f
            for (i in 0 until pinchCount) {
                val dBall = hypot(pinchX[i] - ballX, pinchY[i] - ballY)
                if (dBall <= ballR * 1.4f && dBall < bestDist) {
                    best = BALL; bestDist = dBall; bestIdx = i
                }
                val dCube = hypot(pinchX[i] - cubeCX, pinchY[i] - cubeCY)
                if (abs(pinchX[i] - cubeCX) <= cubeReach &&
                    abs(pinchY[i] - cubeCY) <= cubeReach && dCube < bestDist
                ) {
                    best = CUBE; bestDist = dCube; bestIdx = i
                }
            }
            if (best != NONE) {
                grabbed = best
                val cx = if (best == BALL) ballX else cubeCX
                val cy = if (best == BALL) ballY else cubeCY
                grabOffsetX = cx - pinchX[bestIdx]
                grabOffsetY = cy - pinchY[bestIdx]
            }
        } else {
            if (pinchCount == 0) {
                grabbed = NONE   // soltou
            } else {
                // Segue a pinça mais perto do objeto segurado.
                val cx = if (grabbed == BALL) ballX else cubeCX
                val cy = if (grabbed == BALL) ballY else cubeCY
                var idx = 0
                var bestDist = Float.MAX_VALUE
                for (i in 0 until pinchCount) {
                    val d = hypot(pinchX[i] - cx, pinchY[i] - cy)
                    if (d < bestDist) { bestDist = d; idx = i }
                }
                val nx = ((pinchX[idx] + grabOffsetX) / iw).coerceIn(0.08f, 0.92f)
                val ny = ((pinchY[idx] + grabOffsetY) / ih).coerceIn(0.10f, 0.90f)
                if (grabbed == BALL) { ballNX = nx; ballNY = ny } else { cubeNX = nx; cubeNY = ny }
                applyGeometry(iw, ih)
            }
        }

        ballState = if (grabbed == BALL) 2 else if (ballTouch) 1 else 0
        cubeState = if (grabbed == CUBE) 2 else if (cubeTouch) 1 else 0
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
            // Anel amarelo no ponto onde a pinça está fechada.
            for (i in 0 until pinchCount) {
                canvas.drawCircle(pinchX[i], pinchY[i], max(ballR * 0.35f, 8f), pinchPaint)
            }
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
        objectPaint.color = when (ballState) {
            2 -> Color.rgb(30, 136, 229)
            1 -> Color.rgb(67, 160, 71)
            else -> Color.rgb(229, 57, 53)
        }
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
        when (cubeState) {
            2 -> {
                front = Color.rgb(30, 136, 229)
                top = Color.rgb(100, 181, 246)
                side = Color.rgb(21, 101, 192)
            }
            1 -> {
                front = Color.rgb(67, 160, 71)
                top = Color.rgb(129, 199, 132)
                side = Color.rgb(46, 125, 50)
            }
            else -> {
                front = Color.rgb(255, 152, 0)
                top = Color.rgb(255, 183, 77)
                side = Color.rgb(230, 81, 0)
            }
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
        // Pontos da mão no modelo do MediaPipe.
        const val WRIST = 0
        const val THUMB_TIP = 4
        const val INDEX_TIP = 8
        const val MIDDLE_MCP = 9

        // Sensibilidade da pinça (distância polegar-indicador / tamanho da mão).
        // Quanto MAIOR o número, mais "fácil" é pegar/soltar.
        const val PINCH_START = 0.30f
        const val PINCH_RELEASE = 0.45f

        const val MAX_HANDS = 4
        const val NONE = 0
        const val BALL = 1
        const val CUBE = 2
    }
}
