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
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
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
 *  - Menu amarelo: mini jogo 3D de empilhar blocos numa mesa. Pinça num bloco: ele sobe e você leva
 *    até onde quer (a marca amarela na mesa mostra onde ele vai cair); solta para cair. Para ver a mesa
 *    de outros lados: vire a cabeça (a mesa "gira" junto) ou faça pinça na mesa e arraste. O botão
 *    redondo no canto reinicia.
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

    // ---- Mini jogo 3D: empilhar blocos numa mesa que você olha de vários lados (4º ícone, amarelo) ----
    // Eixos da cena: x = direita, y = cima, z = em direção a quem olha (na posição inicial da câmera).
    // A mesa vai de -0,5 a 0,5 em x e em z, e o tampo da mesa é y = 0.
    private val gCX = FloatArray(GAME_BLOCKS)        // centro do bloco em x
    private val gCZ = FloatArray(GAME_BLOCKS)        // centro do bloco em z
    private val gBY = FloatArray(GAME_BLOCKS)        // altura da base do bloco (0 = em cima da mesa)
    private val gSX = floatArrayOf(0.26f, 0.22f, 0.19f, 0.24f, 0.17f)   // largura de cada bloco (x)
    private val gSZ = floatArrayOf(0.22f, 0.26f, 0.20f, 0.17f, 0.24f)   // profundidade de cada bloco (z)
    private val gVX = FloatArray(GAME_BLOCKS)
    private val gVZ = FloatArray(GAME_BLOCKS)
    private val gVY = FloatArray(GAME_BLOCKS)        // velocidade de queda (para baixo)
    private val gSupport = IntArray(GAME_BLOCKS)     // em cima de quem (-1 = mesa, -2 = no ar)
    private val gLevel = IntArray(GAME_BLOCKS)       // andar na torre (0 = fora da torre)
    private val gOrder = IntArray(GAME_BLOCKS)
    private val gDraw = IntArray(GAME_BLOCKS)
    private val gTouch = BooleanArray(GAME_BLOCKS)   // dedo indicador encostando no bloco
    private val gColors = intArrayOf(
        Color.rgb(239, 83, 80),
        Color.rgb(255, 202, 40),
        Color.rgb(66, 165, 245),
        Color.rgb(171, 71, 188),
        Color.rgb(102, 187, 106)
    )
    // Retângulo de cada bloco na tela (para pegar com a pinça) e a distância dele até a câmera.
    private val gMinX = FloatArray(GAME_BLOCKS)
    private val gMinY = FloatArray(GAME_BLOCKS)
    private val gMaxX = FloatArray(GAME_BLOCKS)
    private val gMaxY = FloatArray(GAME_BLOCKS)
    private val gDepth = FloatArray(GAME_BLOCKS)
    private var gameHeld = -1
    private var holdDX = 0f
    private var holdDZ = 0f
    private var gameReady = false
    private var gameLastMs = 0L
    private var gameTower = 0
    private var gameBest = 0
    private var lastGameX = 0f
    private var lastGameY = 0f
    private var orbitStartYaw = 0f
    private var orbitStartElev = 0f

    // Câmera do jogo: gira em volta da mesa. O lado de onde você olha vem da cabeça + do arrastar.
    private var camYawManual = GAME_YAW0
    private var camElevManual = GAME_ELEV0
    private var camYaw = GAME_YAW0
    private var camElev = GAME_ELEV0
    private var headRefReady = false
    private var headYawAcc = 0f
    private var headYawLast = 0f
    private var headPitch0 = 0f
    private val camE = FloatArray(3)    // posição da câmera
    private val camF = FloatArray(3)    // para frente
    private val camR = FloatArray(3)    // para a direita
    private val camU = FloatArray(3)    // para cima
    private var vpCX = 0f
    private var vpCY = 0f
    private var vpF = 1f
    private val tmp3 = FloatArray(3)
    private val hit2 = FloatArray(2)
    private val sp = FloatArray(2)
    private val pq = FloatArray(8)
    private val facePath = Path()
    private val gridPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(50, 255, 255, 255)
    }

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
        // Com o jogo aberto, a barra e o menu ficam presos à tela (a mesa gira com a cabeça, eles não).
        worldActive = worldMode && rot != null && !isGameOpen()
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
            panelW = min(u * 0.68f, iw * 0.9f)
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

    /** Ao abrir o jogo, a barra fica presa à tela e desce, para sobrar espaço em cima (a janela do jogo é grande). */
    private fun lowerBarForGame(iw: Float, ih: Float) {
        val halfW = min(barW / 2f / iw, 0.49f)
        barNX = (barCX / iw).coerceIn(halfW, 1f - halfW)
        barNY = (max(barCY, ih * GAME_BAR_Y) / ih).coerceIn(0.10f, 0.82f)
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
                    val hb = pickBlock(fx, fy, 0f)
                    if (hb >= 0) gTouch[hb] = true
                    if (abs(fx - resetCX) <= resetR * 1.4f && abs(fy - resetCY) <= resetR * 1.4f) resetTouch = true
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

        if ((grabbed == GAME_BLOCK || grabbed == GAME_RESET || grabbed == GAME_ORBIT) &&
            (!isGameOpen() || (grabbed == GAME_BLOCK && gameHeld < 0))
        ) {
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
                // Pinça no botão de reiniciar, num bloco, ou na mesa (pinça e arrastar gira a mesa).
                val tol = iconS * 0.15f
                for (p in 0 until pinchCount) {
                    if (abs(pinchX[p] - resetCX) <= resetR * 1.4f && abs(pinchY[p] - resetCY) <= resetR * 1.4f) {
                        best = GAME_RESET
                        bestIdx = p
                        break
                    }
                    val b = pickBlock(pinchX[p], pinchY[p], tol)
                    if (b >= 0) {
                        best = GAME_BLOCK
                        bestIdx = p
                        gameHeld = b
                        break
                    }
                    if (pinchX[p] >= panelLeft && pinchX[p] <= panelLeft + panelW &&
                        pinchY[p] >= panelTop && pinchY[p] <= panelTop + panelH
                    ) {
                        best = GAME_ORBIT
                        bestIdx = p
                        break
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
                        grabOffsetX = 0f
                        grabOffsetY = 0f
                        lastGameX = pinchX[bestIdx]
                        lastGameY = pinchY[bestIdx]
                        gVX[b] = 0f
                        gVZ[b] = 0f
                        gVY[b] = 0f
                        // O bloco sobe reto; depois ele acompanha o movimento da mão no plano da altura de carregar.
                        if (gameRayPlane(pinchX[bestIdx], pinchY[bestIdx], GAME_CARRY_Y + GAME_BH / 2f, hit2)) {
                            holdDX = gCX[b] - hit2[0]
                            holdDZ = gCZ[b] - hit2[1]
                        } else {
                            holdDX = 0f
                            holdDZ = 0f
                        }
                    }
                    GAME_ORBIT -> {
                        grabOffsetX = 0f
                        grabOffsetY = 0f
                        lastGameX = pinchX[bestIdx]
                        lastGameY = pinchY[bestIdx]
                        orbitStartYaw = camYawManual
                        orbitStartElev = camElevManual
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
                    val prevMenu = openMenu
                    openMenu = if (openMenu == grabbed) NONE else grabbed
                    if (prevMenu == GAME_ICON && openMenu != GAME_ICON) anchorsPending = true   // a barra volta para a sua frente
                    if (openMenu == GAME_ICON) headRefReady = false                             // olhar daqui é o lado "de frente"
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
                    GAME_BLOCK, GAME_ORBIT -> {
                        expectedX = lastGameX
                        expectedY = lastGameY
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
                        lastGameX = pinchX[idx]
                        lastGameY = pinchY[idx]
                        if (b >= 0 && gameRayPlane(pinchX[idx], pinchY[idx], GAME_CARRY_Y + GAME_BH / 2f, hit2)) {
                            val lx = TABLE_HALF - gSX[b] / 2f
                            val lz = TABLE_HALF - gSZ[b] / 2f
                            gCX[b] = (hit2[0] + holdDX).coerceIn(-lx, lx)
                            gCZ[b] = (hit2[1] + holdDZ).coerceIn(-lz, lz)
                        }
                    }
                    GAME_ORBIT -> {
                        lastGameX = pinchX[idx]
                        lastGameY = pinchY[idx]
                        camYawManual = orbitStartYaw + (pinchX[idx] - grabStartX) / panelW * ORBIT_YAW_PER_PANEL
                        camElevManual = (orbitStartElev + (pinchY[idx] - grabStartY) / panelW * ORBIT_ELEV_PER_PANEL)
                            .coerceIn(GAME_ELEV_MIN, GAME_ELEV_MAX)
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
            stepGame(iw, ih)
        } else {
            gameLastMs = 0L
        }

        for (i in 0 until ICON_COUNT) {
            iconState[i] = if (grabbed == i) 2 else if (iconTouch[i]) 1 else 0
        }
        handleState = if (grabbed == HANDLE) 2 else if (handleTouch) 1 else 0
        pHandleState = if (grabbed == PANEL_HANDLE) 2 else if (pHandleTouch) 1 else 0
    }

    /** Espalha os blocos pela mesa (eles caem de leve) e volta a câmera para o lado de frente. */
    private fun resetGame() {
        val sx = floatArrayOf(-0.30f, 0.05f, 0.32f, -0.28f, 0.22f)
        val sz = floatArrayOf(-0.28f, -0.30f, -0.22f, 0.25f, 0.28f)
        for (i in 0 until GAME_BLOCKS) {
            gCX[i] = sx[i]
            gCZ[i] = sz[i]
            gBY[i] = 0.15f + (i % 2) * 0.10f
            gVX[i] = 0f
            gVZ[i] = 0f
            gVY[i] = 0f
            gSupport[i] = -2
            gLevel[i] = 0
        }
        gameHeld = -1
        gameReady = true
        gameLastMs = 0L
        gameTower = 0
        camYawManual = GAME_YAW0
        camElevManual = GAME_ELEV0
        camYaw = GAME_YAW0
        camElev = GAME_ELEV0
        headRefReady = false
    }

    // ---------- câmera e projeção ----------

    /** Atualiza a câmera: a cabeça (se houver sensor) e o arrastar da mesa decidem de que lado você olha. */
    private fun updateGameCamera(dt: Float, iw: Float, ih: Float) {
        var yawTarget = camYawManual
        var elevTarget = camElevManual
        if (rot != null) {
            unproject(iw * 0.5f, ih * 0.5f, iw, ih, tmp3)       // para onde você olha, no mundo
            val hy = atan2(tmp3[0], tmp3[1])
            val hp = asin(tmp3[2].coerceIn(-1f, 1f))
            if (!headRefReady) {
                headRefReady = true
                headYawLast = hy
                headYawAcc = 0f
                headPitch0 = hp
            }
            var d = hy - headYawLast
            while (d > PI_F) d -= TWO_PI_F
            while (d < -PI_F) d += TWO_PI_F
            headYawAcc += d
            headYawLast = hy
            // Virar a cabeça para a direita leva a câmera para o lado esquerdo da mesa (você "contorna" a mesa).
            yawTarget = camYawManual - headYawAcc * HEAD_GAIN
            // Olhar mais para baixo = ver a mesa mais de cima.
            elevTarget = camElevManual + (headPitch0 - hp)
        }
        elevTarget = elevTarget.coerceIn(GAME_ELEV_MIN, GAME_ELEV_MAX)
        val k = min(1f, dt * 10f)
        camYaw += (yawTarget - camYaw) * k
        camElev += (elevTarget - camElev) * k

        val ce = cos(camElev)
        val se = sin(camElev)
        val sy = sin(camYaw)
        val cy = cos(camYaw)
        camE[0] = GAME_DIST * sy * ce
        camE[1] = GAME_TARGET_Y + GAME_DIST * se
        camE[2] = GAME_DIST * cy * ce
        camF[0] = -sy * ce
        camF[1] = -se
        camF[2] = -cy * ce
        var rx = -camF[2]
        var rz = camF[0]
        val rn = sqrt(rx * rx + rz * rz)
        if (rn > 1e-6f) {
            rx /= rn
            rz /= rn
        }
        camR[0] = rx
        camR[1] = 0f
        camR[2] = rz
        camU[0] = -rz * camF[1]
        camU[1] = rz * camF[0] - rx * camF[2]
        camU[2] = rx * camF[1]

        vpCX = panelLeft + panelW / 2f
        vpCY = panelTop + panelH * 0.57f
        vpF = panelW * GAME_SCALE * GAME_DIST
    }

    /** Ponto da cena -> ponto na tela (em out). Devolve a distância até a câmera, ou -1 se estiver atrás dela. */
    private fun gameProject(x: Float, y: Float, z: Float, out: FloatArray): Float {
        val vx = x - camE[0]
        val vy = y - camE[1]
        val vz = z - camE[2]
        val zc = vx * camF[0] + vy * camF[1] + vz * camF[2]
        if (zc < 0.2f) {
            out[0] = vpCX
            out[1] = vpCY
            return -1f
        }
        out[0] = vpCX + vpF * (vx * camR[0] + vy * camR[1] + vz * camR[2]) / zc
        out[1] = vpCY - vpF * (vx * camU[0] + vy * camU[1] + vz * camU[2]) / zc
        return zc
    }

    /** Ponto da tela -> ponto (x, z) da cena na altura planeY. Devolve false se o raio não chega lá. */
    private fun gameRayPlane(px: Float, py: Float, planeY: Float, out: FloatArray): Boolean {
        val a = (px - vpCX) / vpF
        val b = -(py - vpCY) / vpF
        val dx = camF[0] + a * camR[0] + b * camU[0]
        val dy = camF[1] + a * camR[1] + b * camU[1]
        val dz = camF[2] + a * camR[2] + b * camU[2]
        if (dy > -0.02f) return false
        val t = (planeY - camE[1]) / dy
        out[0] = camE[0] + t * dx
        out[1] = camE[2] + t * dz
        return true
    }

    /** Calcula o retângulo de cada bloco na tela (para pegar com a pinça). */
    private fun updateGameRects() {
        for (i in 0 until GAME_BLOCKS) {
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (c in 0 until 8) {
                val x = gCX[i] + (if ((c and 1) == 0) -gSX[i] else gSX[i]) / 2f
                val y = gBY[i] + (if ((c and 2) == 0) 0f else GAME_BH)
                val z = gCZ[i] + (if ((c and 4) == 0) -gSZ[i] else gSZ[i]) / 2f
                gameProject(x, y, z, sp)
                minX = min(minX, sp[0])
                maxX = max(maxX, sp[0])
                minY = min(minY, sp[1])
                maxY = max(maxY, sp[1])
            }
            gMinX[i] = minX
            gMaxX[i] = maxX
            gMinY[i] = minY
            gMaxY[i] = maxY
            gDepth[i] = gameProject(gCX[i], gBY[i] + GAME_BH / 2f, gCZ[i], sp)
        }
    }

    /** Qual bloco está debaixo do ponto (x, y) da tela? O mais perto da câmera ganha. -1 = nenhum. */
    private fun pickBlock(x: Float, y: Float, tol: Float): Int {
        var best = -1
        var bestDepth = Float.MAX_VALUE
        for (i in 0 until GAME_BLOCKS) {
            if (gDepth[i] <= 0f) continue
            if (x >= gMinX[i] - tol && x <= gMaxX[i] + tol &&
                y >= gMinY[i] - tol && y <= gMaxY[i] + tol &&
                gDepth[i] < bestDepth
            ) {
                best = i
                bestDepth = gDepth[i]
            }
        }
        return best
    }

    // ---------- física ----------

    /** Altura em que o bloco i pararia se fosse solto agora (topo do que está embaixo dele). */
    private fun gameLandingY(i: Int): Float {
        var top = 0f
        for (j in 0 until GAME_BLOCKS) {
            if (j == i) continue
            val ovx = min(gCX[i] + gSX[i] / 2f, gCX[j] + gSX[j] / 2f) - max(gCX[i] - gSX[i] / 2f, gCX[j] - gSX[j] / 2f)
            val ovz = min(gCZ[i] + gSZ[i] / 2f, gCZ[j] + gSZ[j] / 2f) - max(gCZ[i] - gSZ[i] / 2f, gCZ[j] - gSZ[j] / 2f)
            if (ovx > 0f && ovz > 0f) top = max(top, gBY[j] + GAME_BH)
        }
        return top
    }

    /** Um passo do jogo: câmera, gravidade, blocos que empilham, escorregam, e a contagem da torre. */
    private fun stepGame(iw: Float, ih: Float) {
        if (!gameReady) resetGame()
        val now = SystemClock.uptimeMillis()
        val dt = if (gameLastMs == 0L) 0.016f else ((now - gameLastMs) / 1000f).coerceIn(0.001f, 0.05f)
        gameLastMs = now
        updateGameCamera(dt, iw, ih)

        val bh = GAME_BH
        val holding = grabbed == GAME_BLOCK && gameHeld >= 0

        // Ordem: do bloco mais baixo para o mais alto (os de baixo se acertam primeiro).
        for (k in 0 until GAME_BLOCKS) gOrder[k] = k
        for (a in 1 until GAME_BLOCKS) {
            val v = gOrder[a]
            var b = a - 1
            while (b >= 0 && gBY[gOrder[b]] > gBY[v]) {
                gOrder[b + 1] = gOrder[b]
                b--
            }
            gOrder[b + 1] = v
        }

        for (k in 0 until GAME_BLOCKS) {
            val i = gOrder[k]
            val hx = gSX[i] / 2f
            val hz = gSZ[i] / 2f
            if (holding && i == gameHeld) {
                // Bloco na sua mão: sobe até a altura de carregar.
                gBY[i] += (GAME_CARRY_Y - gBY[i]) * min(1f, 12f * dt)
                gVX[i] = 0f
                gVZ[i] = 0f
                gVY[i] = 0f
                gSupport[i] = -2
                continue
            }
            var supportTop = 0f
            var support = -1
            for (j in 0 until GAME_BLOCKS) {
                if (j == i) continue
                val ovx = min(gCX[i] + hx, gCX[j] + gSX[j] / 2f) - max(gCX[i] - hx, gCX[j] - gSX[j] / 2f)
                val ovz = min(gCZ[i] + hz, gCZ[j] + gSZ[j] / 2f) - max(gCZ[i] - hz, gCZ[j] - gSZ[j] / 2f)
                if (ovx <= 0f || ovz <= 0f) continue
                val topJ = gBY[j] + bh
                if (topJ > gBY[i] + bh * 0.5f) continue          // o outro não está embaixo de mim
                val dx = gCX[i] - gCX[j]
                val dz = gCZ[i] - gCZ[j]
                if (abs(dx) <= gSX[j] / 2f && abs(dz) <= gSZ[j] / 2f) {
                    // Meu centro está sobre ele: ele me segura.
                    if (topJ > supportTop) {
                        supportTop = topJ
                        support = j
                    }
                } else if (gBY[i] <= topJ + 0.01f) {
                    // Só a pontinha encosta: escorrega para o lado mais próximo e cai.
                    if (abs(dx) / gSX[j] >= abs(dz) / gSZ[j]) {
                        gVX[i] = if (dx < 0f) -GAME_SLIDE else GAME_SLIDE
                    } else {
                        gVZ[i] = if (dz < 0f) -GAME_SLIDE else GAME_SLIDE
                    }
                }
            }

            if (gBY[i] > supportTop + 0.0005f) {
                gVY[i] = min(gVY[i] + GAME_GRAVITY * dt, 3f)
                var ny = gBY[i] - gVY[i] * dt
                if (ny <= supportTop) {
                    ny = supportTop
                    gVY[i] = 0f
                    gSupport[i] = support
                } else {
                    gSupport[i] = -2
                }
                gBY[i] = ny
            } else {
                gBY[i] = supportTop
                gVY[i] = 0f
                gSupport[i] = support
            }

            gCX[i] = (gCX[i] + gVX[i] * dt).coerceIn(-TABLE_HALF + hx, TABLE_HALF - hx)
            gCZ[i] = (gCZ[i] + gVZ[i] * dt).coerceIn(-TABLE_HALF + hz, TABLE_HALF - hz)
            val damp = max(0f, 1f - 6f * dt)
            gVX[i] *= damp
            gVZ[i] *= damp
            if (abs(gVX[i]) < 0.01f) gVX[i] = 0f
            if (abs(gVZ[i]) < 0.01f) gVZ[i] = 0f
        }

        // Blocos no mesmo nível não ficam um dentro do outro (o bloco na sua mão não empurra: ele voa por cima).
        for (a in 0 until GAME_BLOCKS) {
            for (b in a + 1 until GAME_BLOCKS) {
                val ovx = min(gCX[a] + gSX[a] / 2f, gCX[b] + gSX[b] / 2f) - max(gCX[a] - gSX[a] / 2f, gCX[b] - gSX[b] / 2f)
                val ovz = min(gCZ[a] + gSZ[a] / 2f, gCZ[b] + gSZ[b] / 2f) - max(gCZ[a] - gSZ[a] / 2f, gCZ[b] - gSZ[b] / 2f)
                if (ovx <= 0f || ovz <= 0f) continue
                val ovy = min(gBY[a], gBY[b]) + bh - max(gBY[a], gBY[b])
                if (ovy <= bh * 0.5f) continue
                val wa: Float
                val wb: Float
                if (abs(gBY[a] - gBY[b]) < bh * 0.3f) {
                    wa = 0.5f
                    wb = 0.5f
                } else if (gBY[a] > gBY[b]) {
                    wa = 1f
                    wb = 0f
                } else {
                    wa = 0f
                    wb = 1f
                }
                if (ovx <= ovz) {
                    val push = ovx * min(1f, 12f * dt)
                    val sa = if (gCX[a] < gCX[b]) -1f else 1f
                    gCX[a] = (gCX[a] + sa * push * wa).coerceIn(-TABLE_HALF + gSX[a] / 2f, TABLE_HALF - gSX[a] / 2f)
                    gCX[b] = (gCX[b] - sa * push * wb).coerceIn(-TABLE_HALF + gSX[b] / 2f, TABLE_HALF - gSX[b] / 2f)
                } else {
                    val push = ovz * min(1f, 12f * dt)
                    val sa = if (gCZ[a] < gCZ[b]) -1f else 1f
                    gCZ[a] = (gCZ[a] + sa * push * wa).coerceIn(-TABLE_HALF + gSZ[a] / 2f, TABLE_HALF - gSZ[a] / 2f)
                    gCZ[b] = (gCZ[b] - sa * push * wb).coerceIn(-TABLE_HALF + gSZ[b] / 2f, TABLE_HALF - gSZ[b] / 2f)
                }
            }
        }

        // Altura da torre: quantos blocos seguidos estão empilhados sobre a mesa.
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

        updateGameRects()
    }

    // ---------- desenho ----------

    private fun shade(c: Int, f: Float): Int = Color.rgb(
        min(255, (Color.red(c) * f).toInt()),
        min(255, (Color.green(c) * f).toInt()),
        min(255, (Color.blue(c) * f).toInt())
    )

    private fun setQuadPoint(k: Int, x: Float, y: Float, z: Float): Boolean {
        val d = gameProject(x, y, z, sp)
        pq[k * 2] = sp[0]
        pq[k * 2 + 1] = sp[1]
        return d > 0f
    }

    /** Desenha um quadrilátero da cena (4 pontos 3D). Deixa o contorno em facePath. */
    private fun quad(
        canvas: Canvas, color: Int, outline: Boolean,
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float, x3: Float, y3: Float, z3: Float
    ) {
        val a = setQuadPoint(0, x0, y0, z0)
        val b = setQuadPoint(1, x1, y1, z1)
        val c = setQuadPoint(2, x2, y2, z2)
        val d = setQuadPoint(3, x3, y3, z3)
        if (!(a && b && c && d)) return
        facePath.reset()
        facePath.moveTo(pq[0], pq[1])
        facePath.lineTo(pq[2], pq[3])
        facePath.lineTo(pq[4], pq[5])
        facePath.lineTo(pq[6], pq[7])
        facePath.close()
        fillPaint.color = color
        canvas.drawPath(facePath, fillPaint)
        if (outline) canvas.drawPath(facePath, outlinePaint)
    }

    private fun drawGameTable(canvas: Canvas) {
        val h = TABLE_HALF
        val th = 0.07f
        val sideA = Color.rgb(104, 72, 48)
        val sideB = Color.rgb(78, 53, 35)
        if (camE[0] > h) quad(canvas, sideA, true, h, 0f, h, h, 0f, -h, h, -th, -h, h, -th, h)
        if (camE[0] < -h) quad(canvas, sideB, true, -h, 0f, -h, -h, 0f, h, -h, -th, h, -h, -th, -h)
        if (camE[2] > h) quad(canvas, sideB, true, -h, 0f, h, h, 0f, h, h, -th, h, -h, -th, h)
        if (camE[2] < -h) quad(canvas, sideA, true, h, 0f, -h, -h, 0f, -h, -h, -th, -h, h, -th, -h)
        // Tampo.
        quad(canvas, Color.rgb(46, 96, 66), true, -h, 0f, -h, h, 0f, -h, h, 0f, h, -h, 0f, h)
        // Linhas da mesa: ajudam a perceber o giro e a profundidade.
        for (g in 1..3) {
            val t = -h + g * (2f * h / 4f)
            if (gameProject(t, 0f, -h, sp) > 0f) {
                val ax = sp[0]
                val ay = sp[1]
                if (gameProject(t, 0f, h, sp) > 0f) canvas.drawLine(ax, ay, sp[0], sp[1], gridPaint)
            }
            if (gameProject(-h, 0f, t, sp) > 0f) {
                val ax = sp[0]
                val ay = sp[1]
                if (gameProject(h, 0f, t, sp) > 0f) canvas.drawLine(ax, ay, sp[0], sp[1], gridPaint)
            }
        }
        // Marca amarela num canto: mostra de que lado você está olhando.
        val zc = gameProject(-h + 0.07f, 0f, h - 0.07f, sp)
        if (zc > 0f) {
            fillPaint.color = Color.rgb(255, 214, 64)
            canvas.drawCircle(sp[0], sp[1], max(vpF / zc * 0.03f, 3f), fillPaint)
        }
    }

    private fun drawGameBox(canvas: Canvas, i: Int) {
        val x0 = gCX[i] - gSX[i] / 2f
        val x1 = gCX[i] + gSX[i] / 2f
        val z0 = gCZ[i] - gSZ[i] / 2f
        val z1 = gCZ[i] + gSZ[i] / 2f
        val y0 = gBY[i]
        val y1 = y0 + GAME_BH
        val base = gColors[i]
        // Só as faces que olham para a câmera. Luz vem de cima, da esquerda e de frente.
        if (camE[0] > x1) quad(canvas, shade(base, 0.55f), true, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1)
        if (camE[0] < x0) quad(canvas, shade(base, 0.70f), true, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0)
        if (camE[2] > z1) quad(canvas, shade(base, 0.78f), true, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1)
        if (camE[2] < z0) quad(canvas, shade(base, 0.50f), true, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0)
        if (camE[1] > y1) {
            quad(canvas, shade(base, 0.98f), true, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0)
            if (grabbed == GAME_BLOCK && gameHeld == i) {
                canvas.drawPath(facePath, pressRingPaint)
            } else if (gTouch[i]) {
                fillPaint.color = Color.argb(70, 255, 255, 255)
                canvas.drawPath(facePath, fillPaint)
            }
        }
    }

    /** Desenha mesa, sombras, blocos, placar e o botão de reiniciar dentro do menu do jogo. */
    private fun drawGame(canvas: Canvas) {
        val bw = panelW
        rect.set(panelLeft, panelTop, panelLeft + panelW, panelTop + panelH)
        canvas.save()
        canvas.clipRect(rect)

        drawGameTable(canvas)

        // Sombras no tampo da mesa (blocos no ar) e a marca amarela de onde o bloco segurado vai cair.
        for (i in 0 until GAME_BLOCKS) {
            val held = grabbed == GAME_BLOCK && gameHeld == i
            if (gBY[i] > 0.02f || held) {
                val x0 = gCX[i] - gSX[i] / 2f
                val x1 = gCX[i] + gSX[i] / 2f
                val z0 = gCZ[i] - gSZ[i] / 2f
                val z1 = gCZ[i] + gSZ[i] / 2f
                quad(canvas, Color.argb(80, 0, 0, 0), false, x0, 0.002f, z0, x1, 0.002f, z0, x1, 0.002f, z1, x0, 0.002f, z1)
            }
        }
        if (grabbed == GAME_BLOCK && gameHeld >= 0) {
            val h = gameHeld
            val ly = gameLandingY(h) + 0.003f
            val x0 = gCX[h] - gSX[h] / 2f
            val x1 = gCX[h] + gSX[h] / 2f
            val z0 = gCZ[h] - gSZ[h] / 2f
            val z1 = gCZ[h] + gSZ[h] / 2f
            quad(canvas, Color.argb(120, 255, 235, 59), false, x0, ly, z0, x1, ly, z0, x1, ly, z1, x0, ly, z1)
            canvas.drawPath(facePath, pinchPaint)
        }

        // Blocos: dos mais longe para os mais perto da câmera.
        for (k in 0 until GAME_BLOCKS) gDraw[k] = k
        for (a in 1 until GAME_BLOCKS) {
            val v = gDraw[a]
            var b = a - 1
            while (b >= 0 && gDepth[gDraw[b]] < gDepth[v]) {
                gDraw[b + 1] = gDraw[b]
                b--
            }
            gDraw[b + 1] = v
        }
        for (k in 0 until GAME_BLOCKS) {
            val i = gDraw[k]
            if (gDepth[i] > 0f) drawGameBox(canvas, i)
        }
        canvas.restore()

        // Placar.
        textPaint.textSize = bw * 0.055f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.WHITE
        val label = if (gameTower >= GAME_BLOCKS) "Torre completa!" else "Torre: $gameTower/$GAME_BLOCKS"
        canvas.drawText(label, panelLeft + bw * 0.04f, panelTop + bw * 0.092f, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("Recorde: $gameBest", resetCX - resetR * 1.5f, panelTop + bw * 0.092f, textPaint)
        textPaint.textSize = bw * 0.034f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.argb(190, 255, 255, 255)
        canvas.drawText("Vire a cabeça ou arraste a mesa para girar", panelLeft + bw * 0.04f, panelTop + bw * 0.145f, textPaint)
        textPaint.color = Color.WHITE

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
        const val GAME_ORBIT = ICON_COUNT + 6    // pinça na mesa do jogo (arrastar gira a mesa)
        const val SET_OPT = ICON_COUNT + 5       // pinça num cartão de estilo da mão

        // Jogo 3D de empilhar blocos (a mesa tem 1,0 de lado).
        const val GAME_BLOCKS = 5
        const val GAME_BH = 0.12f         // altura de cada bloco
        const val TABLE_HALF = 0.5f       // metade do lado da mesa
        const val GAME_ASPECT = 0.80f     // altura / largura da janela do jogo
        const val GAME_GRAVITY = 3.0f
        const val GAME_SLIDE = 0.5f       // velocidade de quem escorrega da ponta de outro bloco
        const val GAME_CARRY_Y = 0.55f    // altura da base do bloco quando você o segura
        const val GAME_BAR_Y = 0.80f      // a barra desce até aqui ao abrir o jogo
        const val GAME_DIST = 2.6f        // distância da câmera até a mesa
        const val GAME_SCALE = 0.55f      // tamanho da mesa na janela (fração da largura, por unidade)
        const val GAME_TARGET_Y = 0.22f   // altura do ponto para onde a câmera olha
        const val GAME_YAW0 = 0.45f       // giro inicial da câmera (radianos)
        const val GAME_ELEV0 = 0.70f      // inclinação inicial da câmera (radianos, ~40 graus)
        const val GAME_ELEV_MIN = 0.30f
        const val GAME_ELEV_MAX = 1.30f
        const val HEAD_GAIN = 1.0f        // 1 = virar a cabeça 90 graus mostra a mesa de lado
        const val ORBIT_YAW_PER_PANEL = 3.0f    // arrastar a largura da janela gira ~170 graus
        const val ORBIT_ELEV_PER_PANEL = 1.5f
        const val PI_F = 3.1415927f
        const val TWO_PI_F = 6.2831855f
    }
}
