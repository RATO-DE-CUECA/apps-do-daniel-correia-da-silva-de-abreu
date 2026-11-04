package com.handtracking.app

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Descobre para onde o aparelho está virado SEM precisar de giroscópio.
 *
 *  - Acelerômetro: diz onde fica o chão. Isso dá a inclinação (olhar para cima/baixo e inclinar a cabeça)
 *    e não tem "deriva" (não se desajusta com o tempo).
 *  - Câmera: o quanto a imagem deslizou entre um quadro e o seguinte diz o quanto você virou a cabeça
 *    (para os lados e para cima/baixo). Essa parte vai acumulando um errinho com o tempo; o botão
 *    "Centralizar" do app resolve.
 *
 * O resultado é uma matriz 3x3 (dispositivo -> mundo), no mesmo formato do giroscópio do Android,
 * então o resto do app não precisa saber de onde ela veio.
 *
 * Esta classe não usa nada do Android de propósito (recebe pixels, não Bitmap).
 */
class VisualOrientation {

    private val lock = Any()

    private val rot = FloatArray(9)          // dispositivo -> mundo
    private var rotReady = false
    private val grav = FloatArray(3)         // acelerômetro filtrado (aponta para cima)
    private var gravReady = false

    // Direita e cima da tela, nos eixos do aparelho (depende da rotação da tela).
    private val eRight = floatArrayOf(1f, 0f, 0f)
    private val eUp = floatArrayOf(0f, 1f, 0f)

    // Imagens pequenas em tons de cinza (nível 0 = 1/4 do tamanho, nível 1 = 1/8).
    private var gw = 0
    private var gh = 0
    private var g1w = 0
    private var g1h = 0
    private var g0Cur = IntArray(0)
    private var g0Prev = IntArray(0)
    private var g1Cur = IntArray(0)
    private var g1Prev = IntArray(0)
    private var hasPrev = false

    // Buffers de trabalho (evita criar objetos a cada quadro).
    private val shiftX = FloatArray(MAX_BLOCKS)
    private val shiftY = FloatArray(MAX_BLOCKS)
    private val sortX = FloatArray(MAX_BLOCKS)
    private val sortY = FloatArray(MAX_BLOCKS)
    private val weightX = FloatArray(MAX_BLOCKS)
    private val weightY = FloatArray(MAX_BLOCKS)

    // Último movimento medido (usado para "continuar girando" se um quadro não der para medir).
    private var lastSx = 0f
    private var lastSy = 0f
    private var missed = 0

    // Só para conferir como está indo (quantos blocos foram usados no último quadro).
    @Volatile var debugBlocks = 0
    @Volatile var debugInliers = 0
    private val tmp9 = FloatArray(9)
    private val tmpS = FloatArray(9)
    private val tmpM = FloatArray(9)
    private val tmpP = FloatArray(9)

    /** Rotação da tela: 0, 1, 2 ou 3 (igual a Surface.ROTATION_0, _90, _180, _270). */
    fun setDisplayRotation(rotation: Int) {
        synchronized(lock) {
            when (rotation) {
                1 -> setAxes(0f, 1f, 0f, -1f, 0f, 0f)
                2 -> setAxes(-1f, 0f, 0f, 0f, -1f, 0f)
                3 -> setAxes(0f, -1f, 0f, 1f, 0f, 0f)
                else -> setAxes(1f, 0f, 0f, 0f, 1f, 0f)
            }
        }
    }

    private fun setAxes(rx: Float, ry: Float, rz: Float, ux: Float, uy: Float, uz: Float) {
        eRight[0] = rx; eRight[1] = ry; eRight[2] = rz
        eUp[0] = ux; eUp[1] = uy; eUp[2] = uz
    }

    /** Leitura do acelerômetro (pode ser chamada de qualquer thread). */
    fun onAccel(x: Float, y: Float, z: Float) {
        synchronized(lock) {
            if (!gravReady) {
                grav[0] = x; grav[1] = y; grav[2] = z
                gravReady = true
            } else {
                // Filtro suave: tira o tremido das mãos.
                grav[0] += (x - grav[0]) * ACCEL_SMOOTH
                grav[1] += (y - grav[1]) * ACCEL_SMOOTH
                grav[2] += (z - grav[2]) * ACCEL_SMOOTH
            }
        }
    }

    /** Esquece o quadro anterior (use quando a câmera trocar ou a tela girar). */
    fun reset() {
        synchronized(lock) { hasPrev = false }
    }

    /** Última orientação (cópia) ou null se ainda não deu para calcular. */
    fun current(): FloatArray? {
        synchronized(lock) {
            return if (rotReady) rot.clone() else null
        }
    }

