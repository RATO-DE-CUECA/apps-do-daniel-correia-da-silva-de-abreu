package com.handtracking.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
 * Desenha o esqueleto da mão e uma barra flutuante com 3 ícones (verde, vermelho e azul).
 *  - Encostar o dedo indicador num ícone: ele cresce um pouco e acende.
 *  - Pinça (polegar + indicador) num ícone: puxa o ícone para fora da barra e arrasta.
 *  - Soltar o ícone em cima da barra: ele volta para o seu lugar.
 *  - Pinça na barrinha branca embaixo da barra: move a barra inteira.
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

    // ---- Barra de ícones ----
    private val fillPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(140, 0, 0, 0)
    }
    private val grabRingPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.WHITE
    }
    private val pinchPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.YELLOW
    }
    private val rect = RectF()

    private val iconColors = intArrayOf(
        Color.rgb(67, 160, 71),   // verde
        Color.rgb(229, 57, 53),   // vermelho
        Color.rgb(30, 136, 229)   // azul
    )

    // Posição do CENTRO da barra, de 0 a 1 (fração da imagem da câmera).
    private var barNX = 0.5f
    private var barNY = 0.75f

    // Ícones: dentro da barra (no seu lugar) ou soltos (posição própria, de 0 a 1).
    private val iconInBar = BooleanArray(ICON_COUNT) { true }
    private val iconNX = FloatArray(ICON_COUNT)
    private val iconNY = FloatArray(ICON_COUNT)

    // Geometria em pixels da imagem da câmera (recalculada a cada desenho).
    private var sceneReady = false
    private var iconS = 0f
    private var gap = 0f
    private var barW = 0f
    private var barH = 0f
    private var barCX = 0f
    private var barCY = 0f
    private var barLeft = 0f
    private var barTop = 0f
    private var handleCX = 0f
    private var handleCY = 0f
    private var handleW = 0f
    private var handleH = 0f
    private val iconCX = FloatArray(ICON_COUNT)
    private val iconCY = FloatArray(ICON_COUNT)

    // Estado de cada ícone: 0 = normal, 1 = dedo encostando, 2 = segurado.
    private val iconState = IntArray(ICON_COUNT)
    private val iconTouch = BooleanArray(ICON_COUNT)
    private var handleState = 0

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

    /** Converte as posições (0 a 1) em pixels da imagem e calcula o tamanho de cada peça. */
    private fun applyGeometry(iw: Float, ih: Float) {
        val u = min(iw, ih)
        iconS = u * 0.13f
        gap = iconS * 0.25f
        barW = ICON_COUNT * iconS + (ICON_COUNT + 1) * gap
        barH = iconS + 2f * gap
        barCX = barNX * iw
        barCY = barNY * ih
        barLeft = barCX - barW / 2f
        barTop = barCY - barH / 2f

        handleW = barW * 0.6f
        handleH = max(u * 0.012f, 5f)
        handleCX = barCX
        handleCY = barTop + barH + gap * 1.2f

        for (i in 0 until ICON_COUNT) {
            if (iconInBar[i]) {
                iconCX[i] = barLeft + gap + iconS / 2f + i * (iconS + gap)
                iconCY[i] = barCY
            } else {
                iconCX[i] = iconNX[i] * iw
                iconCY[i] = iconNY[i] * ih
            }
        }
    }

    /**
     * Calcula onde está cada peça, se o dedo indicador encosta nelas
     * e se a pinça está segurando algo (e então move).
     */
    private fun updateScene(iw: Float, ih: Float) {
        sceneReady = iw > 1f && ih > 1f
        if (!sceneReady) return

        applyGeometry(iw, ih)

        for (i in 0 until ICON_COUNT) iconTouch[i] = false
        var handleTouch = false
        pinchCount = 0

        val res = result
        if (res != null) {
            for (hand in res.landmarks()) {
                if (hand.size <= MIDDLE_MCP) continue

                // Dedo indicador encostando?
                val fx = hand[INDEX_TIP].x() * iw
                val fy = hand[INDEX_TIP].y() * ih
                for (i in 0 until ICON_COUNT) {
                    if (abs(fx - iconCX[i]) <= iconS * 0.55f && abs(fy - iconCY[i]) <= iconS * 0.55f) {
                        iconTouch[i] = true
                    }
                }
                if (abs(fx - handleCX) <= handleW / 2f && abs(fy - handleCY) <= iconS * 0.5f) {
                    handleTouch = true
                }

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
            // Tenta pegar: primeiro um ícone, senão a barrinha de mover a barra.
            var best = NONE
            var bestDist = Float.MAX_VALUE
            var bestIdx = -1
            val reach = iconS * 0.65f
            for (p in 0 until pinchCount) {
                val px = pinchX[p]
                val py = pinchY[p]
                for (i in 0 until ICON_COUNT) {
                    if (abs(px - iconCX[i]) <= reach && abs(py - iconCY[i]) <= reach) {
                        val d = hypot(px - iconCX[i], py - iconCY[i])
                        if (d < bestDist) { best = i; bestDist = d; bestIdx = p }
                    }
                }
                if (abs(px - handleCX) <= handleW / 2f + iconS * 0.3f && abs(py - handleCY) <= iconS * 0.5f) {
                    // Soma iconS para o ícone ganhar quando os dois estiverem no mesmo ponto.
                    val d = hypot(px - handleCX, py - handleCY) + iconS
                    if (d < bestDist) { best = HANDLE; bestDist = d; bestIdx = p }
                }
            }
            if (best != NONE) {
                grabbed = best
                if (best == HANDLE) {
                    grabOffsetX = barCX - pinchX[bestIdx]
                    grabOffsetY = barCY - pinchY[bestIdx]
                } else {
                    grabOffsetX = iconCX[best] - pinchX[bestIdx]
                    grabOffsetY = iconCY[best] - pinchY[bestIdx]
                    if (iconInBar[best]) {
                        // Puxou o ícone para fora da barra.
                        iconInBar[best] = false
                        iconNX[best] = iconCX[best] / iw
                        iconNY[best] = iconCY[best] / ih
                    }
                }
            }
        } else {
            if (pinchCount == 0) {
                // Soltou. Se um ícone foi solto em cima da barra, ele volta para o seu lugar.
                if (grabbed != HANDLE) {
                    val i = grabbed
                    val margin = iconS * 0.3f
                    if (iconCX[i] in (barLeft - margin)..(barLeft + barW + margin) &&
                        iconCY[i] in (barTop - margin)..(barTop + barH + margin)
                    ) {
                        iconInBar[i] = true
                    }
                }
                grabbed = NONE
                applyGeometry(iw, ih)
            } else {
                // Segue a pinça mais perto da peça segurada.
                val cx = if (grabbed == HANDLE) barCX else iconCX[grabbed]
                val cy = if (grabbed == HANDLE) barCY else iconCY[grabbed]
                var idx = 0
                var bestDist = Float.MAX_VALUE
                for (p in 0 until pinchCount) {
                    val d = hypot(pinchX[p] - cx, pinchY[p] - cy)
                    if (d < bestDist) { bestDist = d; idx = p }
                }
                val nx = (pinchX[idx] + grabOffsetX) / iw
                val ny = (pinchY[idx] + grabOffsetY) / ih
                if (grabbed == HANDLE) {
                    val halfW = min(barW / 2f / iw, 0.49f)
                    barNX = nx.coerceIn(halfW, 1f - halfW)
                    barNY = ny.coerceIn(0.10f, 0.82f)
                } else {
                    iconNX[grabbed] = nx.coerceIn(0.05f, 0.95f)
                    iconNY[grabbed] = ny.coerceIn(0.05f, 0.95f)
                }
                applyGeometry(iw, ih)
            }
        }

        for (i in 0 until ICON_COUNT) {
            iconState[i] = if (grabbed == i) 2 else if (iconTouch[i]) 1 else 0
        }
        handleState = if (grabbed == HANDLE) 2 else if (handleTouch) 1 else 0
    }

    /** Desenha (imagem opcional +) barra + esqueleto dentro de uma região da tela. */
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
            drawBar(canvas)
            // Ícones na barra, depois os soltos, e o segurado por último (fica por cima).
            for (i in 0 until ICON_COUNT) if (iconInBar[i] && grabbed != i) drawIcon(canvas, i)
            for (i in 0 until ICON_COUNT) if (!iconInBar[i] && grabbed != i) drawIcon(canvas, i)
            if (grabbed in 0 until ICON_COUNT) drawIcon(canvas, grabbed)
            // Anel amarelo no ponto onde a pinça está fechada.
            for (p in 0 until pinchCount) {
                canvas.drawCircle(pinchX[p], pinchY[p], max(iconS * 0.25f, 8f), pinchPaint)
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

    private fun drawBar(canvas: Canvas) {
        // Fundo da barra (escuro e meio transparente).
        fillPaint.color = Color.argb(130, 30, 30, 30)
        rect.set(barLeft, barTop, barLeft + barW, barTop + barH)
        val r = gap * 1.6f
        canvas.drawRoundRect(rect, r, r, fillPaint)

        // Barrinha branca embaixo: serve para mover a barra.
        val h = if (handleState == 0) handleH else handleH * 1.8f
        fillPaint.color = if (handleState == 2) Color.YELLOW else Color.WHITE
        rect.set(handleCX - handleW / 2f, handleCY - h / 2f, handleCX + handleW / 2f, handleCY + h / 2f)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, fillPaint)
    }

    private fun drawIcon(canvas: Canvas, i: Int) {
        val state = iconState[i]
        val grow = when (state) {
            2 -> 1.2f
            1 -> 1.1f
            else -> 1f
        }
        val half = iconS / 2f * grow
        val cx = iconCX[i]
        val cy = iconCY[i]
        val radius = half * 0.5f

        rect.set(cx - half, cy - half, cx + half, cy + half)
        fillPaint.color = iconColors[i]
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        canvas.drawRoundRect(rect, radius, radius, outlinePaint)

        if (state == 1) {
            // Acende quando o dedo encosta.
            fillPaint.color = Color.argb(70, 255, 255, 255)
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
        } else if (state == 2) {
            canvas.drawRoundRect(rect, radius, radius, grabRingPaint)
        }
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

        const val ICON_COUNT = 3
        const val MAX_HANDS = 4
        const val NONE = -1
        const val HANDLE = ICON_COUNT   // alvo "barrinha de mover a barra"
    }
}
