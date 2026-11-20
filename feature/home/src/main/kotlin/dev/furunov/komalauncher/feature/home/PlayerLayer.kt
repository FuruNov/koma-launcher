package dev.furunov.komalauncher.feature.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.furunov.komalauncher.feature.home.anim.PanelMotion
import dev.furunov.komalauncher.model.media.AudioSpectrum
import dev.furunov.komalauncher.model.media.Spectrum
import dev.furunov.komalauncher.model.media.SpectrumShaper
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ホーム画面が再生中のコマに使うもの。:app が渡す。
 * [spectrum] は「音に合わせて動かす」がオンで、マイクの権限があるときだけ渡す（null ならそれらしい動き）。
 */
class HomeMedia(
    val art: StateFlow<ImageBitmap?>,
    val spectrum: AudioSpectrum?,
)

/** 再生中のコマを操作したときに、中央に一瞬表示する記号。 */
enum class PlayerFlash {
    PLAY,
    PAUSE,
    NEXT,
    PREVIOUS,
    ;

    companion object {
        /** タップした時点で記号を決める（状態が届いた時点で記号が切り替わらないように）。 */
        fun forTap(playing: Boolean): PlayerFlash = if (playing) PAUSE else PLAY
    }
}

/** 表示中の記号と、操作した時刻（System.nanoTime。同じ記号が続いても表示し直すため）。 */
data class PlayerFlashEvent(
    val kind: PlayerFlash,
    val at: Long,
) {
    /** [nanos] より後の操作か。層が現れる前の操作の記号は表示しない。 */
    fun isNewerThan(nanos: Long): Boolean = at > nanos
}

/** ジャケットのクロスフェード。曲が変わったら、前のジャケットを薄くしながら新しいジャケットを濃くする。 */
@Stable
class ArtFade {
    var old by mutableStateOf<ImageBitmap?>(null)
    var current by mutableStateOf<ImageBitmap?>(null)
    val progress = Animatable(1f)
}

@Composable
internal fun rememberArtFade(art: ImageBitmap?): ArtFade {
    val fade = remember { ArtFade() }
    LaunchedEffect(art) {
        if (art === fade.current) return@LaunchedEffect
        fade.old = fade.current
        fade.current = art
        fade.progress.snapTo(0f)
        fade.progress.animateTo(1f, tween(250, easing = LinearEasing))
        fade.old = null
    }
    return fade
}

// 一時停止中のジャケット。彩度をほとんど抜き、少し暗くする
private val pausedFilter =
    ColorFilter.colorMatrix(
        ColorMatrix().apply {
            setToSaturation(0.15f)
            timesAssign(ColorMatrix().apply { setToScale(0.8f, 0.8f, 0.8f, 1f) })
        },
    )

/** ジャケットをコマいっぱいに描く（コマの絵の上に重ねる）。[DrawScope.size] はコマの外接長方形。 */
internal fun DrawScope.drawPlayerArt(
    fade: ArtFade,
    playing: Boolean,
) {
    val filter = if (playing) null else pausedFilter
    val p = fade.progress.value
    // 曲が変わるときは、前のジャケットを不透明のまま残して上に新しいジャケットを重ねる（下のコマの絵が透けないように）。
    // ジャケットが無くなるときだけ、前のジャケットを薄くしてコマの絵に戻す
    fade.old?.let { drawCover(it, if (fade.current != null) 1f else 1f - p, filter) }
    fade.current?.let { drawCover(it, p, filter) }
}

private fun DrawScope.drawCover(
    b: ImageBitmap,
    alpha: Float,
    filter: ColorFilter?,
) {
    if (b.width <= 0 || b.height <= 0 || alpha <= 0f) return
    val s = max(size.width / b.width, size.height / b.height)
    val w = b.width * s
    val h = b.height * s
    drawImage(
        b,
        dstOffset = IntOffset(((size.width - w) / 2f).roundToInt(), ((size.height - h) / 2f).roundToInt()),
        dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
        alpha = alpha,
        colorFilter = filter,
    )
}

/**
 * 再生中のコマの上に重ねる層: 下の辺のビジュアライザー、一時停止の記号、操作の記号。
 * ジャケットはコマの絵と同じ層（PanelLayer）に描く。時計と大見出しをジャケットの上に残すため。
 */
