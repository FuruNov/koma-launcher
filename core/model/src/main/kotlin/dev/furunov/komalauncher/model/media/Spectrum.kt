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
        tuning: SpectrumTuning = SpectrumTuning(),
    ) {
        val rise = min(1f, dtSec * tuning.rise)
        val fall = min(1f, dtSec * tuning.fall)
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
 * 帯域ごとのパワーを棒の高さ（0〜1）に変える。帯域ごとに最近の最大の大きさを覚えて、そこから [SpectrumTuning.windowDb] 下までを 0〜1 に割り当てる。
 *
 * 実機（2026-11-20、YT Music、Visualizer.getFft の値）で記録した 1 オクターブごとの大きさは、低音が 10〜43dB と大きく変わる一方、中音域は 25〜40dB を
 * ±5dB ほど揺れるだけで、8kHz より上はほぼ雑音（0〜8dB）だった。大きさを対数で 0〜128 に割り当てるだけでは、中音域の棒が
 * 常に 6〜8 割の高さで細かく震え、音に追従していても偽物の動きに見えた。そこで、
 * - 帯域は [LOW_HZ]〜[HIGH_HZ] だけを対数の間隔で並べる（音楽の中身がある範囲）
 * - 帯域の中の平均の大きさ（dB）を使う（最大の値より安定する）
 * - 大きさを時間方向にも平均する（[SpectrumTuning.tauSec]）
 * - 帯域ごとの最近の最大に合わせて範囲を決め、曲や帯域ごとの音量の違いを打ち消す
 * - [SpectrumTuning.contrast] 乗して、大きい音と小さい音の差を強める
 * 状態（平均した大きさと最近の最大）を持つので、棒の並びごとに 1 つ作る。
 */
class SpectrumShaper(
    private val count: Int,
    private val tuning: SpectrumTuning = SpectrumTuning(),
) {
    private val levelDb = FloatArray(count) { Float.NaN }
    private val peakDb = FloatArray(count) { Float.NaN }
    private val rawDb = FloatArray(count)

    /** [power] を [out]（長さ [count]）に書く。[dtSec] は前の呼び出しからの時間。 */
    fun shape(
        power: FloatArray,
        sampleRateHz: Int,
        dtSec: Float,
        out: FloatArray,
    ) {
        bandDb(power, sampleRateHz, rawDb)
        val t = tuning
        for (i in 0 until count) {
            val raw = rawDb[i]
            val level = levelDb[i]
            val db = if (level.isNaN() || raw - level > t.jumpDb) raw else level + (raw - level) * min(1f, dtSec / t.tauSec)
            levelDb[i] = db
            // 最近の最大は、大きい音が来たらすぐ上げ、静かになったら 1 秒に [SpectrumTuning.decayDb] ずつ下げる
            val prev = peakDb[i]
            val peak = if (prev.isNaN()) db else max(db, prev - t.decayDb * dtSec)
            peakDb[i] = max(peak, t.minPeakDb)
            val v = if (db < t.gateDb) 0f else ((db - (peakDb[i] - t.windowDb)) / t.windowDb).coerceIn(0f, 1f)
            out[i] = v.pow(t.contrast)
        }
    }

    /** 棒ごとの帯域の平均の大きさ（dB、時間方向には平均しない）を [out]（長さ [count]）に書く。記録の再生で、棒の動きと比べるのにも使う。 */
    fun bandDb(
        power: FloatArray,
        sampleRateHz: Int,
        out: FloatArray,
    ) {
        val r = ranges(power.size, sampleRateHz)
        for (i in 0 until count) {
            val lo = r[2 * i]
            val hi = r[2 * i + 1]
            var sum = 0f
            for (k in lo until hi) sum += power[k]
            out[i] = 10f * log10(1e-12f + sum / (hi - lo))
        }
    }

    private var rangesKey = 0L
    private var rangesCache = IntArray(0)

    /**
     * 棒 i が読む区画の範囲（[lo, hi)）を、[2i] と [2i + 1] に並べて返す。[bins] は区画の数（波形の長さの半分）。
     * 毎フレーム呼ぶので、区画の数と標本化周波数が変わったときだけ計算し直す。
     */
    fun ranges(
        bins: Int,
        sampleRateHz: Int,
    ): IntArray {
        val key = bins.toLong() shl 32 or sampleRateHz.toLong()
        if (key == rangesKey) return rangesCache
        val out = IntArray(2 * count)
        val hzPerBin = sampleRateHz / 2f / bins
        if (tuning.minBins > 0) {
            var lo = max(1, (LOW_HZ / hzPerBin).roundToInt())
            val top = (HIGH_HZ / hzPerBin).roundToInt().coerceAtMost(bins)
            for (i in 0 until count) {
                val step = (top.toFloat() / lo).pow(1f / (count - i))
                val hi = max((lo * step).roundToInt(), lo + tuning.minBins).coerceAtMost(bins)
                out[2 * i] = lo
                out[2 * i + 1] = hi
                lo = hi.coerceAtMost(bins - 1)
            }
        } else {
            val ratio = HIGH_HZ / LOW_HZ
            // 低い帯域では 1 区画（約 47Hz）より帯域が狭く、複数の棒が同じ区画に当たる。前の棒の区画と重ならないよう、後ろへ詰める
            var next = 1
            for (i in 0 until count) {
                val fLo = LOW_HZ * ratio.pow(i.toFloat() / count)
                val fHi = LOW_HZ * ratio.pow((i + 1).toFloat() / count)
                val lo = max((fLo / hzPerBin).roundToInt(), next).coerceIn(1, bins - 1)
                val hi = (fHi / hzPerBin).roundToInt().coerceIn(lo + 1, bins)
                next = hi
                out[2 * i] = lo
                out[2 * i + 1] = hi
            }
        }
        rangesKey = key
        rangesCache = out
        return out
    }

    private companion object {
        const val LOW_HZ = 50f
        const val HIGH_HZ = 12_000f
    }
}
