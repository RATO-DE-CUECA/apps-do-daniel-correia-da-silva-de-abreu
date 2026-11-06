package com.handtracking.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Desenha o esqueleto da mão e uma barra flutuante com 4 ícones (verde, vermelho, azul e amarelo).
 *  - Encostar o dedo indicador num ícone: ele cresce um pouco e acende.
 *  - Pinça rápida (fechar e abrir os dedos) num ícone: abre (ou fecha) o menu daquele ícone.
 *  - Os ícones ficam sempre no lugar (não dá para arrastar).
 *  - Pinça na barrinha branca embaixo da barra: move a barra inteira.
 *  - Pinça na barrinha branca embaixo de um menu: move só aquele menu.
 *  - Menu vermelho (modo normal): mostra o YouTube; a pinça funciona como o dedo na tela
 *    (pinça rápida = clique, pinça e arrastar = rolar).
 *  - Menu verde: configurações (estilo da mão: normal ou estilo Meta Quest; fica salvo no aparelho).
 *  - Menu amarelo: mini jogo de empilhar blocos (pinça num bloco para pegar, solta para cair;
 *    o botão redondo no canto reinicia).
 * No modo VR Box também desenha a imagem da câmera e repete tudo para os dois olhos.
 *
 * Modo 3D ("mundo"): com a câmera traseira e o giroscópio, a barra e os menus ficam parados
 * no espaço. Se você vira a cabeça, eles saem da tela e ficam no lugar onde você os deixou.
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

    // ---- Barra de ícones e menus ----
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
    private val pressRingPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.WHITE
    }
    private val panelBorderPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 6f
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
        Color.rgb(30, 136, 229),  // azul
        Color.rgb(251, 192, 45)   // amarelo (mini jogo)
    )

    // Posição do CENTRO da barra, de 0 a 1 (fração da imagem da câmera).
    // Altura: 0 = topo da imagem, 1 = embaixo. (0.55 = meio da tela)
    private var barNX = 0.5f
    private var barNY = 0.55f

    // Menu aberto (índice do ícone) ou NONE. Só um menu fica aberto por vez.
    private var openMenu = NONE

    // Cada menu lembra a própria posição depois que você o move (centro, de 0 a 1).
    // Enquanto não foi movido, ele aparece em cima da barra e acompanha a barra.
    private val panelMoved = BooleanArray(ICON_COUNT)
    private val panelNX = FloatArray(ICON_COUNT)
    private val panelNY = FloatArray(ICON_COUNT)

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

    private var panelW = 0f
    private var panelH = 0f
    private var panelLeft = 0f
    private var panelTop = 0f
    private var pHandleCX = 0f
    private var pHandleCY = 0f
    private var pHandleW = 0f

    // Estado: 0 = normal, 1 = dedo encostando, 2 = segurado.
    private val iconState = IntArray(ICON_COUNT)
    private val iconTouch = BooleanArray(ICON_COUNT)
    private var handleState = 0
    private var pHandleState = 0

    // Pinça (pegar).
    private var grabbed = NONE
    private var grabOffsetX = 0f
    private var grabOffsetY = 0f
    private var tapCancelled = false   // true = a mão se afastou, então não conta como toque
    private var grabStartTime = 0L
    private var grabStartX = 0f
    private var grabStartY = 0f
    private val pinchX = FloatArray(MAX_HANDS)
    private val pinchY = FloatArray(MAX_HANDS)
    private var pinchCount = 0

    // ---- YouTube dentro do menu vermelho (só no modo normal) ----
    /** Avisa a tela onde o YouTube deve ficar: (visível, x, y, largura, altura, raio), em pixels da view. */
    var onWebPanel: ((Boolean, Float, Float, Float, Float, Float) -> Unit)? = null
    /** Envia a pinça como toque para o YouTube: (ação do MotionEvent, x, y) em pixels do YouTube. */
    var onWebTouch: ((Int, Float, Float) -> Unit)? = null
    private var touchScale = 1f      // pixels da view por pixel da imagem (modo normal)
    private var lastWebPX = 0f       // última posição do toque no YouTube (pixels da imagem)
    private var lastWebPY = 0f
    private var panelCorner = 0f

    // ---- YouTube no modo VR Box (desenhado por nós, uma vez para cada olho) ----
    /** Pede para ligar/desligar o YouTube "virtual" (quem faz isso é a MainActivity). */
    var onVrWebWanted: ((Boolean) -> Unit)? = null
    /** Pinça -> toque no YouTube virtual: (ação, x, y) em pixels do YouTube virtual. */
    var onVrWebTouch: ((Int, Float, Float) -> Unit)? = null
    @Volatile private var webFrame: Bitmap? = null
    private val webSrc = Rect(0, 0, WebTexture.WIDTH, WebTexture.HEIGHT)

    // ---- Configurações (1º ícone, verde): estilo da mão ----
    private val prefs = context.getSharedPreferences("aether_mr", Context.MODE_PRIVATE)

    /** Estilo da mão: STYLE_NORMAL (linhas e pontos) ou STYLE_QUEST (mão translúcida). Fica salvo. */
    var handStyle: Int = prefs.getInt("hand_style", STYLE_NORMAL)
        set(value) {
            field = value
            prefs.edit().putInt("hand_style", value).apply()
            postInvalidate()
        }

    private val optL = FloatArray(STYLE_COUNT)      // cartões de escolha: lado esquerdo de cada um
    private var optT = 0f                           // topo (igual para todos)
    private var optW = 0f
    private var optH = 0f
    private val optTouch = BooleanArray(STYLE_COUNT)
    private var optHeld = -1
    private val lmX = FloatArray(21)                // pontos da mão em pixels da tela (para desenhar)
    private val lmY = FloatArray(21)
    private val questPaint = Paint().apply {
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val palmPath = Path()

    // ---- Mini jogo: empilhar blocos (4º ícone, amarelo) ----
    // Unidade do jogo: 1,0 = largura do menu. A altura do menu é GAME_ASPECT (o chão fica embaixo).
    private val gX = FloatArray(GAME_BLOCKS)        // lado esquerdo do bloco
    private val gY = FloatArray(GAME_BLOCKS)        // topo do bloco (cresce para baixo)
    private val gW = floatArrayOf(0.20f, 0.17f, 0.15f, 0.19f, 0.13f)   // largura de cada bloco
    private val gVX = FloatArray(GAME_BLOCKS)
    private val gVY = FloatArray(GAME_BLOCKS)
    private val gSupport = IntArray(GAME_BLOCKS)    // em cima de quem (-1 = chão, -2 = no ar)
    private val gLevel = IntArray(GAME_BLOCKS)      // andar na torre (0 = fora da torre)
    private val gOrder = IntArray(GAME_BLOCKS)
    private val gTouch = BooleanArray(GAME_BLOCKS)  // dedo indicador encostando no bloco
    private val gColors = intArrayOf(
        Color.rgb(239, 83, 80),
        Color.rgb(255, 202, 40),
        Color.rgb(66, 165, 245),
        Color.rgb(171, 71, 188),
        Color.rgb(102, 187, 106)
    )
    private var gameHeld = -1
    private var gameReady = false
    private var gameLastMs = 0L
    private var gameTower = 0
    private var gameBest = 0
    private var resetCX = 0f
    private var resetCY = 0f
    private var resetR = 0f
    private var resetTouch = false
    private val textPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        isFakeBoldText = true
    }
    private val arrowPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        color = Color.rgb(40, 40, 40)
    }
    private val arrowPath = Path()

    // ---- Modo 3D ("mundo"): a barra e os menus ficam parados no espaço, usando o giroscópio ----
    /** true = usa o giroscópio (só com a câmera traseira). false = tudo preso na tela, como antes. */
    @Volatile var worldMode: Boolean = false
        set(value) {
            field = value
            anchorsPending = true
            panelMoved.fill(false)
            postInvalidate()
        }
    @Volatile private var liveRot: FloatArray? = null    // giroscópio mais recente (dispositivo -> mundo)
    @Volatile private var frameRot: FloatArray? = null   // giroscópio na hora do frame (VR Box)
    private var rot: FloatArray? = null                  // matriz usada no desenho atual
    private var worldActive = false
    private var anchorsPending = true                    // precisa (re)posicionar a barra à frente
    private val eRight = floatArrayOf(1f, 0f, 0f)        // direita da tela, nos eixos do aparelho
    private val eUp = floatArrayOf(0f, 1f, 0f)           // cima da tela, nos eixos do aparelho
    private val barDir = FloatArray(3)                   // direção da barra no mundo
    private val panelDir = Array(ICON_COUNT) { FloatArray(3) }  // direção de cada menu no mundo
    private val tmp2 = FloatArray(2)

    fun setResults(newResult: HandLandmarkerResult?, width: Int, height: Int) {
        result = newResult
        imageWidth = width
        imageHeight = height
        postInvalidate()
    }

    /** Usado só no modo VR Box: último frame da câmera (já girado/espelhado) e o giroscópio daquela hora. */
    fun setFrame(bitmap: Bitmap, rotation: FloatArray? = null) {
        frame = bitmap
        frameRot = rotation
        postInvalidate()
    }

    /** Último quadro do YouTube virtual (modo VR Box). */
    fun setWebFrame(bitmap: Bitmap?) {
        webFrame = bitmap
        postInvalidate()
    }

    /** Giroscópio: matriz 3x3 (dispositivo -> mundo), vinda do SensorManager. */
    fun setGyro(matrix: FloatArray) {
        liveRot = matrix
        if (worldMode && !vrMode) postInvalidate()
    }

    fun currentRotation(): FloatArray? = liveRot

    /**
     * Retângulos das mãos na última detecção: (minX, minY, maxX, maxY) de 0 a 1, um para cada mão.
     * O rastreador por câmera usa isso para ignorar as mãos (elas se mexem sozinhas).
     */
    fun handBoxes(): FloatArray? {
        val res = result ?: return null
        val hands = res.landmarks()
        if (hands.isEmpty()) return null
        val out = FloatArray(hands.size * 4)
        for ((i, hand) in hands.withIndex()) {
            var minX = 1f
            var minY = 1f
            var maxX = 0f
            var maxY = 0f
            for (p in hand) {
                minX = min(minX, p.x())
                minY = min(minY, p.y())
                maxX = max(maxX, p.x())
                maxY = max(maxY, p.y())
            }
            out[i * 4] = minX
            out[i * 4 + 1] = minY
            out[i * 4 + 2] = maxX
            out[i * 4 + 3] = maxY
        }
        return out
    }

    /** Rotação da tela (Surface.ROTATION_*): diz onde ficam a direita e o cima da tela no aparelho. */
    fun setDisplayRotation(rotation: Int) {
        when (rotation) {
            Surface.ROTATION_90 -> setAxes(0f, 1f, 0f, -1f, 0f, 0f)
            Surface.ROTATION_180 -> setAxes(-1f, 0f, 0f, 0f, -1f, 0f)
            Surface.ROTATION_270 -> setAxes(0f, -1f, 0f, 1f, 0f, 0f)
            else -> setAxes(1f, 0f, 0f, 0f, 1f, 0f)
        }
        postInvalidate()
    }

    private fun setAxes(rx: Float, ry: Float, rz: Float, ux: Float, uy: Float, uz: Float) {
        eRight[0] = rx; eRight[1] = ry; eRight[2] = rz
        eUp[0] = ux; eUp[1] = uy; eUp[2] = uz
    }

    /** Traz a barra de volta para a frente de você (e os menus voltam a acompanhar a barra). */
    fun recenter() {
        anchorsPending = true
        postInvalidate()
    }

    // Distância focal da câmera, em pixels da imagem (supõe ~66 graus no lado maior).
    private fun focal(iw: Float, ih: Float): Float = max(iw, ih) / 2f / TAN_HALF_FOV

    /** Direção do mundo -> ponto na imagem. Devolve false se estiver atrás da câmera. */
    private fun project(dir: FloatArray, iw: Float, ih: Float, out: FloatArray): Boolean {
        val r = rot ?: return false
        // Direção nos eixos do aparelho (R transposta vezes a direção).
        val dx = r[0] * dir[0] + r[3] * dir[1] + r[6] * dir[2]
        val dy = r[1] * dir[0] + r[4] * dir[1] + r[7] * dir[2]
        val dz = r[2] * dir[0] + r[5] * dir[1] + r[8] * dir[2]
        val fwd = -dz   // a câmera traseira olha para -Z
        if (fwd < 0.05f) return false
        val right = dx * eRight[0] + dy * eRight[1] + dz * eRight[2]
        val up = dx * eUp[0] + dy * eUp[1] + dz * eUp[2]
        val f = focal(iw, ih)
        out[0] = (iw / 2f + f * right / fwd).coerceIn(-OFFSCREEN, OFFSCREEN)
        out[1] = (ih / 2f - f * up / fwd).coerceIn(-OFFSCREEN, OFFSCREEN)
        return true
    }

    /** Ponto na imagem -> direção do mundo. */
    private fun unproject(x: Float, y: Float, iw: Float, ih: Float, out: FloatArray) {
        val r = rot ?: return
        val f = focal(iw, ih)
        val a = (x - iw / 2f) / f
        val b = -(y - ih / 2f) / f
        val vx = a * eRight[0] + b * eUp[0]
        val vy = a * eRight[1] + b * eUp[1]
        val vz = a * eRight[2] + b * eUp[2] - 1f
        val wx = r[0] * vx + r[1] * vy + r[2] * vz
        val wy = r[3] * vx + r[4] * vy + r[5] * vz
        val wz = r[6] * vx + r[7] * vy + r[8] * vz
        val n = sqrt(wx * wx + wy * wy + wz * wz)
        if (n < 1e-6f) return
        out[0] = wx / n
        out[1] = wy / n
        out[2] = wz / n
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
        touchScale = min(width / iw, height / ih)
        // No VR Box usa o giroscópio da hora do frame (a imagem e o menu ficam combinados).
        rot = if (vrMode) (frameRot ?: liveRot) else liveRot
        worldActive = worldMode && rot != null
        updateScene(iw, ih)

        if (vrMode) {
            val half = width / 2f
            drawRegion(canvas, 0f, half, height.toFloat(), bmp, iw, ih)     // olho esquerdo
            drawRegion(canvas, half, half, height.toFloat(), bmp, iw, ih)   // olho direito
            canvas.drawLine(half, 0f, half, height.toFloat(), dividerPaint)
        } else {
            drawRegion(canvas, 0f, width.toFloat(), height.toFloat(), null, iw, ih)
        }

        notifyWebPanel(iw, ih)

        // O jogo precisa redesenhar sempre (os blocos caem sozinhos).
        if (isGameOpen()) postInvalidateOnAnimation()
    }

    /** O menu verde é o das configurações. */
    private fun isSettingsOpen(): Boolean = openMenu == SETTINGS_ICON

    /** O menu amarelo é o jogo de empilhar blocos. */
    private fun isGameOpen(): Boolean = openMenu == GAME_ICON

    /** O menu vermelho mostra o YouTube (nos dois modos). */
    private fun isWebOpen(): Boolean = openMenu == YOUTUBE_ICON

    /** Modo normal: o YouTube é uma tela de verdade (WebView) por cima da imagem. */
    private fun isWebPanel(): Boolean = !vrMode && isWebOpen()

    /** Modo VR Box: o YouTube é desenhado por nós, uma vez para cada olho. */
    private fun isVrWeb(): Boolean = vrMode && isWebOpen()

    /** Manda a pinça como toque para o YouTube (o de verdade ou o virtual do VR Box). */
    private fun emitWebTouch(action: Int, px: Float, py: Float) {
        if (vrMode) {
            onVrWebTouch?.invoke(
                action,
                (px - panelLeft) / panelW * WebTexture.WIDTH,
                (py - panelTop) / panelH * WebTexture.HEIGHT
            )
        } else {
            onWebTouch?.invoke(action, (px - panelLeft) * touchScale, (py - panelTop) * touchScale)
        }
    }

    /** Diz para a tela onde colocar (ou esconder) o YouTube. */
    private fun notifyWebPanel(iw: Float, ih: Float) {
        onVrWebWanted?.invoke(sceneReady && isVrWeb())
        val cb = onWebPanel ?: return
        if (!sceneReady || !isWebPanel()) {
            cb(false, 0f, 0f, 0f, 0f, 0f)
            return
        }
        val scale = min(width / iw, height / ih)
        val offsetX = (width - iw * scale) / 2f
        val offsetY = (height - ih * scale) / 2f
        cb(
            true,
            offsetX + panelLeft * scale,
            offsetY + panelTop * scale,
            panelW * scale,
            panelH * scale,
            panelCorner * scale
        )
    }

    /** Converte as posições (0 a 1) em pixels da imagem e calcula o tamanho de cada peça. */
    private fun applyGeometry(iw: Float, ih: Float) {
        val u = min(iw, ih)
        iconS = u * 0.13f
        gap = iconS * 0.25f
        barW = ICON_COUNT * iconS + (ICON_COUNT + 1) * gap
        barH = iconS + 2f * gap
        if (worldActive) {
            if (project(barDir, iw, ih, tmp2)) {
                barCX = tmp2[0]
                barCY = tmp2[1]
            } else {
                barCX = -OFFSCREEN   // atrás de você: fora da tela
                barCY = -OFFSCREEN
            }
        } else {
            barCX = barNX * iw
            barCY = barNY * ih
        }
        barLeft = barCX - barW / 2f
        barTop = barCY - barH / 2f

        handleW = barW * 0.6f
        handleCX = barCX
        handleCY = barTop + barH + gap * 1.2f

        for (i in 0 until ICON_COUNT) {
            iconCX[i] = barLeft + gap + iconS / 2f + i * (iconS + gap)
            iconCY[i] = barCY
        }

        // Menu aberto.
        val margin = u * 0.03f
        if (isWebPanel()) {
            // Menu do YouTube (modo normal): maior, para caber a página.
            panelW = iw * 0.92f
            panelH = ih * 0.36f
        } else if (isVrWeb()) {
            // YouTube no VR Box: janela no formato do vídeo (16:9), do tamanho que couber acima da barra.
            val aspect = WebTexture.HEIGHT.toFloat() / WebTexture.WIDTH
            val room = barTop - gap * 3.2f - margin
            panelH = min(u * 0.45f, max(room, u * 0.2f))
            panelW = min(panelH / aspect, iw * 0.9f)
            panelH = panelW * aspect
        } else if (isGameOpen()) {
            // Jogo: janela mais alta; o tamanho dos blocos é sempre proporcional à largura.
            panelW = min(u * 0.72f, iw * 0.9f)
            panelH = panelW * GAME_ASPECT
        } else {
            panelW = min(u * 0.62f, iw * 0.9f)
            panelH = u * 0.30f
        }
        panelCorner = gap * 1.6f
        pHandleW = panelW * 0.5f
        val m = openMenu
        if (m != NONE) {
            val left: Float
            val top: Float
            if (panelMoved[m]) {
                if (worldActive) {
                    if (project(panelDir[m], iw, ih, tmp2)) {
                        left = tmp2[0] - panelW / 2f
                        top = tmp2[1] - panelH / 2f
                    } else {
                        left = -OFFSCREEN
                        top = -OFFSCREEN
                    }
                } else {
                    left = panelNX[m] * iw - panelW / 2f
                    top = panelNY[m] * ih - panelH / 2f
                }
            } else {
                // Padrão: em cima da barra (ou embaixo dela, se não couber em cima).
                left = barCX - panelW / 2f
                val aboveTop = barTop - gap * 3.2f - panelH
                top = if (worldActive || aboveTop >= margin) aboveTop else handleCY + gap * 2.2f
            }
            if (worldActive) {
                // No modo 3D o menu pode sair da tela: ele fica parado no espaço.
                panelLeft = left
                panelTop = top
            } else {
                val maxLeft = max(margin, iw - margin - panelW)
                val maxTop = max(margin, ih - margin - panelH - gap * 2.5f)
                panelLeft = left.coerceIn(margin, maxLeft)
                panelTop = top.coerceIn(margin, maxTop)
            }
            pHandleCX = panelLeft + panelW / 2f
            pHandleCY = panelTop + panelH + gap * 1.3f
            resetR = panelW * 0.05f
            resetCX = panelLeft + panelW - panelW * 0.075f
            resetCY = panelTop + panelW * 0.075f
            // Cartões de escolha do menu de configurações (dois lado a lado).
            val pad = panelW * 0.05f
            optW = (panelW - pad * (STYLE_COUNT + 1)) / STYLE_COUNT
            optH = panelH * 0.52f
            optT = panelTop + panelH * 0.40f
            for (o in 0 until STYLE_COUNT) optL[o] = panelLeft + pad + o * (optW + pad)
        }
    }

    /** Ao abrir o jogo, a barra desce para sobrar espaço em cima (o jogo usa uma janela grande). */
    private fun lowerBarForGame(iw: Float, ih: Float) {
        val targetY = ih * GAME_BAR_Y
        if (barCY >= targetY) return
        if (worldActive) {
            unproject(barCX, targetY, iw, ih, barDir)
        } else {
            barNY = (targetY / ih).coerceIn(0.10f, 0.82f)
        }
    }

    /**
     * Calcula onde está cada peça, se o dedo indicador encosta nelas
     * e se a pinça está segurando algo.
     */
    private fun updateScene(iw: Float, ih: Float) {
        sceneReady = iw > 1f && ih > 1f
        if (!sceneReady) return

        handleH = max(min(iw, ih) * 0.012f, 5f)

        if (worldActive && anchorsPending) {
            // Põe a barra no meio da imagem de agora, parada no espaço.
            unproject(iw * 0.5f, ih * 0.55f, iw, ih, barDir)
            panelMoved.fill(false)
            anchorsPending = false
        }
        applyGeometry(iw, ih)

        for (i in 0 until ICON_COUNT) iconTouch[i] = false
        for (b in 0 until GAME_BLOCKS) gTouch[b] = false
        for (o in 0 until STYLE_COUNT) optTouch[o] = false
        resetTouch = false
        var handleTouch = false
        var pHandleTouch = false
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
                if (isSettingsOpen()) {
                    for (o in 0 until STYLE_COUNT) {
                        if (fx >= optL[o] && fx <= optL[o] + optW && fy >= optT && fy <= optT + optH) optTouch[o] = true
                    }
                }
                if (isGameOpen()) {
                    val blockH = GAME_BLOCK_H * panelW
                    for (b in 0 until GAME_BLOCKS) {
                        val bl = panelLeft + gX[b] * panelW
                        val bt = panelTop + gY[b] * panelW
                        if (fx >= bl && fx <= bl + gW[b] * panelW && fy >= bt && fy <= bt + blockH) gTouch[b] = true
                    }
                    if (abs(fx - resetCX) <= resetR * 1.4f && abs(fy - resetCY) <= resetR * 1.4f) resetTouch = true
                }
                if (openMenu != NONE &&
                    abs(fx - pHandleCX) <= pHandleW / 2f && abs(fy - pHandleCY) <= iconS * 0.5f
                ) {
                    pHandleTouch = true
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

        if ((grabbed == GAME_BLOCK || grabbed == GAME_RESET) && (!isGameOpen() || (grabbed == GAME_BLOCK && gameHeld < 0))) {
            grabbed = NONE
            gameHeld = -1
        }
        if (grabbed == SET_OPT && (!isSettingsOpen() || optHeld < 0)) {
            grabbed = NONE
            optHeld = -1
        }

        if (grabbed == NONE) {
            // Tenta pegar: um ícone (toque), a barrinha da barra ou a barrinha do menu aberto.
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
                // Soma iconS nas barrinhas para o ícone ganhar quando estiverem no mesmo ponto.
                if (abs(px - handleCX) <= handleW / 2f + iconS * 0.3f && abs(py - handleCY) <= iconS * 0.5f) {
                    val d = hypot(px - handleCX, py - handleCY) + iconS
                    if (d < bestDist) { best = HANDLE; bestDist = d; bestIdx = p }
                }
                if (openMenu != NONE &&
                    abs(px - pHandleCX) <= pHandleW / 2f + iconS * 0.3f && abs(py - pHandleCY) <= iconS * 0.5f
                ) {
                    val d = hypot(px - pHandleCX, py - pHandleCY) + iconS
                    if (d < bestDist) { best = PANEL_HANDLE; bestDist = d; bestIdx = p }
                }
            }
            if (best == NONE && isWebOpen()) {
                // Pinça dentro do YouTube: funciona como o dedo na tela.
                for (p in 0 until pinchCount) {
                    if (pinchX[p] in panelLeft..(panelLeft + panelW) &&
                        pinchY[p] in panelTop..(panelTop + panelH)
                    ) {
                        best = PANEL_CONTENT
                        bestIdx = p
                        break
                    }
                }
            }
            if (best == NONE && isSettingsOpen()) {
                // Pinça num cartão de estilo da mão.
                val tol = iconS * 0.1f
                for (p in 0 until pinchCount) {
                    for (o in 0 until STYLE_COUNT) {
                        if (pinchX[p] >= optL[o] - tol && pinchX[p] <= optL[o] + optW + tol &&
                            pinchY[p] >= optT - tol && pinchY[p] <= optT + optH + tol
                        ) {
                            best = SET_OPT
                            bestIdx = p
                            optHeld = o
                            break
                        }
                    }
                    if (best != NONE) break
                }
            }
            if (best == NONE && isGameOpen()) {
                // Pinça no botão de reiniciar ou num bloco do jogo.
                val tol = iconS * 0.25f
                val blockH = GAME_BLOCK_H * panelW
                var bd = Float.MAX_VALUE
                for (p in 0 until pinchCount) {
                    if (abs(pinchX[p] - resetCX) <= resetR * 1.4f && abs(pinchY[p] - resetCY) <= resetR * 1.4f) {
                        best = GAME_RESET
                        bestIdx = p
                        break
                    }
                    for (b in 0 until GAME_BLOCKS) {
                        val cx = panelLeft + (gX[b] + gW[b] / 2f) * panelW
                        val cy = panelTop + (gY[b] + GAME_BLOCK_H / 2f) * panelW
                        if (abs(pinchX[p] - cx) <= gW[b] * panelW / 2f + tol && abs(pinchY[p] - cy) <= blockH / 2f + tol) {
                            val d = hypot(pinchX[p] - cx, pinchY[p] - cy)
                            if (d < bd) {
                                bd = d
                                best = GAME_BLOCK
                                bestIdx = p
                                gameHeld = b
                            }
                        }
                    }
                }
            }
            if (best != NONE) {
                grabbed = best
                grabStartTime = SystemClock.uptimeMillis()
                grabStartX = pinchX[bestIdx]
                grabStartY = pinchY[bestIdx]
                tapCancelled = false
                when (best) {
                    HANDLE -> {
                        grabOffsetX = barCX - pinchX[bestIdx]
                        grabOffsetY = barCY - pinchY[bestIdx]
                    }
                    PANEL_HANDLE -> {
                        grabOffsetX = panelLeft + panelW / 2f - pinchX[bestIdx]
                        grabOffsetY = panelTop + panelH / 2f - pinchY[bestIdx]
                    }
                    GAME_BLOCK -> {
                        val b = gameHeld
                        grabOffsetX = panelLeft + (gX[b] + gW[b] / 2f) * panelW - pinchX[bestIdx]
                        grabOffsetY = panelTop + (gY[b] + GAME_BLOCK_H / 2f) * panelW - pinchY[bestIdx]
                        gVX[b] = 0f
                        gVY[b] = 0f
                    }
                    PANEL_CONTENT -> {
                        grabOffsetX = 0f
                        grabOffsetY = 0f
                        lastWebPX = pinchX[bestIdx]
                        lastWebPY = pinchY[bestIdx]
                        emitWebTouch(MotionEvent.ACTION_DOWN, lastWebPX, lastWebPY)
                    }
                    else -> {
                        grabOffsetX = 0f
                        grabOffsetY = 0f
                    }
                }
            }
        } else {
            if (pinchCount == 0) {
                // Soltou. Pinça rápida num ícone, sem a mão se afastar = toque: abre/fecha o menu.
                if (grabbed in 0 until ICON_COUNT && !tapCancelled &&
                    SystemClock.uptimeMillis() - grabStartTime <= TAP_MAX_MS
                ) {
                    openMenu = if (openMenu == grabbed) NONE else grabbed
                    if (openMenu == GAME_ICON && !panelMoved[GAME_ICON]) lowerBarForGame(iw, ih)
                }
                if (grabbed == GAME_RESET && !tapCancelled &&
                    SystemClock.uptimeMillis() - grabStartTime <= TAP_MAX_MS
                ) {
                    resetGame()
                }
                if (grabbed == GAME_BLOCK) {
                    gameHeld = -1   // soltou: o bloco cai
                }
                if (grabbed == SET_OPT) {
                    if (optHeld >= 0 && !tapCancelled &&
                        SystemClock.uptimeMillis() - grabStartTime <= TAP_MAX_MS
                    ) {
                        handStyle = optHeld   // pinça rápida no cartão: escolhe esse estilo
                    }
                    optHeld = -1
                }
                if (grabbed == PANEL_CONTENT) {
                    emitWebTouch(MotionEvent.ACTION_UP, lastWebPX, lastWebPY)
                }
                grabbed = NONE
                applyGeometry(iw, ih)
            } else {
                // Onde a pinça deveria estar se ela estivesse acompanhando a peça segurada.
                val expectedX: Float
                val expectedY: Float
                when (grabbed) {
                    HANDLE -> {
                        expectedX = barCX - grabOffsetX
                        expectedY = barCY - grabOffsetY
                    }
                    PANEL_HANDLE -> {
                        expectedX = panelLeft + panelW / 2f - grabOffsetX
                        expectedY = panelTop + panelH / 2f - grabOffsetY
                    }
                    PANEL_CONTENT -> {
                        expectedX = lastWebPX
                        expectedY = lastWebPY
                    }
                    GAME_BLOCK -> {
                        val b = gameHeld
                        expectedX = panelLeft + (gX[b] + gW[b] / 2f) * panelW - grabOffsetX
                        expectedY = panelTop + (gY[b] + GAME_BLOCK_H / 2f) * panelW - grabOffsetY
                    }
                    else -> {
                        expectedX = grabStartX
                        expectedY = grabStartY
                    }
                }
                var idx = 0
                var bestDist = Float.MAX_VALUE
                for (p in 0 until pinchCount) {
                    val d = hypot(pinchX[p] - expectedX, pinchY[p] - expectedY)
                    if (d < bestDist) { bestDist = d; idx = p }
                }

                when (grabbed) {
                    HANDLE -> {
                        val cx = pinchX[idx] + grabOffsetX
                        val cy = pinchY[idx] + grabOffsetY
                        if (worldActive) {
                            unproject(cx, cy, iw, ih, barDir)
                        } else {
                            val halfW = min(barW / 2f / iw, 0.49f)
                            barNX = (cx / iw).coerceIn(halfW, 1f - halfW)
                            barNY = (cy / ih).coerceIn(0.10f, 0.82f)
                        }
                    }
                    PANEL_HANDLE -> {
                        val m = openMenu
                        if (m != NONE) {
                            val cx = pinchX[idx] + grabOffsetX
                            val cy = pinchY[idx] + grabOffsetY
                            panelMoved[m] = true
                            if (worldActive) {
                                unproject(cx, cy, iw, ih, panelDir[m])
                            } else {
                                panelNX[m] = (cx / iw).coerceIn(0f, 1f)
                                panelNY[m] = (cy / ih).coerceIn(0f, 1f)
                            }
                        }
                    }
                    GAME_BLOCK -> {
                        val b = gameHeld
                        if (b >= 0) {
                            val cx = pinchX[idx] + grabOffsetX
                            val cy = pinchY[idx] + grabOffsetY
                            gX[b] = ((cx - panelLeft) / panelW - gW[b] / 2f).coerceIn(0f, 1f - gW[b])
                            gY[b] = ((cy - panelTop) / panelW - GAME_BLOCK_H / 2f)
                                .coerceIn(0f, GAME_ASPECT - GAME_BLOCK_H)
                            gVX[b] = 0f
                            gVY[b] = 0f
                        }
                    }
                    PANEL_CONTENT -> {
                        lastWebPX = pinchX[idx].coerceIn(panelLeft, panelLeft + panelW)
                        lastWebPY = pinchY[idx].coerceIn(panelTop, panelTop + panelH)
                        emitWebTouch(MotionEvent.ACTION_MOVE, lastWebPX, lastWebPY)
                    }
                    else -> {
                        // Segurando um ícone: ele não se move; se a mão se afastar, cancela o toque.
                        if (!tapCancelled &&
                            hypot(pinchX[idx] - grabStartX, pinchY[idx] - grabStartY) > iconS * DRAG_START
                        ) {
                            tapCancelled = true
                        }
                    }
                }
                applyGeometry(iw, ih)
            }
        }

        if (isGameOpen()) {
            stepGame()
        } else {
            gameLastMs = 0L
        }

        for (i in 0 until ICON_COUNT) {
            iconState[i] = if (grabbed == i) 2 else if (iconTouch[i]) 1 else 0
        }
        handleState = if (grabbed == HANDLE) 2 else if (handleTouch) 1 else 0
        pHandleState = if (grabbed == PANEL_HANDLE) 2 else if (pHandleTouch) 1 else 0
    }

    /** Coloca os blocos no alto, espalhados; eles caem sozinhos para o chão. */
    private fun resetGame() {
        for (i in 0 until GAME_BLOCKS) {
            gX[i] = 0.01f + i * 0.2f
            gY[i] = 0.02f + (i % 2) * 0.12f
            gVX[i] = 0f
            gVY[i] = 0f
            gSupport[i] = -2
            gLevel[i] = 0
        }
        gameHeld = -1
        gameReady = true
        gameLastMs = 0L
        gameTower = 0
    }

    /** Um passo do jogo: gravidade, blocos que empilham, escorregam e a contagem da torre. */
    private fun stepGame() {
        if (!gameReady) resetGame()
        val now = SystemClock.uptimeMillis()
        val dt = if (gameLastMs == 0L) 0.016f else ((now - gameLastMs) / 1000f).coerceIn(0.001f, 0.05f)
        gameLastMs = now
        val bh = GAME_BLOCK_H
        val floor = GAME_ASPECT
        val holding = grabbed == GAME_BLOCK && gameHeld >= 0

        // Ordem: do bloco mais baixo para o mais alto (os de baixo se acertam primeiro).
        for (k in 0 until GAME_BLOCKS) gOrder[k] = k
        for (a in 1 until GAME_BLOCKS) {
            val v = gOrder[a]
            var b = a - 1
            while (b >= 0 && gY[gOrder[b]] < gY[v]) {
                gOrder[b + 1] = gOrder[b]
                b--
            }
            gOrder[b + 1] = v
        }

        for (k in 0 until GAME_BLOCKS) {
            val i = gOrder[k]
            if (holding && i == gameHeld) {
                gSupport[i] = -2
                continue
            }
            var supportTop = floor
            var support = -1
            val ci = gX[i] + gW[i] / 2f
            for (j in 0 until GAME_BLOCKS) {
                if (j == i) continue
                val ov = min(gX[i] + gW[i], gX[j] + gW[j]) - max(gX[i], gX[j])
                if (ov <= 0f) continue
                if (gY[j] < gY[i] + bh * 0.5f) continue          // o outro não está embaixo de mim
                if (ci >= gX[j] && ci <= gX[j] + gW[j]) {
                    // Meu centro está sobre ele: ele me segura.
                    if (gY[j] < supportTop) {
                        supportTop = gY[j]
                        support = j
                    }
                } else if (gY[i] + bh >= gY[j] - 0.01f) {
                    // Só a pontinha encosta: escorrega para o lado e cai.
                    gVX[i] = if (ci < gX[j] + gW[j] / 2f) -GAME_SLIDE else GAME_SLIDE
                }
            }

            if (gY[i] + bh < supportTop - 0.0005f) {
                gVY[i] = min(gVY[i] + GAME_GRAVITY * dt, 2.0f)
                var ny = gY[i] + gVY[i] * dt
                if (ny + bh >= supportTop) {
                    ny = supportTop - bh
                    gVY[i] = 0f
                    gSupport[i] = support
                } else {
                    gSupport[i] = -2
                }
                gY[i] = ny
            } else {
                gY[i] = supportTop - bh
                gVY[i] = 0f
                gSupport[i] = support
            }

            gX[i] = (gX[i] + gVX[i] * dt).coerceIn(0f, 1f - gW[i])
            gVX[i] *= max(0f, 1f - 6f * dt)
            if (abs(gVX[i]) < 0.01f) gVX[i] = 0f
        }

        // Blocos lado a lado não ficam um dentro do outro (o bloco na sua mão empurra os outros).
        for (a in 0 until GAME_BLOCKS) {
            for (b in a + 1 until GAME_BLOCKS) {
                val ovx = min(gX[a] + gW[a], gX[b] + gW[b]) - max(gX[a], gX[b])
                if (ovx <= 0f) continue
                val ovy = min(gY[a], gY[b]) + bh - max(gY[a], gY[b])
                if (ovy <= bh * 0.5f) continue
                val push = ovx * min(1f, 12f * dt)
                val sa = if (gX[a] + gW[a] / 2f < gX[b] + gW[b] / 2f) -1f else 1f
                val wa: Float
                val wb: Float
                if (holding && a == gameHeld) { wa = 0f; wb = 1f }
                else if (holding && b == gameHeld) { wa = 1f; wb = 0f }
                else if (abs(gY[a] - gY[b]) < bh * 0.3f) { wa = 0.5f; wb = 0.5f }
                else if (gY[a] < gY[b]) { wa = 1f; wb = 0f }
                else { wa = 0f; wb = 1f }
                gX[a] = (gX[a] + sa * push * wa).coerceIn(0f, 1f - gW[a])
                gX[b] = (gX[b] - sa * push * wb).coerceIn(0f, 1f - gW[b])
            }
        }

        // Altura da torre: quantos blocos seguidos estão empilhados sobre o chão.
        var tower = 0
        for (k in 0 until GAME_BLOCKS) {
            val i = gOrder[k]
            val s = gSupport[i]
            gLevel[i] = when {
                holding && i == gameHeld -> 0
                s == -1 -> 1
                s >= 0 && gLevel[s] > 0 -> gLevel[s] + 1
                else -> 0
            }
            if (gLevel[i] > tower) tower = gLevel[i]
        }
        gameTower = tower
        if (tower > gameBest) gameBest = tower
    }

    /** Desenha chão, blocos, placar e o botão de reiniciar dentro do menu do jogo. */
    private fun drawGame(canvas: Canvas) {
        val bw = panelW
        val blockH = GAME_BLOCK_H * bw

        // Chão.
        fillPaint.color = Color.argb(90, 255, 255, 255)
        canvas.drawRect(panelLeft + bw * 0.01f, panelTop + panelH - bw * 0.012f, panelLeft + bw * 0.99f, panelTop + panelH, fillPaint)

        // Blocos.
        val radius = bw * 0.012f
        for (i in 0 until GAME_BLOCKS) {
            val l = panelLeft + gX[i] * bw
            val t = panelTop + gY[i] * bw
            rect.set(l, t, l + gW[i] * bw, t + blockH)
            fillPaint.color = gColors[i]
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
            canvas.drawRoundRect(rect, radius, radius, outlinePaint)
            if (grabbed == GAME_BLOCK && gameHeld == i) {
                canvas.drawRoundRect(rect, radius, radius, pressRingPaint)
            } else if (gTouch[i]) {
                fillPaint.color = Color.argb(70, 255, 255, 255)
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
            }
        }

        // Placar.
        textPaint.textSize = bw * 0.055f
        textPaint.textAlign = Paint.Align.LEFT
        val label = if (gameTower >= GAME_BLOCKS) "Torre completa!" else "Torre: $gameTower/$GAME_BLOCKS"
        canvas.drawText(label, panelLeft + bw * 0.04f, panelTop + bw * 0.092f, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("Recorde: $gameBest", resetCX - resetR * 1.5f, panelTop + bw * 0.092f, textPaint)

        // Botão de reiniciar (círculo com uma seta).
        fillPaint.color = when {
            grabbed == GAME_RESET -> Color.YELLOW
            resetTouch -> Color.WHITE
            else -> Color.argb(210, 235, 235, 235)
        }
        canvas.drawCircle(resetCX, resetCY, resetR, fillPaint)
        val rr = resetR * 0.5f
        arrowPaint.strokeWidth = max(resetR * 0.18f, 2f)
        rect.set(resetCX - rr, resetCY - rr, resetCX + rr, resetCY + rr)
        canvas.drawArc(rect, -45f, 270f, false, arrowPaint)
        // Ponta da seta no fim do arco (ângulo 225 graus, sentido horário).
        val ex = resetCX - rr * 0.7071f
        val ey = resetCY - rr * 0.7071f
        val tx = 0.7071f    // direção do arco nesse ponto
        val ty = -0.7071f
        val nx = -0.7071f   // para fora do círculo
        val ny = -0.7071f
        arrowPath.reset()
        arrowPath.moveTo(ex + tx * resetR * 0.45f, ey + ty * resetR * 0.45f)
        arrowPath.lineTo(ex + nx * resetR * 0.3f, ey + ny * resetR * 0.3f)
        arrowPath.lineTo(ex - nx * resetR * 0.3f, ey - ny * resetR * 0.3f)
        arrowPath.close()
        fillPaint.color = Color.rgb(40, 40, 40)
        canvas.drawPath(arrowPath, fillPaint)
    }

    /** Menu de configurações: título e dois cartões para escolher o estilo da mão. */
    private fun drawSettings(canvas: Canvas) {
        val bw = panelW
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.WHITE
        textPaint.textSize = bw * 0.062f
        canvas.drawText("Configurações", panelLeft + bw * 0.05f, panelTop + panelH * 0.17f, textPaint)
        textPaint.textSize = bw * 0.045f
        textPaint.color = Color.argb(210, 255, 255, 255)
        canvas.drawText("Estilo da mão", panelLeft + bw * 0.05f, panelTop + panelH * 0.34f, textPaint)

        val radius = gap * 1.2f
        for (o in 0 until STYLE_COUNT) {
            rect.set(optL[o], optT, optL[o] + optW, optT + optH)
            val selected = handStyle == o
            fillPaint.color = if (selected) Color.argb(120, 67, 160, 71) else Color.argb(110, 90, 90, 90)
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
            if (optTouch[o]) {
                fillPaint.color = Color.argb(60, 255, 255, 255)
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
            }
            if (grabbed == SET_OPT && optHeld == o) {
                canvas.drawRoundRect(rect, radius, radius, pressRingPaint)
            } else {
                pinchPaint.color = if (selected) iconColors[SETTINGS_ICON] else Color.argb(100, 255, 255, 255)
                canvas.drawRoundRect(rect, radius, radius, pinchPaint)
                pinchPaint.color = Color.YELLOW
            }

            // Mini desenho de um dedo no estilo.
            val cx = optL[o] + optW / 2f
            val cy = optT + optH * 0.38f
            val sz = optH * 0.22f
            val x0 = cx - sz
            val y0 = cy + sz * 0.6f
            val x1 = cx
            val y1 = cy - sz * 0.2f
            val x2 = cx + sz
            val y2 = cy - sz * 0.8f
            if (o == STYLE_QUEST) {
                questPaint.style = Paint.Style.STROKE
                questPaint.color = QUEST_RIM
                questPaint.strokeWidth = sz * 0.75f
                canvas.drawLine(x0, y0, x1, y1, questPaint)
                canvas.drawLine(x1, y1, x2, y2, questPaint)
                questPaint.color = QUEST_BODY
                questPaint.strokeWidth = sz * 0.55f
                canvas.drawLine(x0, y0, x1, y1, questPaint)
                canvas.drawLine(x1, y1, x2, y2, questPaint)
            } else {
                canvas.drawLine(x0, y0, x1, y1, linePaint)
                canvas.drawLine(x1, y1, x2, y2, linePaint)
                canvas.drawCircle(x0, y0, sz * 0.16f, pointPaint)
                canvas.drawCircle(x1, y1, sz * 0.16f, pointPaint)
                canvas.drawCircle(x2, y2, sz * 0.16f, pointPaint)
            }

            // Nome do estilo.
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = Color.WHITE
            textPaint.textSize = bw * 0.05f
            canvas.drawText(if (o == STYLE_QUEST) "Meta Quest" else "Normal", cx, optT + optH * 0.88f, textPaint)
        }
        textPaint.color = Color.WHITE
    }

    /** Desenha (imagem opcional +) menu + barra + esqueleto dentro de uma região da tela. */
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
            drawPanel(canvas)
            drawBar(canvas)
            for (i in 0 until ICON_COUNT) drawIcon(canvas, i)
            // Anel amarelo no ponto onde a pinça está fechada.
            for (p in 0 until pinchCount) {
                canvas.drawCircle(pinchX[p], pinchY[p], max(iconS * 0.25f, 8f), pinchPaint)
            }
            canvas.restore()
        }

        val res = result ?: return
        for (hand in res.landmarks()) {
            if (hand.size < 21) continue
            for (k in 0 until 21) {
                lmX[k] = hand[k].x() * iw * scale + offsetX
                lmY[k] = hand[k].y() * ih * scale + offsetY
            }
            if (handStyle == STYLE_QUEST) drawQuestHand(canvas) else drawNormalHand(canvas)
        }
    }

    /** Estilo normal: linhas brancas e pontos azuis. */
    private fun drawNormalHand(canvas: Canvas) {
        HandLandmarker.HAND_CONNECTIONS.forEach { connection ->
            canvas.drawLine(
                lmX[connection.start()], lmY[connection.start()],
                lmX[connection.end()], lmY[connection.end()],
                linePaint
            )
        }
        for (k in 0 until 21) {
            canvas.drawCircle(lmX[k], lmY[k], 9f, pointPaint)
        }
    }

    /** Estilo Meta Quest: mão cheia, translúcida, com borda clara e um pontinho branco no indicador. */
    private fun drawQuestHand(canvas: Canvas) {
        val hs = hypot(lmX[WRIST] - lmX[MIDDLE_MCP], lmY[WRIST] - lmY[MIDDLE_MCP])
        if (hs < 4f) return
        val rim = hs * 0.035f

        // Tudo é desenhado opaco numa camada e a camada inteira fica translúcida (sem escurecer onde se cruza).
        canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), QUEST_ALPHA)
        for (pass in 0..1) {
            val extra = if (pass == 0) rim * 2f else 0f    // 1ª passada: borda clara; 2ª: corpo
            questPaint.color = if (pass == 0) QUEST_RIM else QUEST_BODY

            // Palma da mão.
            questPaint.style = Paint.Style.FILL_AND_STROKE
            questPaint.strokeWidth = hs * 0.12f + extra
            palmPath.reset()
            palmPath.moveTo(lmX[0], lmY[0])
            palmPath.lineTo(lmX[1], lmY[1])
            palmPath.lineTo(lmX[5], lmY[5])
            palmPath.lineTo(lmX[9], lmY[9])
            palmPath.lineTo(lmX[13], lmY[13])
            palmPath.lineTo(lmX[17], lmY[17])
            palmPath.close()
            canvas.drawPath(palmPath, questPaint)

            // Dedos: polegar (1 a 4), indicador (5 a 8), médio, anelar e mindinho. Afinam até a ponta.
            questPaint.style = Paint.Style.STROKE
            for (f in 0 until 5) {
                val base = 1 + f * 4
                var w = hs * (if (f == 0) 0.19f else if (f == 4) 0.15f else 0.17f)
                for (k in 0 until 3) {
                    questPaint.strokeWidth = w + extra
                    canvas.drawLine(lmX[base + k], lmY[base + k], lmX[base + k + 1], lmY[base + k + 1], questPaint)
                    w *= 0.9f
                }
            }
        }
        canvas.restore()

        // Pontinho branco na ponta do indicador (é ele que encosta nos ícones).
        fillPaint.color = Color.WHITE
        canvas.drawCircle(lmX[INDEX_TIP], lmY[INDEX_TIP], hs * 0.05f, fillPaint)
    }

    /** Barrinha branca (alça) usada para mover a barra ou um menu. */
    private fun drawHandle(canvas: Canvas, cx: Float, cy: Float, w: Float, state: Int) {
        val h = if (state == 0) handleH else handleH * 1.8f
        fillPaint.color = if (state == 2) Color.YELLOW else Color.WHITE
        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, fillPaint)
    }

    private fun drawBar(canvas: Canvas) {
        // Fundo da barra (escuro e meio transparente).
        fillPaint.color = Color.argb(130, 30, 30, 30)
        rect.set(barLeft, barTop, barLeft + barW, barTop + barH)
        val r = gap * 1.6f
        canvas.drawRoundRect(rect, r, r, fillPaint)

        // Barrinha branca embaixo: serve para mover a barra.
        drawHandle(canvas, handleCX, handleCY, handleW, handleState)

        // Bolinha branca embaixo do ícone que está com o menu aberto.
        if (openMenu != NONE) {
            fillPaint.color = Color.WHITE
            canvas.drawCircle(iconCX[openMenu], barTop + barH - gap * 0.5f, gap * 0.28f, fillPaint)
        }
    }

    /** Menu vazio do ícone que foi tocado (por enquanto só a janela, sem nada dentro). */
    private fun drawPanel(canvas: Canvas) {
        val m = openMenu
        if (m == NONE) return
        rect.set(panelLeft, panelTop, panelLeft + panelW, panelTop + panelH)
        val r = gap * 1.6f
        if (isVrWeb()) {
            // YouTube do modo VR Box: desenha o último quadro (ou preto, enquanto carrega).
            val webBmp = webFrame
            if (webBmp != null) {
                canvas.drawBitmap(webBmp, webSrc, rect, framePaint)
            } else {
                fillPaint.color = Color.BLACK
                canvas.drawRect(rect, fillPaint)
            }
        } else if (!isWebPanel()) {
            fillPaint.color = Color.argb(150, 20, 20, 20)
            canvas.drawRoundRect(rect, r, r, fillPaint)
        }
        // Borda com a cor do ícone que abriu o menu.
        panelBorderPaint.color = iconColors[m]
        canvas.drawRoundRect(rect, r, r, panelBorderPaint)

        if (isGameOpen()) drawGame(canvas)
        if (isSettingsOpen()) drawSettings(canvas)

        // Barrinha branca embaixo do menu: serve para mover só este menu.
        drawHandle(canvas, pHandleCX, pHandleCY, pHandleW, pHandleState)
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
            canvas.drawRoundRect(rect, radius, radius, pressRingPaint)
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

        const val TAP_MAX_MS = 900L     // pinça mais curta que isso (sem a mão se afastar) = toque
        const val DRAG_START = 0.4f     // quanto a mão pode se afastar (em tamanhos de ícone) e ainda ser toque

        const val TAN_HALF_FOV = 0.6494f   // tan(33 graus): câmera com ~66 graus no lado maior da imagem
        const val OFFSCREEN = 100000f      // "bem longe da tela"

        const val ICON_COUNT = 4
        const val YOUTUBE_ICON = 1   // o ícone vermelho abre o YouTube
        const val SETTINGS_ICON = 0  // o ícone verde abre as configurações
        const val GAME_ICON = 3      // o ícone amarelo abre o jogo de empilhar blocos

        // Estilos da mão.
        const val STYLE_NORMAL = 0
        const val STYLE_QUEST = 1
        const val STYLE_COUNT = 2
        const val QUEST_ALPHA = 175   // transparência da mão estilo Meta Quest (0 a 255)
        val QUEST_RIM = Color.rgb(228, 238, 255)    // borda clara
        val QUEST_BODY = Color.rgb(150, 172, 210)   // corpo azul acinzentado
        const val MAX_HANDS = 4
        const val NONE = -1
        const val HANDLE = ICON_COUNT          // barrinha que move a barra
        const val PANEL_HANDLE = ICON_COUNT + 1  // barrinha que move o menu aberto
        const val PANEL_CONTENT = ICON_COUNT + 2 // pinça dentro do YouTube (clicar/rolar)
        const val GAME_BLOCK = ICON_COUNT + 3    // pinça segurando um bloco do jogo
        const val GAME_RESET = ICON_COUNT + 4    // pinça no botão de reiniciar o jogo
        const val SET_OPT = ICON_COUNT + 5       // pinça num cartão de estilo da mão

        // Jogo de empilhar blocos (unidade: 1,0 = largura do menu).
        const val GAME_BLOCKS = 5
        const val GAME_BLOCK_H = 0.105f   // altura de cada bloco
        const val GAME_ASPECT = 0.66f     // altura do menu do jogo / largura
        const val GAME_GRAVITY = 2.6f
        const val GAME_SLIDE = 0.5f       // velocidade de quem escorrega da ponta de outro bloco
        const val GAME_BAR_Y = 0.80f      // a barra desce até aqui ao abrir o jogo
    }
}