@Composable
internal fun PlayerLayer(
    g: PanelGeometry,
    motion: PanelMotion,
    playing: Boolean,
    barColor: Color,
    spectrum: AudioSpectrum?,
    flash: PlayerFlashEvent?,
) {
    val density = LocalDensity.current
    val b = g.bounds
    // 棒の位置は、コマの外接長方形の左上を原点にして 1 度だけ計算する
    val bars =
        remember(g, density.density) {
            val (p, q) = Spectrum.bottomEdge(g.polygon.points)
            val ax = p.x - b.left
            val ay = p.y - b.top
            val ex = q.x - p.x
            val ey = q.y - p.y
            val len = hypot(ex, ey)
            val n = Spectrum.barCount(len, density.density)
            // 辺に垂直で、コマの内側（画面の上）を向く向き
            var nx = ey / len
            var ny = -ex / len
            if (ny > 0f) {
                nx = -nx
                ny = -ny
            }
            Bars(ax, ay, ex / len, ey / len, nx, ny, len / n, n, min(g.inscribed.height * 0.55f, with(density) { 120.dp.toPx() }))
        }
    val values = remember(bars.count) { FloatArray(bars.count) }
    var frame by remember { mutableLongStateOf(0L) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(playing, spectrum, bars.count) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val target = FloatArray(bars.count)
            val shaper = SpectrumShaper(bars.count)
            var started = playing && spectrum?.start() == true
            var silentSince = -1L
            var lastRestart = 0L
            // 取得できない間（失敗、または一度も音を受け取れないまま無音が 5 秒続く）は、それらしい動きを見せる。
            // 取得は止めず、音が届いたらすぐ実際の音に戻す（曲の静かな出だしや、音の出力先が変わったときに戻れるように）
            var simulate = !started
            // 一度でも音を受け取れたら、その後の無音は本当の無音（曲の間など）とみなし、棒を 0 にする
            var heard = false
            try {
                var last = withFrameNanosCompat()
                while (true) {
                    val t = withFrameNanosCompat()
                    val dt = (t - last) / 1e9f
                    last = t
                    if (!playing) {
                        target.fill(0f)
                    } else {
                        val fft = if (started) spectrum?.read() else null
                        val ok = fft != null
                        if (fft != null && !Spectrum.isSilent(fft)) {
                            silentSince = -1L
                            simulate = false
                            heard = true
                            shaper.shape(fft, spectrum?.sampleRateHz ?: 48_000, dt, target)
                        } else {
                            if (silentSince < 0) silentSince = t
                            val stuck = !ok || t - silentSince > SILENCE_NANOS
                            if (stuck && !heard) simulate = true
                            // 取得できないときは、作り直して試す（間隔を空ける）
                            if (spectrum != null && stuck && t - lastRestart > RESTART_NANOS) {
                                lastRestart = t
                                // start に成功した分だけ stop する（ほかの処理が使っている分まで数を減らさないように）
                                if (started) spectrum.stop()
                                started = spectrum.start()
                            }
                            // 無音なら 0 にする。読み取りに失敗したときも、前の値のまま止まらないよう 0 にする
                            if (!simulate) target.fill(0f)
                        }
                        if (simulate) Spectrum.simulated(bars.count, t / 1e9f, 1f, target)
                    }
                    Spectrum.smooth(values, target, dt)
                    frame = t
                    // 一時停止して棒が下がりきったら、描き直しを止める
                    if (!playing && values.all { it < 0.001f }) break
                }
            } finally {
                if (started) spectrum?.stop()
            }
        }
    }

    val flashProgress = remember { Animatable(1f) }
    // 前回の再生のときの操作の記号を、次に再生したときに表示しないよう、層が現れた時刻より後の操作だけを表示する
    val shownSince = remember { System.nanoTime() }
    // アニメーションを始めた記号。始まる前の 1 フレームも、記号を表示し始めとして描くために使う
    var started by remember { mutableStateOf<PlayerFlashEvent?>(null) }
    LaunchedEffect(flash) {
        if (flash == null || !flash.isNewerThan(shownSince)) return@LaunchedEffect
        flashProgress.snapTo(0f)
        started = flash
        flashProgress.animateTo(1f, tween(600, easing = LinearEasing))
    }

    PanelBox(b.left, b.top, b.width, b.height, motion, clipShape = g.shape) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION")
            frame
            drawBars(bars, values, barColor)
            // 棒はコマの枠の後ろに見せる（枠の上に棒が出ないよう、棒の上から枠を描き直す）
            with(g.face) { drawBorder() }
            val cx = g.inscribed.center.x - b.left
            val cy = g.inscribed.center.y - b.top
            val fresh = flash != null && flash.isNewerThan(shownSince)
            val fp = if (fresh && started !== flash) 0f else flashProgress.value
            val flashing = fresh && fp < 1f
            if (!playing) {
                // 操作の記号が消えていくのに合わせて浮かび上がらせる（2 つの記号が重なって別の記号に見えないように）
                val a = if (flashing) fp else 1f
                drawCircle(Color.Black.copy(alpha = 0.45f * a), radius = 26.dp.toPx(), center = Offset(cx, cy))
                drawPlayMark(cx + 2.dp.toPx(), cy, 22.dp.toPx(), Color.White.copy(alpha = a))
            }
            if (flash != null && flashing) {
                val a = 1f - fp
                drawCircle(Color.Black.copy(alpha = 0.5f * a), radius = (30 + 10 * fp).dp.toPx(), center = Offset(cx, cy))
                val s = 24.dp.toPx()
                val white = Color.White.copy(alpha = a)
                when (flash.kind) {
                    PlayerFlash.NEXT -> drawSkipMark(cx, cy, s, 1f, white)
                    PlayerFlash.PREVIOUS -> drawSkipMark(cx, cy, s, -1f, white)
                    PlayerFlash.PAUSE -> drawPauseMark(cx, cy, s, white)
                    PlayerFlash.PLAY -> drawPlayMark(cx + 2.dp.toPx(), cy, s, white)
                }
            }
        }
    }
}

