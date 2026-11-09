package com.handtracking.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
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
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Desenha o esqueleto da mão e o menu flutuante, com 4 apps (verde, vermelho, azul e amarelo).
 *  - Formato "Janela" (padrão): uma janela de vidro com os apps em grade (ícone + nome), no estilo
 *    do Apple Vision Pro. Cada janela tem um botão "•••" em cima (opções), e embaixo uma bolinha
 *    (fecha) e uma barrinha (move). As janelas ficam levemente inclinadas na direção de quem olha.
 *    No modo VR Box o fundo das janelas é a câmera desfocada (vidro fosco de verdade).
 *  - Encostar o dedo indicador num ícone: ele cresce um pouco e acende.
 *  - Pinça rápida (fechar e abrir os dedos) num ícone: abre (ou fecha) o menu daquele ícone.
 *  - Os ícones ficam sempre no lugar (não dá para arrastar).
 *  - Pinça na barrinha branca embaixo da barra: move a barra inteira.
 *  - Pinça na barrinha branca embaixo de um menu: move só aquele menu.
 *  - Menu vermelho (modo normal): mostra o YouTube; a pinça funciona como o dedo na tela
 *    (pinça rápida = clique, pinça e arrastar = rolar).
 *  - Menu verde: configurações, em duas abas. "Mão": estilo da mão (normal ou Meta Quest).
 *    "Menu": formato da barra (horizontal, em arco ou vertical). As escolhas ficam salvas no aparelho.
 *  - Menu azul ("Jogos"): abre jogos instalados no celular (APK), como o Gorilla MR. O app acha o jogo pelo
 *    nome na lista de apps do celular, então não precisa copiar nada do jogo para dentro deste app.
 *  - Jogo "Tiro" (teste), pela lista de Jogos: ao fechar a mão toda, aparece uma pistola feita de blocos na
 *    sua mão (passo 1: só segurar; atirar vem depois). Enquanto a mão está fechada ela não conta como pinça.
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
        Color.rgb(245, 166, 35)   // âmbar (mini jogo)
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

    /** Formato do menu: LAYOUT_GRID (janela), LAYOUT_HORIZONTAL, LAYOUT_ARC (em arco) ou LAYOUT_VERTICAL. Fica salvo. */
    var menuLayout: Int = prefs.getInt("menu_layout", LAYOUT_GRID)
        set(value) {
            field = value
            prefs.edit().putInt("menu_layout", value).apply()
            postInvalidate()
        }

    private var settingsTab = 0                     // aba aberta nas configurações: 0 = Mão, 1 = Menu
    private val tabL = FloatArray(2)                // abas (esquerda de cada uma)
    private var tabT = 0f
    private var tabW = 0f
    private var tabH = 0f
    private val tabTouch = BooleanArray(2)
    private var tabHeld = -1
    private val optL = FloatArray(OPT_MAX)          // cartões de escolha: lado esquerdo de cada um
    private var optT = 0f                           // topo (igual para todos)
    private var optW = 0f
    private var optH = 0f
    private val optTouch = BooleanArray(OPT_MAX)
    private var optHeld = -1
    // ---- Jogo de tiro (teste): pistola de blocos na mão fechada ----
    private val gunOn = BooleanArray(MAX_HANDS)         // mão fechada segurando a pistola
    private val gunCnt = IntArray(MAX_HANDS)            // contador para não piscar (histerese)
    private val gunX = FloatArray(MAX_HANDS)            // centro da palma, em pixels da imagem (suavizado)
    private val gunY = FloatArray(MAX_HANDS)
    private val gunUX = FloatArray(MAX_HANDS)           // para onde aponta o cano (do pulso para os nós dos dedos)
    private val gunUY = FloatArray(MAX_HANDS)
    private val gunVX = FloatArray(MAX_HANDS)           // "para cima" da pistola (lado do polegar)
    private val gunVY = FloatArray(MAX_HANDS)
    private val gunHS = FloatArray(MAX_HANDS)           // tamanho da mão (pulso até o nó do dedo médio)
    private val gunTmpX = FloatArray(21)
    private val gunTmpY = FloatArray(21)
    // Pistola em blocos, vista de lado (cano para a direita). S corpo, R mira, M ponta do cano, G cabo, K gatilho.
    private val gunRows = arrayOf(
        ".R....R.",
        "SSSSSSSM",
        "SSSSSSSM",
        ".GGK....",
        ".GG.....",
        "GGG....."
    )
    private val gunPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(170, 20, 24, 34)
    }

    // ---- App "Jogos": abre jogos (APK) que já estão instalados no celular ----
    private val appCtx: Context = context
    private val appGameNames = arrayOf("Blocos", "Tiro", "Gorilla MR")        // nome mostrado
    private val appGameBuiltIn = booleanArrayOf(true, true, false)            // true = jogo do próprio app (abre aqui dentro)
    private val appGameMenu = intArrayOf(GAME_ICON, SHOOT_MENU, NONE)         // menu que o jogo do app abre
    private val appGameKeys = arrayOf(                                        // jeitos de o nome aparecer no celular
        emptyArray<String>(),
        emptyArray<String>(),
        arrayOf("gorilla mr", "gorillamr", "gorilla_mr", "gorilla-mr")
    )
    private val appGameIntent = arrayOfNulls<Intent>(appGameNames.size)       // como abrir (null = não instalado)
    private val tileL = FloatArray(3)
    private var tileT = 0f
    private var tileW = 0f
    private var tileH = 0f
    private val tileTouch = BooleanArray(3)
    private var tileHeld = -1

    // ---- Janelas de vidro: ••• em cima, bolinha e barrinha embaixo, inclinação e vidro fosco ----
    private var imgW = 0f                           // tamanho da imagem da câmera (para inclinar e desfocar)
    private var imgH = 0f
    private var blurBmp: Bitmap? = null             // câmera bem desfocada (só no modo VR Box)
    private val glassPath = Path()
    private val tiltSrc = FloatArray(8)
    private val tiltDst = FloatArray(8)
    private val tiltMatrix = Matrix()
    private val pillCX = FloatArray(2)              // botão •••: 0 = da janela inicial, 1 = do menu aberto
    private val pillCY = FloatArray(2)
    private var pillW = 0f
    private var pillH = 0f
    private val pillTouch = BooleanArray(2)
    private var pillHeld = -1
    private var dotCX = 0f                          // bolinha que fecha o menu aberto
    private var dotCY = 0f
    private var dotR = 0f
    private var dotTouch = false
    private var popFor = -1                         // lista do •••: -1 = fechada, 0 = da janela inicial, 1 = do menu
    private var popL = 0f
    private var popT = 0f
    private var popW = 0f
    private var popRowH = 0f
    private val popTouch = BooleanArray(2)
    private var popHeld = -1
    private val thinPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        setShadowLayer(3f, 0f, 1.5f, Color.argb(120, 0, 0, 0))
    }

    // ---- Visual de vidro e animações da barra ----
    private var barInit = false
    private var arcCX = 0f                          // barra em arco: centro e raio do círculo, ângulos em graus
    private var arcCY = 0f
    private var arcR = 0f
    private val iconScale = FloatArray(ICON_COUNT) { 1f }   // tamanho atual de cada ícone (cresce ao encostar)
    private val iconGlow = FloatArray(ICON_COUNT)           // brilho em volta do ícone (0 a 1)
    private val iconDot = FloatArray(ICON_COUNT)            // bolinha de "menu aberto" (0 a 1)
    private var shownMenu = NONE                    // menu que está na tela (fica enquanto fecha)
    private var panelAnim = 0f                      // 0 = fechado, 1 = aberto (animação suave)
    private var animLastMs = 0L
    private var animating = false
    private val glyphPath = Path()
    private val glassRingPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.argb(140, 255, 255, 255)
    }
    private val bandPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
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
    private val eUp = floatArrayOf(0f, 1f, 0f)  
