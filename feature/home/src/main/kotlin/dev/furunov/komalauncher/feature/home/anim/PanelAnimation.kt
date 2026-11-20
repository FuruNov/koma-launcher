package dev.furunov.komalauncher.feature.home.anim

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import dev.furunov.komalauncher.model.geometry.Rect
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 1 つのコマの動きの値。graphicsLayer のラムダの中で読むので、値が変わっても
 * 描画の段階だけが更新され、画面の作り直し（recomposition）は起きない。
 */
@Stable
class PanelMotion {
    val dx = Animatable(0f)
    val dy = Animatable(0f)
    val scale = Animatable(1f)
    val alpha = Animatable(1f)

    /** 押している間の沈み込み（0〜1）。影の濃さにも使う。 */
    val press = Animatable(0f)

    suspend fun reset() {
        dx.snapTo(0f)
        dy.snapTo(0f)
        scale.snapTo(1f)
        alpha.snapTo(1f)
        press.snapTo(0f)
    }
}

/** コマが動く場面の情報。画面のどちら側にあるかで、出てくる向きを決める。 */
class MotionContext(
    val bounds: Rect,
    val screenWidth: Float,
    val screenHeight: Float,
    /** 何枚目に動かすか（時間をずらすため）。 */
    val order: Int,
    val staggerMs: Int,
    /** 1 が標準。大きいほどゆっくり。 */
    val speed: Float,
)

/**
 * アニメーションの 1 案。案を増やすときは、このインターフェースを実装したクラスを 1 つ書き、
 * [PanelAnimations.registry] に名前で登録する。設定画面の選択肢にも自動で並ぶ。
 */
interface PanelAnimation {
    val id: String
    val label: String

    /** ロック解除のときに、コマを画面に入れる。 */
    suspend fun enter(m: PanelMotion, c: MotionContext)

    /** 指が触れたとき。 */
    suspend fun pressDown(m: PanelMotion, c: MotionContext)

    /** 指が離れたとき（タップが成立した）。この後にフォルダが開く。 */
    suspend fun pressUp(m: PanelMotion, c: MotionContext)

    /** 指が離れたが、タップにならなかった（スワイプなど）。 */
    suspend fun pressCancel(m: PanelMotion, c: MotionContext) = together {
        launch { m.scale.animateTo(1f, spring(stiffness = Spring.StiffnessMedium)) }
        launch { m.press.animateTo(0f) }
    }
}

private fun Float.ms(speed: Float) = (this * speed).toInt()

/** 画面の外側の辺の方向。コマの中心が近い側の辺から出てくる。 */
private fun outward(c: MotionContext): Pair<Float, Float> {
    val cx = c.bounds.center.x / c.screenWidth - 0.5f
    val cy = c.bounds.center.y / c.screenHeight - 0.5f
    return if (kotlin.math.abs(cx) * c.screenWidth > kotlin.math.abs(cy) * c.screenHeight * 0.6f) {
        (if (cx < 0) -1f else 1f) to 0f
    } else {
        0f to (if (cy < 0) -1f else 1f)
    }
}

/** 外側から少し縮んだ状態で滑り込み、少し行き過ぎて戻る。 */
object SlideOvershoot : PanelAnimation {
    override val id = "slide-overshoot"
    override val label = "スライドイン"

    override suspend fun enter(m: PanelMotion, c: MotionContext) {
        val (ox, oy) = outward(c)
        m.dx.snapTo(ox * c.bounds.width * 0.6f)
        m.dy.snapTo(oy * c.bounds.height * 0.6f)
        m.scale.snapTo(0.8f)
        m.alpha.snapTo(0f)
        delay((c.order * c.staggerMs).toFloat().ms(c.speed).toLong())
        coroutineScope {
            val s = spring<Float>(dampingRatio = 0.55f, stiffness = 380f / (c.speed * c.speed))
            launch { m.dx.animateTo(0f, s) }
            launch { m.dy.animateTo(0f, s) }
            launch { m.scale.animateTo(1f, s) }
            launch { m.alpha.animateTo(1f, tween(180f.ms(c.speed))) }
        }
    }