    /**
     * Processa um quadro da câmera (já girado para ficar em pé na tela).
     * [boxes]: retângulos das mãos (minX, minY, maxX, maxY, de 0 a 1), para não confundir o
     * movimento da mão com o movimento da cabeça. Pode ser null.
     * Devolve a orientação atualizada (cópia) ou null se ainda não deu para calcular.
     */
    fun onFrame(pixels: IntArray, w: Int, h: Int, boxes: FloatArray?): FloatArray? {
        val newGw = w / 4
        val newGh = h / 4
        if (newGw < 48 || newGh < 48) return current()

        synchronized(lock) {
            if (newGw != gw || newGh != gh) {
                gw = newGw
                gh = newGh
                g1w = gw / 2
                g1h = gh / 2
                g0Cur = IntArray(gw * gh)
                g0Prev = IntArray(gw * gh)
                g1Cur = IntArray(g1w * g1h)
                g1Prev = IntArray(g1w * g1h)
                hasPrev = false
            }

            toGray(pixels, w)

            if (hasPrev) {
                if (!rotReady) initFromGravity()
                if (rotReady) {
                    if (estimateAndRotate(boxes)) {
                        missed = 0
                    } else if (missed < MAX_MISSED && (lastSx != 0f || lastSy != 0f)) {
                        // Não deu para medir (mão na frente, parede lisa...): continua o giro de antes, enfraquecendo.
                        missed++
                        val decay = 0.7f
                        lastSx *= decay
                        lastSy *= decay
                        rotateByShift(lastSx, lastSy)
                    } else {
                        lastSx = 0f
                        lastSy = 0f
                    }
                }
            }

            if (rotReady) correctWithGravity()

            // O quadro de agora vira o "anterior" do próximo.
            val t0 = g0Prev; g0Prev = g0Cur; g0Cur = t0
            val t1 = g1Prev; g1Prev = g1Cur; g1Cur = t1
            hasPrev = true

            if (!rotReady) initFromGravity()
            return if (rotReady) rot.clone() else null
        }
    }

    // ------------------------------------------------------------------
    // Imagem -> tons de cinza pequenos
    // ------------------------------------------------------------------

