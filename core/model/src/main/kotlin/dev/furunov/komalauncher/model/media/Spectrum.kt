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
    /** 作れなければ false（権限が無い、端末が対応していないなど）。true を返した呼び出しごとに、[stop] を 1 回呼ぶ。 */
    fun start(): Boolean

    /** [start] と対にして呼ぶ。複数の呼び出し元が使っている間は、最後の 1 つが呼んだときに解放する。 */
    fun stop()

    /**
     * 帯域ごとのパワー（[PowerSpectrum.compute] の形式）を返す。取得できなければ null。配列の長さは端末やほかのアプリの設定で変わり
     * （512 とは限らない）、次の read で書き換わる。
     */
    fun read(): FloatArray?

    /** 取得している音の標本化周波数（Hz）。パワーの何番目がどの周波数かを決めるのに使う。 */
    val sampleRateHz: Int get() = 48_000
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

    /** 上がるときは速く、下がるときはゆっくり、[current] を [target] へ寄せる。 */
    fun smooth(
        current: FloatArray,
        target: FloatArray,
        dtSec: Float,
    ) {
        // 実機で敏感すぎると指摘があり、30 から下げた
        val rise = min(1f, dtSec * 15f)
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

    fun isSilent(power: FloatArray): Boolean = power.all { it == 0f }

    private const val MIN_EDGE = 0.5f
}

/**
 * 帯域ごとのパワーを棒の高さ（0〜1）に変える。帯域ごとに最近の最大の大きさを覚えて、そこから [WINDOW_DB] 下までを 0〜1 に割り当てる。
 *
 * 実機（2026-11-20、YT Music、Visualizer.getFft の値）で記録した 1 オクターブごとの大きさは、低音が 10〜43dB と大きく変わる一方、中音域は 25〜40dB を
 * ±5dB ほど揺れるだけで、8kHz より上はほぼ雑音（0〜8dB）だった。大きさを対数で 0〜128 に割り当てるだけでは、中音域の棒が
 * 常に 6〜8 割の高さで細かく震え、音に追従していても偽物の動きに見えた。そこで、
 * - 帯域は [LOW_HZ]〜[HIGH_HZ] だけを対数の間隔で並べる（音楽の中身がある範囲）
 * - 帯域の中の平均の大きさ（dB）を使う（最大の値より安定する）
 * - 大きさを時間方向にも平均する（[TAU_SEC]）
 * - 帯域ごとの最近の最大に合わせて範囲を決め、曲や帯域ごとの音量の違いを打ち消す
 * - [CONTRAST] 乗して、大きい音と小さい音の差を強める
 * 状態（平均した大きさと最近の最大）を持つので、棒の並びごとに 1 つ作る。
 */
class SpectrumShaper(
    private val count: Int,
) {
    private val levelDb = FloatArray(count) { Float.NaN }
    private val peakDb = FloatArray(count) { Float.NaN }

    /** [power] を [out]（長さ [count]）に書く。[dtSec] は前の呼び出しからの時間。 */
    fun shape(
        power: FloatArray,
        sampleRateHz: Int,
        dtSec: Float,
        out: FloatArray,
    ) {
        val bins = power.size
        val hzPerBin = sampleRateHz / 2f / bins
        val ratio = HIGH_HZ / LOW_HZ
        // 低い帯域では 1 区画（約 47Hz）より帯域が狭く、複数の棒が同じ区画に当たる。前の棒の区画と重ならないよう、後ろへ詰める
        var next = 1
        for (i in 0 until count) {
            val fLo = LOW_HZ * ratio.pow(i.toFloat() / count)
            val fHi = LOW_HZ * ratio.pow((i + 1).toFloat() / count)
            val lo = max((fLo / hzPerBin).roundToInt(), next).coerceIn(1, bins - 1)
            val hi = (fHi / hzPerBin).roundToInt().coerceIn(lo + 1, bins)
            next = hi
            var sum = 0f
            for (k in lo until hi) sum += power[k]
            val raw = 10f * log10(1e-12f + sum / (hi - lo))
            val level = levelDb[i]
            val db = if (level.isNaN()) raw else level + (raw - level) * min(1f, dtSec / TAU_SEC)
            levelDb[i] = db
            // 最近の最大は、大きい音が来たらすぐ上げ、静かになったら 1 秒に [DECAY_DB] ずつ下げる
            val prev = peakDb[i]
            val peak = if (prev.isNaN()) db else max(db, prev - DECAY_DB * dtSec)
            peakDb[i] = max(peak, MIN_PEAK_DB)
            val v = if (db < GATE_DB) 0f else ((db - (peakDb[i] - WINDOW_DB)) / WINDOW_DB).coerceIn(0f, 1f)
            out[i] = v.pow(CONTRAST)
        }
    }

    private companion object {
        const val LOW_HZ = 50f
        const val HIGH_HZ = 12_000f

        /** 最近の最大から何 dB 下までを棒の高さにするか。帯域の大きさは 20ms ごとに 3.5dB ほど変わるので、実機で敏感すぎると指摘があり 20 から広げた。 */
        const val WINDOW_DB = 30f

        // 実機で棒が全体的に高いと指摘があり、DECAY_DB を 2 から、CONTRAST を 1.5 から変えた。実機の記録（約 20 秒）で計算すると、
        // 平均の高さは 0.66 から 0.45 に、9 割を超える時間は 23% から 7% に下がり、フレームごとの変化は 0.045 から 0.055 に増えるだけだった
        const val DECAY_DB = 0.5f
        const val CONTRAST = 3f

        /**
         * 大きさを時間方向に平均する時定数（秒）。1 区画だけの帯域は、取得（21ms）ごとに数 dB ばらつく。棒の範囲を広げ、上がる速さを下げても、
         * 実機で敏感すぎるという見え方は変わらなかったので、棒にする前の大きさを平均する。
         */
        const val TAU_SEC = 0.08f

        // 大きさは、全振幅の正弦波を 0dB とする。getFft の値（全振幅の正弦波で約 512）で決めた以前の閾値からは、54dB を引くと換算できる

        /** これより小さい音は無音とみなす。波形の ±1 の揺れは 1 区画あたり平均 -64dB で、区画ごとにばらつくので、9dB 上に置く。 */
        const val GATE_DB = -55f

        /**
         * 最近の最大の下限。静かな部分で、小さい音まで棒いっぱいに広げないように。以前の getFft の値で、[GATE_DB] から 24dB 上に置いていた
         * （実機で揺れすぎると指摘があり、差を 14dB から広げた）ので、その差を保つ。実機（2026-11-21、YT Music）の 1 オクターブごとの
         * 平均は、低音〜中音域が -16〜-29dB、2kHz より上が -35〜-53dB だった。
         */
        const val MIN_PEAK_DB = -31f
    }
}
