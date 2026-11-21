package dev.furunov.komalauncher.tools

import dev.furunov.komalauncher.model.media.PowerSpectrum
import dev.furunov.komalauncher.model.media.Spectrum
import dev.furunov.komalauncher.model.media.SpectrumRecording
import dev.furunov.komalauncher.model.media.SpectrumShaper
import dev.furunov.komalauncher.model.media.SpectrumTuning
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.File
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.system.exitProcess

private const val USAGE = """
使い方: ./gradlew :tools:vis-replay:run --args="記録.bin [--out ディレクトリ] [--bars 33] [--video] [--config 名前:キー=値,キー=値 ...]"

記録は debug 版の DEBUG_VIS（--ei record 秒数）で実機に書き、adb exec-out run-as で取り出す。
--config を省くと、アプリの既定の値（current）だけを再生する。--config を書くと、current に加えてその設定も再生する。
キーは SpectrumTuning の名前（windowDb, decayDb, contrast, tauSec, jumpDb, minBins, gateDb, minPeakDb, rise, fall, quietDb）。
例: --config slow:tauSec=0.15,rise=10 --config low:contrast=4
"""

/**
 * 再生した 1 つの設定の結果。[bars] はフレームごとの棒の高さ、[gated] はそのフレームを無音として扱ったか。
 * [raw] はフレームごとの、その設定の棒の並びでの帯域の生の大きさ（dB、時間方向に平均せず、最近の最大にも合わせない）で、棒の動きと比べる基準にする。
 * [lowBars] は、キックの立ち上がりを検出する帯域（[KICK_HZ] まで）に収まる棒の数（最低 1 本）。
 */
private class Run(
    val name: String,
    val tuning: SpectrumTuning,
    val bars: List<FloatArray>,
    val gated: BooleanArray,
    val raw: List<FloatArray>,
    val lowBars: Int,
)

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] == "--help") {
        println(USAGE.trim())
        exitProcess(if (args.isEmpty()) 1 else 0)
    }
    val input = File(args[0])
    var out = File("build/vis-replay")
    var barCount = 33
    var video = false
    val configs = mutableListOf("current" to SpectrumTuning())
    var i = 1
    while (i < args.size) {
        when (args[i]) {
            "--out" -> out = File(args[++i])
            "--bars" -> barCount = args[++i].toInt()
            "--video" -> video = true
            "--config" -> configs += parseConfig(args[++i])
            else -> error("知らない引数: ${args[i]}\n$USAGE")
        }
        i++
    }
    val frames = input.inputStream().use { SpectrumRecording.read(it) }
    require(frames.size >= 2) { "記録が短すぎる（${frames.size} フレーム）" }
    val seconds = (frames.last().timeNanos - frames.first().timeNanos) / 1e9
    println("記録: ${input.name}、${frames.size} フレーム、%.1f 秒、棒 $barCount 本".format(seconds))
    out.mkdirs()
    val runs = configs.map { (name, tuning) -> replay(name, tuning, frames, barCount) }
    printTable(runs, frames, kickDb(frames))
    for (r in runs) writeCsv(File(out, "${r.name}.csv"), r, frames)
    if (video) renderVideo(File(out, "bars.mp4"), runs, frames)
    println("出力: ${out.path}")
}

private fun parseConfig(s: String): Pair<String, SpectrumTuning> {
    val (name, body) = s.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    var t = SpectrumTuning()
    for (kv in body.split(",").filter { it.isNotBlank() }) {
        val (k, v) = kv.split("=").let { it[0].trim() to it[1].trim().toFloat() }
        t =
            when (k) {
                "windowDb" -> t.copy(windowDb = v)
                "decayDb" -> t.copy(decayDb = v)
                "contrast" -> t.copy(contrast = v)
                "tauSec" -> t.copy(tauSec = v)
                "jumpDb" -> t.copy(jumpDb = v)
                "minBins" -> t.copy(minBins = v.toInt())
                "gateDb" -> t.copy(gateDb = v)
                "minPeakDb" -> t.copy(minPeakDb = v)
                "rise" -> t.copy(rise = v)
                "fall" -> t.copy(fall = v)
                "quietDb" -> t.copy(quietDb = v)
                else -> error("知らないキー: $k")
            }
    }
    return name to t
}

