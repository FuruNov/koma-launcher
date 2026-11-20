package dev.furunov.komalauncher.model.layout

import dev.furunov.komalauncher.model.geometry.Point
import dev.furunov.komalauncher.model.geometry.Polygon

@JvmInline
value class PanelId(
    val value: String,
)

/**
 * コマに何が入るか。種類を増やすときは、ここに 1 つ加え、LayoutParser の Kind と、
 * ホームでの扱い（HomeViewModel の TapPanel、HomeScreen の描画）を加える（README の「増やし方」）。
 */
sealed interface PanelContent {
    /** タップでアプリの一覧を開くコマ。[initialApps] は最初の起動で 1 度だけ使う初期値。 */
    data class Folder(
        val initialApps: List<String>,
    ) : PanelContent

    /** ウィジェットを表示するコマ。どのウィジェットかは端末ごとに違うので、ここには持たない。 */
    data object Widget : PanelContent

    /** タップで全アプリ一覧を開くコマ。フォルダに入れていないアプリへの入口にする。 */
    data object Drawer : PanelContent
}

/**
 * コマに描く画像と、その置き方。
 * [dx]・[dy] はコマの外接長方形の幅・高さに対する、画像の中心のずらし。
 * 値は端を見せない範囲に収めて使う（[ArtLimits]）。
 */
data class PanelArt(
    /** 内部の files/art/<image>.webp を指す。 */
    val image: String,
    /** 画像の幅 / 高さ。null なら画像をまだ取り込んでいない（絵を描かない）。 */
    val aspect: Float? = null,
    val zoom: Float = 1f,
    val dx: Float = 0f,
    val dy: Float = 0f,
)

data class Panel(
    val id: PanelId,
    val content: PanelContent,
    val label: String? = null,
    val art: PanelArt? = null,
)

/**
 * コマ割りの木。モックアップと同じく、コマを線で 2 つに割ることを繰り返して作る。
 * 線の端は、親のコマの周に対する割合（[Split.ta]・[Split.tb]）で持つ。
 * 画面の縦横比が変わっても、線の端は親のコマの縁の同じ割合の位置に来る。
 */
sealed interface LayoutNode {
    data class Leaf(
        val panel: Panel,
    ) : LayoutNode

    /** 線は ta の点から tb の点へ引く。[a] が線の左側（進む向きに対して）、[b] が右側のコマ。 */
    data class Split(
        val ta: Float,
        val tb: Float,
        val a: LayoutNode,
        val b: LayoutNode,
    ) : LayoutNode

    fun leaves(): List<Panel> = when (this) {
        is Leaf -> listOf(panel)
        is Split -> a.leaves() + b.leaves()
    }

    fun mapPanels(transform: (Panel) -> Panel): LayoutNode = when (this) {
        is Leaf -> Leaf(transform(panel))
        is Split -> copy(a = a.mapPanels(transform), b = b.mapPanels(transform))
    }
}

enum class BorderStyle { NONE, SINGLE, DOUBLE }

data class Border(
    val style: BorderStyle = BorderStyle.SINGLE,
    val width: Float = 10f,
    val color: Long = 0xFF111111,
)

/**
 * コマ割りの外側とコマの間。長さは、画面の幅が [refWidth] のときの画素数で書く。
 * 端末では画面の幅 / [refWidth] 倍して使う（モックアップの幅 1220 の数値をそのまま使うため）。
 */
data class Frame(
    val refWidth: Float = 1220f,
    /** 外周の余白。[bleed] なら 0 として扱う。 */
    val margin: Float = 24f,
    val gutter: Float = 26f,
    /** null なら設定の「コマの間の色」を使う。 */
    val gutterColor: Long? = null,
    /** 裁ち落とし。外周の余白を無くし、画面の端に接するコマを端まで広げる。 */
    val bleed: Boolean = false,
    val border: Border = Border(),
)

/** 漫画風の色合い。モックアップで利用者が残したのは「変換しない」と「淡い色合い」だけ（2026-11-20）。 */
enum class Tone { NONE, SOFT }

/**
 * コマの見た目（段階 3）。値はどれも 0〜1。
 * 既定は何もしない（version 2 の初めのファイルに look が無くても、見た目が変わらないように）。
 */
data class Look(
    val tone: Tone = Tone.NONE,
    /** 色合いのかかり具合。0 で元の色、1 で淡い色合いそのもの。 */
    val strength: Float = 1f,
    /** 内側の影の濃さ。 */
    val inner: Float = 0f,
    /** 光の反射の強さ。 */
    val sheen: Float = 0f,
)

/** コマの名前の向き。 */
enum class LabelMode { NONE, VERTICAL, HORIZONTAL }