    private fun toGray(pixels: IntArray, w: Int) {
        for (gy in 0 until gh) {
            val y0 = gy * 4
            for (gx in 0 until gw) {
                val x0 = gx * 4
                var sum = 0
                for (yy in 0 until 4) {
                    var idx = (y0 + yy) * w + x0
                    for (xx in 0 until 4) {
                        val p = pixels[idx++]
                        sum += ((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29
                    }
                }
                g0Cur[gy * gw + gx] = sum shr 12
            }
        }
        for (y in 0 until g1h) {
            for (x in 0 until g1w) {
                val i = (2 * y) * gw + 2 * x
                g1Cur[y * g1w + x] = (g0Cur[i] + g0Cur[i + 1] + g0Cur[i + gw] + g0Cur[i + gw + 1]) shr 2
            }
        }
    }

    // ------------------------------------------------------------------
    // Quanto a imagem deslizou? (casamento de blocos, do grosso para o fino)
    // ------------------------------------------------------------------

    /** Mede o deslizamento e gira. Devolve false se não conseguiu medir (ou se não houve movimento). */
    private fun estimateAndRotate(boxes: FloatArray?): Boolean {
        val margin = SEARCH + 1
        val spanX = g1w - BLOCK - 2 * margin
        val spanY = g1h - BLOCK - 2 * margin
        if (spanX < 0 || spanY < 0) return false

        var n = 0
        for (iy in 0 until BLOCKS_Y) {
            val by = margin + if (BLOCKS_Y > 1) spanY * iy / (BLOCKS_Y - 1) else spanY / 2
            for (ix in 0 until BLOCKS_X) {
                val bx = margin + if (BLOCKS_X > 1) spanX * ix / (BLOCKS_X - 1) else spanX / 2

                // Não usa blocos em cima das mãos.
                if (boxes != null) {
                    val cx = (bx + BLOCK / 2f) / g1w
                    val cy = (by + BLOCK / 2f) / g1h
                    var onHand = false
                    var k = 0
                    while (k + 3 < boxes.size) {
                        if (cx > boxes[k] - HAND_PAD && cx < boxes[k + 2] + HAND_PAD &&
                            cy > boxes[k + 1] - HAND_PAD && cy < boxes[k + 3] + HAND_PAD
                        ) onHand = true
                        k += 4
                    }
                    if (onHand) continue
                }

                // Precisa ter detalhe (parede lisa não ajuda).
                if (!hasTexture(bx, by)) continue

                // Busca grossa (nível 1).
                var bestCost = Int.MAX_VALUE
                var bestDx = 0
                var bestDy = 0
                for (dy in -SEARCH..SEARCH) {
                    for (dx in -SEARCH..SEARCH) {
                        val c = sad1(bx, by, dx, dy, bestCost)
                        if (c < bestCost) {
                            bestCost = c
                            bestDx = dx
                            bestDy = dy
                        }
                    }
                }
                if (bestCost > MAX_COST1 * BLOCK * BLOCK) continue

                // Busca fina (nível 0), em volta do resultado grosso.
                val ox = bx * 2
                val oy = by * 2
                val cdx = bestDx * 2
                val cdy = bestDy * 2
                var fCost = Int.MAX_VALUE
                var fDx = cdx
                var fDy = cdy
                for (ey in -1..1) {
                    for (ex in -1..1) {
                        val c = sad0(ox, oy, cdx + ex, cdy + ey)
                        if (c < fCost) {
                            fCost = c
                            fDx = cdx + ex
                            fDy = cdy + ey
                        }
                    }
                }

                // Precisão de fração de pixel (parábola em volta do melhor ponto).
                val c0 = fCost.toFloat()
                val subX = parabola(
                    sad0(ox, oy, fDx - 1, fDy).toFloat(), c0, sad0(ox, oy, fDx + 1, fDy).toFloat()
                )
                val subY = parabola(
                    sad0(ox, oy, fDx, fDy - 1).toFloat(), c0, sad0(ox, oy, fDx, fDy + 1).toFloat()
                )

                // Deslocamento do bloco no quadro anterior = -(deslizamento da cena).
                shiftX[n] = -(fDx + subX)
                shiftY[n] = -(fDy + subY)
                // Giro de câmera desliza a imagem MAIS nas bordas (1 + distância²/f²) do que no centro.
                val f0 = max(gw, gh) / 2f / TAN_HALF_FOV
                val u = (2 * bx + BLOCK) - gw / 2f
                val v = (2 * by + BLOCK) - gh / 2f
                weightX[n] = 1f + u * u / (f0 * f0)
                weightY[n] = 1f + v * v / (f0 * f0)
                n++
            }
        }
        debugBlocks = n
        debugInliers = 0
        if (n < MIN_BLOCKS) return false

        // Mediana (aguenta alguns blocos errados).
        for (i in 0 until n) { sortX[i] = shiftX[i]; sortY[i] = shiftY[i] }
        java.util.Arrays.sort(sortX, 0, n)
        java.util.Arrays.sort(sortY, 0, n)
        val medX = if (n % 2 == 1) sortX[n / 2] else (sortX[n / 2 - 1] + sortX[n / 2]) / 2f
        val medY = if (n % 2 == 1) sortY[n / 2] else (sortY[n / 2 - 1] + sortY[n / 2]) / 2f

        // Média só dos blocos que concordam com a mediana.
        var inliers = 0
        var sumX = 0f
        var sumY = 0f
        var sumWX = 0f
        var sumWY = 0f
        for (i in 0 until n) {
            if (abs(shiftX[i] - medX) < INLIER * max(weightX[i], weightY[i]) &&
                abs(shiftY[i] - medY) < INLIER * max(weightX[i], weightY[i])
            ) {
                inliers++
                sumX += shiftX[i]
                sumY += shiftY[i]
                sumWX += weightX[i]
                sumWY += weightY[i]
            }
        }
        debugInliers = inliers
        if (inliers < max(MIN_BLOCKS, (n + 1) / 2)) return false
        var sx = sumX / inliers
        var sy = sumY / inliers

        // Ruído da câmera: movimentos minúsculos viram zero para não "andar" sozinho.
        if (abs(sx) < DEAD_ZONE) sx = 0f
        if (abs(sy) < DEAD_ZONE) sy = 0f
        // Guarda já corrigido pela posição dos blocos (bordas deslizam mais que o centro).
        lastSx = sx / (sumWX / inliers)
        lastSy = sy / (sumWY / inliers)
        if (sx == 0f && sy == 0f) return true   // câmera parada: medimos, só não há o que girar

        rotateByShift(lastSx, lastSy)
        return true
    }

    /** Gira a câmera de acordo com o deslizamento da imagem (em pixels do nível 0). */
    private fun rotateByShift(sx: Float, sy: Float) {
        // Pixels -> ângulo. Cena deslizando para a direita = você virou para a esquerda.
        val f0 = max(gw, gh) / 2f / TAN_HALF_FOV
        val yaw = atan(sx / f0)     // giro em volta do eixo "cima" da tela
        val pitch = atan(sy / f0)   // giro em volta do eixo "direita" da tela
        rotateCamera(yaw, pitch)
    }

    private fun hasTexture(bx: Int, by: Int): Boolean {
        var sum = 0
        for (y in 0 until BLOCK) {
            val row = (by + y) * g1w + bx
            for (x in 0 until BLOCK) sum += g1Cur[row + x]
        }
        val mean = sum / (BLOCK * BLOCK)
        var dev = 0
        for (y in 0 until BLOCK) {
            val row = (by + y) * g1w + bx
            for (x in 0 until BLOCK) dev += abs(g1Cur[row + x] - mean)
        }
        return dev / (BLOCK * BLOCK) >= MIN_TEXTURE
    }

    /** Diferença entre o bloco de agora e o do quadro anterior deslocado (nível 1). */
    private fun sad1(bx: Int, by: Int, dx: Int, dy: Int, limit: Int): Int {
        var sum = 0
        for (y in 0 until BLOCK) {
            val rc = (by + y) * g1w + bx
            val rp = (by + y + dy) * g1w + bx + dx
            for (x in 0 until BLOCK) sum += abs(g1Cur[rc + x] - g1Prev[rp + x])
            if (sum >= limit) return sum
        }
        return sum
    }

    /** Mesma coisa no nível 0 (bloco de 32x32). */
    private fun sad0(ox: Int, oy: Int, dx: Int, dy: Int): Int {
        val size = BLOCK * 2
        var sum = 0
        for (y in 0 until size) {
            val rc = (oy + y) * gw + ox
            val rp = (oy + y + dy) * gw + ox + dx
            for (x in 0 until size) sum += abs(g0Cur[rc + x] - g0Prev[rp + x])
        }
        return sum
    }

    /** Fração de pixel (-0.5 a 0.5) do ponto mais baixo de uma parábola por 3 pontos. */
    private fun parabola(left: Float, center: Float, right: Float): Float {
        val denom = left - 2f * center + right
        if (denom <= 1e-3f) return 0f
        return ((left - right) / (2f * denom)).coerceIn(-0.5f, 0.5f)
    }

    // ------------------------------------------------------------------
    // Matemática da orientação
    // ------------------------------------------------------------------

    /**
     * Gira a câmera: [yaw] em volta do eixo "cima" da tela (positivo = esquerda),
     * [pitch] em volta do eixo "direita" da tela (positivo = olhar para cima).
     */
    private fun rotateCamera(yaw: Float, pitch: Float) {
        val cy = cos(yaw); val sy = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)
        // S = Ry(yaw) * Rx(pitch), nos eixos da tela (x = direita, y = cima, z = para trás).
        tmpS[0] = cy;  tmpS[1] = sy * sp;  tmpS[2] = sy * cp
        tmpS[3] = 0f;  tmpS[4] = cp;       tmpS[5] = -sp
        tmpS[6] = -sy; tmpS[7] = cy * sp;  tmpS[8] = cy * cp
        // P: colunas = direita, cima e "para trás" da tela, nos eixos do aparelho.
        tmpP[0] = eRight[0]; tmpP[1] = eUp[0]; tmpP[2] = 0f
        tmpP[3] = eRight[1]; tmpP[4] = eUp[1]; tmpP[5] = 0f
        tmpP[6] = eRight[2]; tmpP[7] = eUp[2]; tmpP[8] = 1f
        // M = P * S * P^T  (o mesmo giro, mas nos eixos do aparelho)
        mul(tmpP, tmpS, tmp9)
        mulT(tmp9, tmpP, tmpM)
        // rot = rot * M
        mul(rot, tmpM, tmp9)
        System.arraycopy(tmp9, 0, rot, 0, 9)
    }

    /** Primeira orientação: só pelo acelerômetro (o "norte" é para onde a câmera olha agora). */
    private fun initFromGravity() {
        if (!gravReady) return
        var ux = grav[0]; var uy = grav[1]; var uz = grav[2]
        val un = sqrt(ux * ux + uy * uy + uz * uz)
        if (un < 1f) return
        ux /= un; uy /= un; uz /= un
        // Frente da câmera = -Z do aparelho. Tira a parte vertical para achar o "norte".
        var nx = 0f; var ny = 0f; var nz = -1f
        var d = nx * ux + ny * uy + nz * uz
        nx -= d * ux; ny -= d * uy; nz -= d * uz
        var nn = sqrt(nx * nx + ny * ny + nz * nz)
        if (nn < 0.2f) {
            // Câmera apontando para cima/baixo: usa o eixo Y do aparelho.
            nx = 0f; ny = 1f; nz = 0f
            d = nx * ux + ny * uy + nz * uz
            nx -= d * ux; ny -= d * uy; nz -= d * uz
            nn = sqrt(nx * nx + ny * ny + nz * nz)
        }
        nx /= nn; ny /= nn; nz /= nn
        // leste = norte x cima
        val ex = ny * uz - nz * uy
        val ey = nz * ux - nx * uz
        val ez = nx * uy - ny * ux
        rot[0] = ex; rot[1] = ey; rot[2] = ez
        rot[3] = nx; rot[4] = ny; rot[5] = nz
        rot[6] = ux; rot[7] = uy; rot[8] = uz
        rotReady = true
    }

    /** Puxa devagarinho a orientação para bater com a gravidade (corrige inclinação sem deriva). */
    private fun correctWithGravity() {
        if (!gravReady) return
        var mx = grav[0]; var my = grav[1]; var mz = grav[2]
        val mn = sqrt(mx * mx + my * my + mz * mz)
        // Só confia se o aparelho não está sendo sacudido (gravidade ~ 9,8).
        if (mn < 8.0f || mn > 11.8f) return
        mx /= mn; my /= mn; mz /= mn
        // "Cima" segundo a orientação atual (3ª linha da matriz).
        val ex = rot[6]; val ey = rot[7]; val ez = rot[8]
        // eixo = estimado x medido
        val ax = ey * mz - ez * my
        val ay = ez * mx - ex * mz
        val az = ex * my - ey * mx
        val s = sqrt(ax * ax + ay * ay + az * az)
        val dot = ex * mx + ey * my + ez * mz
        if (s < 1e-6f) return
        val angle = GRAVITY_GAIN * atan2(s, dot)
        val kx = ax / s; val ky = ay / s; val kz = az / s
        val c = cos(angle); val sn = sin(angle); val t = 1f - c
        // Q = giro de "angle" em volta de k; precisamos de Q^T (giro contrário).
        val q0 = c + kx * kx * t;        val q1 = kx * ky * t - kz * sn;  val q2 = kx * kz * t + ky * sn
        val q3 = ky * kx * t + kz * sn;  val q4 = c + ky * ky * t;        val q5 = ky * kz * t - kx * sn
        val q6 = kz * kx * t - ky * sn;  val q7 = kz * ky * t + kx * sn;  val q8 = c + kz * kz * t
        tmpM[0] = q0; tmpM[1] = q3; tmpM[2] = q6
        tmpM[3] = q1; tmpM[4] = q4; tmpM[5] = q7
        tmpM[6] = q2; tmpM[7] = q5; tmpM[8] = q8
        // rot = rot * Q^T
        mul(rot, tmpM, tmp9)
        System.arraycopy(tmp9, 0, rot, 0, 9)
    }

    /** out = a * b (matrizes 3x3 em linha). */
    private fun mul(a: FloatArray, b: FloatArray, out: FloatArray) {
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
            }
        }
    }

    /** out = a * b^T. */
    private fun mulT(a: FloatArray, b: FloatArray, out: FloatArray) {
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                out[r * 3 + c] = a[r * 3] * b[c * 3] + a[r * 3 + 1] * b[c * 3 + 1] + a[r * 3 + 2] * b[c * 3 + 2]
            }
        }
    }

    private companion object {
        const val TAN_HALF_FOV = 0.6494f    // tan(33 graus): ~66 graus no lado maior (igual ao HandOverlayView)

        const val BLOCK = 16                // tamanho do bloco no nível 1 (32 no nível 0)
        const val SEARCH = 8                // quanto procura em volta no nível 1
        const val BLOCKS_X = 5
        const val BLOCKS_Y = 4
        const val MAX_MISSED = 6            // quantos quadros seguidos "continua girando" sem medir
        const val MAX_BLOCKS = BLOCKS_X * BLOCKS_Y
        const val MIN_BLOCKS = 3            // mínimo de blocos bons para confiar
        const val MIN_TEXTURE = 3           // detalhe mínimo (nível de cinza médio)
        const val MAX_COST1 = 14            // diferença média máxima por pixel para aceitar o casamento
        const val INLIER = 1.2f             // blocos que concordam (em pixels)
        const val DEAD_ZONE = 0.04f         // ruído (em pixels) que vira zero
        const val HAND_PAD = 0.10f          // folga em volta da mão
        const val ACCEL_SMOOTH = 0.5f       // suavização do acelerômetro
        const val GRAVITY_GAIN = 0.08f      // força da correção pela gravidade (por quadro)
    }
}
