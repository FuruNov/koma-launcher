package dev.furunov.komalauncher.model.layout

import dev.furunov.komalauncher.model.geometry.Point
import dev.furunov.komalauncher.model.geometry.Polygon

@JvmInline
value class PanelId(
    val value: String,
)

/**
 * コマに何が入るか。種類を増やすときは、ここに 1 つ加え、
 * UI 側の PanelKindHandler を 1 つ登録する。既存の種類のコードは触らない。
 */
sealed interface PanelContent {
    /** タップでアプリの一覧を開くコマ。[initialApps] は最初の起動で 1 度だけ使う初期値。 */
    data class Folder(
        val initialApps: List<String>,
    ) : PanelContent

    /** ウィジェットを表示するコマ。どのウィジェットかは端末ごとに違うので、ここには持たない。 */
    data object Widget : PanelContent
}

data class Panel(
    val id: PanelId,
    /** 画像に対する 0〜1 の相対座標。 */
    val polygon: Polygon,
    val content: PanelContent,
    val label: String? = null,
)

data class Layout(
    val version: Int,
    /** 端末上の画像のファイル名。null なら見本の仮の絵を描く。 */
    val image: String?,
    /** 画像の幅 / 高さ。画像を読む前にコマの位置を決められるよう、JSON に持たせる。 */
    val aspect: Float,
    val panels: List<Panel>,
) {
    /**
     * [p]（画像の相対座標）にあるコマ。重なっている場合は後に書いたコマを優先する。
     * 描画も後のコマが上に来るので、見た目と判定をそろえるため。
     */
    fun panelAt(p: Point): Panel? = panels.lastOrNull { it.polygon.contains(p) }

    fun panel(id: PanelId): Panel? = panels.firstOrNull { it.id == id }
}