/** 文字（段階 4）。名前と大見出しは、切り抜きがあれば人物の後ろに置く。 */
data class TextSpec(
    val label: LabelMode = LabelMode.NONE,
    /** true なら英語の名前（モックアップの既定）。 */
    val english: Boolean = true,
    /** 大見出し。空なら出さない。 */
    val masthead: String = "",
    /** 大見出しの縦の位置（コマの高さに対する割合）。 */
    val mastheadY: Float = 0.09f,
    /** 大見出しを置くコマ。null なら時計のコマ。 */
    val mastheadPanel: PanelId? = null,
)

/** ランチャーが描く時計（段階 4）。利用者が選んだ「人物の後ろ」だけを持つ。 */
data class ClockSpec(
    val enabled: Boolean = false,
    /** 置くコマ。null か存在しないコマなら、いちばん大きいコマ。 */
    val panel: PanelId? = null,
    /** コマの外接長方形に対する中心の位置。 */
    val x: Float = 0.5f,
    val y: Float = 0.83f,
    /** 大きさ（モックアップの 0.5〜1.6）。 */
    val size: Float = 0.78f,
)

/** 視差（段階 5）。端末を傾けると、絵・文字・時計を別の量で動かす。 */
data class FxSpec(
    val parallax: Boolean = false,
    /** 動かす量の倍率（モックアップの 0.3〜2）。 */
    val strength: Float = 1f,
) {
    /** 基準の幅（refWidth）のときの、絵・時計・文字を動かす最大の量。モックアップの PARALLAX。 */
    fun artShift(): Float = if (parallax) 26f * strength else 0f

    fun clockShift(): Float = if (parallax) 18f * strength else 0f

    fun textShift(): Float = if (parallax) 7f * strength else 0f
}

/** コマの形の決め方。version 1 と 2 の違いはここに閉じ込め、描画は [LayoutResolver] の結果だけを見る。 */
sealed interface LayoutShape {
    /** version 2。線の木とコマごとの画像。 */
    data class Tree(
        val root: LayoutNode,
        val frame: Frame = Frame(),
        val look: Look = Look(),
        val text: TextSpec = TextSpec(),
        val clock: ClockSpec = ClockSpec(),
        val fx: FxSpec = FxSpec(),
    ) : LayoutShape

    /** version 1。1 枚の絵と、絵に対する 0〜1 の相対座標の多角形。 */
    data class Fixed(
        /** 内部の files/art/ の下のファイル名。null なら見本の仮の絵を描く。 */
        val image: String?,
        /** 画像の幅 / 高さ。画像を読む前にコマの位置を決められるよう、JSON に持たせる。 */
        val aspect: Float,
        val panels: List<FixedPanel>,
    ) : LayoutShape
}

data class FixedPanel(
    val panel: Panel,
    val polygon: Polygon,
)

data class Layout(
    val shape: LayoutShape,
) {
    val version: Int get() = if (shape is LayoutShape.Tree) 2 else 1

    /** コマの一覧。描画と判定の順（後のコマが上）に並ぶ。 */
    val panels: List<Panel> by lazy {
        when (shape) {
            is LayoutShape.Tree -> shape.root.leaves()
            is LayoutShape.Fixed -> shape.panels.map { it.panel }
        }
    }

    /** 画面に描く画像の id（重複なし）。取り込みで写す画像と、描画で読む画像を決めるのに使う。 */
    val images: Set<String> by lazy {
        when (shape) {
            is LayoutShape.Tree -> panels.mapNotNull { it.art?.image }.toSet()
            is LayoutShape.Fixed -> setOfNotNull(shape.image)
        }
    }

    fun panel(id: PanelId): Panel? = panels.firstOrNull { it.id == id }

    /** コマの形を変えずに、コマの中身（画像の縦横比など）を書き換える。 */
    fun mapPanels(transform: (Panel) -> Panel): Layout = Layout(
        when (shape) {
            is LayoutShape.Tree -> shape.copy(root = shape.root.mapPanels(transform))
            is LayoutShape.Fixed -> shape.copy(panels = shape.panels.map { it.copy(panel = transform(it.panel)) })
        },
    )

    companion object {
        /** 横長の帯のコマを上から順に並べる。形を問わないとき（テストなど）に使う。 */
        fun of(panels: List<Panel>): Layout {
            require(panels.isNotEmpty()) { "コマが 1 つ以上要る" }
            val h = 1f / panels.size
            val strips =
                panels.mapIndexed { i, p ->
                    FixedPanel(p, Polygon(listOf(Point(0f, i * h), Point(1f, i * h), Point(1f, (i + 1) * h), Point(0f, (i + 1) * h))))
                }
            return Layout(LayoutShape.Fixed(image = null, aspect = 0.5f, panels = strips))
        }
    }
}