/** フレームごとの、[KICK_HZ] までの区画（1 番目から）のパワーの平均（dB）。棒の並びによらず、キックの立ち上がりの検出に使う。 */
private fun kickDb(frames: List<SpectrumRecording.Frame>): List<Double> {
    val ps = PowerSpectrum()
    var power = FloatArray(0)
    return frames.map { f ->
        if (power.size != f.wave.size / 2) power = FloatArray(f.wave.size / 2)
        ps.compute(f.wave, power)
        val hi = kickBins(power.size, f.sampleRateHz)
        10 * log10(1e-12 + (1 until hi).sumOf { power[it].toDouble() } / (hi - 1))
    }
}

private fun kickBins(
    bins: Int,
    sampleRateHz: Int,
): Int = (KICK_HZ / (sampleRateHz / 2.0 / bins)).toInt().coerceIn(2, bins)

/** アプリのホーム（PlayerLayer）と同じ順で計算する。無音のフレームは棒の目標を 0 にし、smooth で寄せる。 */
private fun replay(
    name: String,
    tuning: SpectrumTuning,
    frames: List<SpectrumRecording.Frame>,
    count: Int,
): Run {
    val shaper = SpectrumShaper(count, tuning)
    val ps = PowerSpectrum()
    val values = FloatArray(count)
    val target = FloatArray(count)
    var power = FloatArray(0)
    val bars = ArrayList<FloatArray>(frames.size)
    val raw = ArrayList<FloatArray>(frames.size)
    val gated = BooleanArray(frames.size)
    for ((i, f) in frames.withIndex()) {
        val dt = if (i == 0) 1f / 60 else (f.timeNanos - frames[i - 1].timeNanos) / 1e9f
        val silent = f.wave.all { it == 0x80.toByte() }
        gated[i] = silent || tuning.isQuiet(f.rmsDb, f.volumeDb)
        if (power.size != f.wave.size / 2) power = FloatArray(f.wave.size / 2)
        ps.compute(f.wave, power)
        raw += FloatArray(count).also { shaper.bandDb(power, f.sampleRateHz, it) }
        if (gated[i]) target.fill(0f) else shaper.shape(power, f.sampleRateHz, dt, target)
        Spectrum.smooth(values, target, dt, tuning)
        bars += values.copyOf()
    }
    val f = frames[0]
    val r = shaper.ranges(f.wave.size / 2, f.sampleRateHz)
    val lowBars = (0 until count).count { r[2 * it + 1] <= kickBins(f.wave.size / 2, f.sampleRateHz) }.coerceAtLeast(1)
    return Run(name, tuning, bars, gated, raw, lowBars)
}