private class Bars(
    val ax: Float,
    val ay: Float,
    val ux: Float,
    val uy: Float,
    val nx: Float,
    val ny: Float,
    val gap: Float,
    val count: Int,
    val maxHeight: Float,
)

private fun DrawScope.drawBars(
    bars: Bars,
    values: FloatArray,
    color: Color,
) {
    val width = bars.gap * 0.55f
    for (i in 0 until bars.count) {
        val v = values[i]
        val h = 2.dp.toPx() + v * bars.maxHeight
        val mx = bars.ax + bars.ux * bars.gap * (i + 0.5f)
        val my = bars.ay + bars.uy * bars.gap * (i + 0.5f)
        drawLine(color.copy(alpha = 0.6f + 0.4f * v), Offset(mx, my), Offset(mx + bars.nx * h, my + bars.ny * h), strokeWidth = width)
    }
}

private fun DrawScope.drawPlayMark(
    cx: Float,
    cy: Float,
    s: Float,
    color: Color,
) {
    val path =
        Path().apply {
            moveTo(cx - s * 0.38f, cy - s / 2f)
            lineTo(cx + s * 0.5f, cy)
            lineTo(cx - s * 0.38f, cy + s / 2f)
            close()
        }
    drawPath(path, color)
}

private fun DrawScope.drawPauseMark(
    cx: Float,
    cy: Float,
    s: Float,
    color: Color,
) {
    drawRect(color, topLeft = Offset(cx - s * 0.45f, cy - s / 2f), size = androidx.compose.ui.geometry.Size(s * 0.3f, s))
    drawRect(color, topLeft = Offset(cx + s * 0.15f, cy - s / 2f), size = androidx.compose.ui.geometry.Size(s * 0.3f, s))
}

/** [dir] が 1 なら次の曲（右向き）、-1 なら前の曲。 */
private fun DrawScope.drawSkipMark(
    cx: Float,
    cy: Float,
    s: Float,
    dir: Float,
    color: Color,
) {
    val path =
        Path().apply {
            moveTo(cx - dir * s / 2f, cy - s / 2f)
            lineTo(cx + dir * s * 0.2f, cy)
            lineTo(cx - dir * s / 2f, cy + s / 2f)
            close()
        }
    drawPath(path, color)
    val barLeft = if (dir > 0) cx + s * 0.25f else cx - s * 0.43f
    drawRect(color, topLeft = Offset(barLeft, cy - s / 2f), size = androidx.compose.ui.geometry.Size(s * 0.18f, s))
}

private suspend fun withFrameNanosCompat(): Long = androidx.compose.runtime.withFrameNanos { it }

private const val SILENCE_NANOS = 5_000_000_000L
private const val RESTART_NANOS = 3_000_000_000L