    override suspend fun pressDown(m: PanelMotion, c: MotionContext) = Squash.pressDown(m, c)

    override suspend fun pressUp(m: PanelMotion, c: MotionContext) = Squash.pressUp(m, c)
}

/** その場でふくらんで現れる。動きを控えめにしたいとき用。 */
object PopIn : PanelAnimation {
    override val id = "pop-in"
    override val label = "ポップ"

    override suspend fun enter(m: PanelMotion, c: MotionContext) {
        m.scale.snapTo(0.4f)
        m.alpha.snapTo(0f)
        delay((c.order * c.staggerMs).toFloat().ms(c.speed).toLong())
        coroutineScope {
            launch { m.scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f / (c.speed * c.speed))) }
            launch { m.alpha.animateTo(1f, tween(120f.ms(c.speed))) }
        }
    }

    override suspend fun pressDown(m: PanelMotion, c: MotionContext) = Squash.pressDown(m, c)

    override suspend fun pressUp(m: PanelMotion, c: MotionContext) = Squash.pressUp(m, c)
}

/** ページをめくるように、上から順に落ちてくる。 */
object Drop : PanelAnimation {
    override val id = "drop"
    override val label = "ドロップ"
    private val easeOutBack = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

    override suspend fun enter(m: PanelMotion, c: MotionContext) {
        m.dy.snapTo(-c.bounds.bottom - 40f)
        m.alpha.snapTo(1f)
        delay((c.order * c.staggerMs * 1.4f).ms(c.speed).toLong())
        m.dy.animateTo(0f, tween(420f.ms(c.speed), easing = easeOutBack))
    }

    override suspend fun pressDown(m: PanelMotion, c: MotionContext) = Squash.pressDown(m, c)

    override suspend fun pressUp(m: PanelMotion, c: MotionContext) = Squash.pressUp(m, c)
}

/** タップ: 押すと沈み、離すと弾んで戻る。 */
object Squash : PanelAnimation {
    override val id = "squash"
    override val label = "バウンス"

    override suspend fun enter(m: PanelMotion, c: MotionContext) = SlideOvershoot.enter(m, c)

    override suspend fun pressDown(m: PanelMotion, c: MotionContext) = together {
        launch { m.scale.animateTo(0.94f, tween(90f.ms(c.speed))) }
        launch { m.press.animateTo(1f, tween(90f.ms(c.speed))) }
    }

    override suspend fun pressUp(m: PanelMotion, c: MotionContext) = together {
        launch { m.press.animateTo(0f, tween(160f.ms(c.speed))) }
        m.scale.animateTo(1.04f, tween(90f.ms(c.speed)))
        m.scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
    }
}

/** タップ: 押すとわずかに傾いて持ち上がる。 */
object Lift : PanelAnimation {
    override val id = "lift"
    override val label = "リフト"

    override suspend fun enter(m: PanelMotion, c: MotionContext) = SlideOvershoot.enter(m, c)

    override suspend fun pressDown(m: PanelMotion, c: MotionContext) = together {
        launch { m.scale.animateTo(1.03f, tween(110f.ms(c.speed))) }
        launch { m.press.animateTo(1f, tween(110f.ms(c.speed))) }
    }

    override suspend fun pressUp(m: PanelMotion, c: MotionContext) = together {
        launch { m.press.animateTo(0f, tween(200f.ms(c.speed))) }
        m.scale.animateTo(1f, spring(dampingRatio = 0.6f))
    }
}

object PanelAnimations {
    val enterChoices: List<PanelAnimation> = listOf(SlideOvershoot, PopIn, Drop)
    val pressChoices: List<PanelAnimation> = listOf(Squash, Lift)
    private val registry: Map<String, PanelAnimation> = (enterChoices + pressChoices).associateBy { it.id }

    fun enter(id: String): PanelAnimation = registry[id] ?: SlideOvershoot

    fun press(id: String): PanelAnimation = registry[id] ?: Squash
}

/** 複数の値を同時に動かし、すべて終わるまで待つ。戻り値を Unit にそろえるための小さな包み。 */
private suspend fun together(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = coroutineScope { block() }