private fun printTable(
    runs: List<Run>,
    frames: List<SpectrumRecording.Frame>,
    kick: List<Double>,
) {
    val t = { i: Int -> (frames[i].timeNanos - frames[0].timeNanos) / 1e9 }
    println()
    val onsets = kickOnsets(runs[0].gated, kick, t)
    // キックで棒が跳ねるのは望ましい動きなので、「変化」は立ち上がりから [ONSET_WINDOW_SEC] 以内のフレームを除いて測る
    val nearOnset = BooleanArray(frames.size)
    for (o in onsets) {
        var j = o
        while (j < frames.size && t(j) - t(o) <= ONSET_WINDOW_SEC) nearOnset[j++] = true
    }
    println("低音（%.0fHz まで）の立ち上がり: ${onsets.size} 回".format(KICK_HZ))
    println("%-12s %6s %7s %7s %6s %9s %14s %14s".format("設定", "平均", "9割超", "変化", "相関", "キック応答", "0になるまで", "上がるまで"))
    for (r in runs) {
        var sum = 0.0
        var n = 0
        var top = 0
        var change = 0.0
        var changeN = 0
        for (i in 1 until r.bars.size) {
            if (r.gated[i]) continue
            for (k in r.bars[i].indices) {
                val v = r.bars[i][k]
                sum += v
                n++
                if (v > 0.9f) top++
                if (nearOnset[i]) continue
                val d = v - r.bars[i - 1][k]
                change += d * d
                changeN++
            }
        }
        // 0 になるまで: 0.5 秒以上の音の後に無音として扱い始めてから、すべての棒が 0.05 を下回るまでの時間
        // 上がるまで: 0.3 秒以上の無音の後に音として扱い始めてから、いずれかの棒が 0.5 を超えるまでの時間
        val release = mutableListOf<Double>()
        val attack = mutableListOf<Double>()
        var runStart = 0
        for (i in 1 until r.bars.size) {
            if (r.gated[i] == r.gated[i - 1]) continue
            val longEnough = t(i) - t(runStart) >= if (r.gated[i]) 0.5 else 0.3
            runStart = i
            if (!longEnough) continue
            val end = (i until r.bars.size).firstOrNull { j ->
                if (r.gated[i]) r.bars[j].all { it < 0.05f } else r.bars[j].any { it > 0.5f }
            } ?: continue
            (if (r.gated[i]) release else attack) += t(end) - t(i)
        }
        val fmt = { xs: List<Double> -> if (xs.isEmpty()) "-" else "%.2f秒(%d回)".format(xs.average(), xs.size) }
        val hits = onsets.mapNotNull { kickResponse(r, it, t) }
        val kickText = if (onsets.isEmpty()) "-" else "%.0f%%/%.0fms".format(100.0 * hits.size / onsets.size, if (hits.isEmpty()) 0.0 else hits.average() * 1000)
        println(
            "%-12s %6.2f %6.1f%% %7.3f %6.2f %9s %14s %14s".format(
                r.name,
                sum / n,
                100.0 * top / n,
                sqrt(change / changeN),
                correlation(r),
                "$kickText(${r.lowBars}本)",
                fmt(release),
                fmt(attack),
            ),
        )
    }
    val gatedSec = (1 until frames.size).filter { runs[0].gated[it] }.sumOf { t(it) - t(it - 1) }
    println()
    println("無音として扱った時間（current）: %.1f 秒".format(gatedSec))
}

/** [KICK_HZ] までの帯域の生の大きさ（パワーの平均）が、[ONSET_WINDOW_SEC] 以内に [ONSET_DB] 以上上がったフレーム。間隔を 0.2 秒以上空ける。 */
private fun kickOnsets(
    gated: BooleanArray,
    low: List<Double>,
    t: (Int) -> Double,
): List<Int> {
    val onsets = mutableListOf<Int>()
    var from = 0
    for (i in low.indices) {
        while (t(i) - t(from) > ONSET_WINDOW_SEC) from++
        if (gated[i] || (from until i).any { gated[it] }) continue
        val min = (from until i).minOfOrNull { low[it] } ?: continue
        if (low[i] - min >= ONSET_DB && (onsets.isEmpty() || t(i) - t(onsets.last()) >= 0.2)) onsets += i
    }
    return onsets
}

/** 立ち上がりから [ONSET_WINDOW_SEC] 以内に、低音の棒（[Run.lowBars] 本）の平均が 0.1 以上上がるまでの時間（秒）。上がらなければ null。 */
private fun kickResponse(
    r: Run,
    onset: Int,
    t: (Int) -> Double,
): Double? {
    val h = { i: Int -> (0 until r.lowBars).map { r.bars[i][it] }.average() }
    val start = h(onset)
    var i = onset
    while (i < r.bars.size && t(i) - t(onset) <= ONSET_WINDOW_SEC) {
        if (h(i) >= start + 0.1) return t(i) - t(onset)
        i++
    }
    return null
}

