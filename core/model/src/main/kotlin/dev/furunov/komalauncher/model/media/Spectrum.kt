package dev.furunov.komalauncher.model.media

import dev.furunov.komalauncher.model.geometry.Point
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 端末が出している音の周波数（Android の Visualizer）。録音の権限が要り、端末によっては取得できない。
 * ホームが前面にあって再生中の間だけ [start] する。
 */
interface AudioSpectrum {
    /** 作れなければ false（権限が無い、端末が対応していないなど）。 */
    fun start(): Boolean

    fun stop()

    /** Visualizer.getFft の形式で [out] に書く。取得できなければ false。 */
    fun read(out: ByteArray): Boolean
}

/** ビジュアライザーの棒の計算。毎フレーム呼ぶので、配列を新しく作らずに引数の配列へ書く。 */
object Spectrum {
    /**
     * 棒を立てる辺。中点が最も下にある辺で、x の小さい端を first にする。
     * 長さがほぼ 0 の辺は除く（コマの間隔が 0 のとき、同じ頂点が 2 回入ることがあるため）。
     */
    fun bottomEdge(points: List<Point>): Pair<Point, Point> {
        val edges = points.indices.map { points[it] to points[(it + 1) % points.size] }
        val (a, b) =
            edges
                .filter { (p, q) -> hypot(q.x - p.x, q.y - p.y) > MIN_EDGE }
                .ifEmpty { edges }
                .maxBy { (p, q) -> p.y + q.y }
        return if (a.x <= b.x) a to b else b to a
    }

    /** 辺の長さに並べる棒の数。9dp ごとに 1 本、最低 10 本。 */
    fun barCount(
        edgeLengthPx: Float,
        density: Float,
    ): Int = max(10, (edgeLengthPx / (9f * density)).roundToInt())

    /**
     * FFT（[0] は直流、[1] はナイキスト周波数の実部、以降は実部・虚部の組）を、
     * 対数の間隔で [count] 本にまとめ、0〜1 にして [out] へ書く。左が低い音。
     */
    fun bands(
        fft: ByteArray,
        count: Int,
        out: FloatArray,
    ) {
        val bins = fft.size / 2
        val top = (bins - 1).toFloat()
        var lo = 1
        for (i in 0 until count) {
            val edge = top.pow((i + 1).toFloat() / count).toInt()
            val hi = max(lo + 1, min(edge + 1, bins))
            var peak = 0f
            for (k in lo until hi) {
                val m = hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat())
                if (m > peak) peak = m
            }
            out[i] = min(1f, log10(1f + peak) / LOG_MAX)
            lo = min(hi, bins - 1)
        }
    }

    /** 上がるときは速く、下がるときはゆっくり、[current] を [target] へ寄せる。 */
    fun smooth(
        current: FloatArray,
        target: FloatArray,
        dtSec: Float,
    ) {
        val rise = min(1f, dtSec * 30f)
        val fall = min(1f, dtSec * 6f)
        for (i in current.indices) {
            val d = target[i] - current[i]
            current[i] += d * if (d > 0f) rise else fall
        }
    }

    /** 音に合わせないときの、それらしい動き（モックアップと同じ式）。[level] が 0 なら全部 0。 */
    fun simulated(
        count: Int,
        timeSec: Float,
        level: Float,
        out: FloatArray,
    ) {
        val beat = max(0f, cos(timeSec * PI.toFloat() * 4f)).pow(6)
        for (i in 0 until count) {
            val f = i.toFloat() / count
            val base = (1f - f) * 0.55f + 0.15f
            val wob = 0.5f + 0.5f * sin(timeSec * (3f + i * 0.7f) + i * 1.9f) * sin(timeSec * 1.3f + i)
            val kick = if (f < 0.25f) beat * 0.5f else beat * 0.12f
            out[i] = min(1f, (base * wob + kick) * level)
        }
    }

    fun isSilent(fft: ByteArray): Boolean = fft.all { it == 0.toByte() }

    private const val MIN_EDGE = 0.5f

    // FFT の値は 1 バイト（-128〜127）なので、大きさの上限を 128 とする
    private val LOG_MAX = log10(1f + 128f)
}