/** 棒ごとに、高さと帯域の生の大きさの相関係数を出し、棒の間で平均する。無音として扱ったフレームは除く。 */
private fun correlation(r: Run): Double {
    val idx = r.bars.indices.filter { !r.gated[it] }
    return r.bars[0].indices.map { k ->
        val x = idx.map { r.bars[it][k].toDouble() }
        val y = idx.map { r.raw[it][k].toDouble() }
        val mx = x.average()
        val my = y.average()
        val cov = x.indices.sumOf { (x[it] - mx) * (y[it] - my) }
        val vx = x.sumOf { (it - mx) * (it - mx) }
        val vy = y.sumOf { (it - my) * (it - my) }
        if (vx == 0.0 || vy == 0.0) 0.0 else cov / sqrt(vx * vy)
    }.average()
}

private const val KICK_HZ = 281.0
private const val ONSET_DB = 10.0
private const val ONSET_WINDOW_SEC = 0.15

private fun writeCsv(
    file: File,
    r: Run,
    frames: List<SpectrumRecording.Frame>,
) {
    file.bufferedWriter().use { w ->
        w.write("time,gated,rmsDb,volumeDb," + r.bars[0].indices.joinToString(",") { "bar$it" } + "\n")
        for (i in frames.indices) {
            val f = frames[i]
            w.write("%.4f,%d,%.1f,%.1f,".format((f.timeNanos - frames[0].timeNanos) / 1e9, if (r.gated[i]) 1 else 0, f.rmsDb, f.volumeDb))
            w.write(r.bars[i].joinToString(",") { "%.3f".format(it) } + "\n")
        }
    }
}

/** 設定ごとの棒を上下に並べた動画を、ffmpeg で 30 フレーム毎秒の mp4 にする。 */
private fun renderVideo(
    file: File,
    runs: List<Run>,
    frames: List<SpectrumRecording.Frame>,
) {
    val count = runs[0].bars[0].size
    val barW = 12
    val rowH = 140
    val labelH = 22
    val w = count * barW + 40
    val h = runs.size * (rowH + labelH) + 30
    val fps = 30
    val img = BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR)
    val pixels = (img.raster.dataBuffer as DataBufferByte).data
    val ffmpeg =
        ProcessBuilder(
            "ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", "${w}x$h", "-r", "$fps", "-i", "-",
            "-pix_fmt", "yuv420p", "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2", file.path,
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start()
    val t0 = frames[0].timeNanos
    val total = (frames.last().timeNanos - t0) / 1e9
    var j = 0
    ffmpeg.outputStream.buffered().use { sink ->
        var k = 0
        while (k / fps.toDouble() <= total) {
            val time = k / fps.toDouble()
            while (j + 1 < frames.size && (frames[j + 1].timeNanos - t0) / 1e9 <= time) j++
            val g = img.createGraphics()
            g.color = Color(18, 18, 22)
            g.fillRect(0, 0, w, h)
            g.font = Font(Font.MONOSPACED, Font.PLAIN, 14)
            g.color = Color.WHITE
            g.drawString("%.1f s%s".format(time, if (runs[0].gated[j]) "  (silent)" else ""), 20, 20)
            for ((row, r) in runs.withIndex()) {
                val top = 30 + row * (rowH + labelH)
                g.color = Color(200, 200, 210)
                g.drawString(r.name, 20, top + 16)
                val base = top + labelH + rowH
                g.color = Color(110, 190, 255)
                for (b in 0 until count) {
                    val bh = (r.bars[j][b] * rowH).toInt()
                    g.fillRect(20 + b * barW, base - bh, barW - 3, bh)
                }
            }
            g.dispose()
            sink.write(pixels)
            k++
        }
    }
    check(ffmpeg.waitFor() == 0) { "ffmpeg が失敗した" }
    println("動画: ${file.path}")
}
