package dev.furunov.komalauncher.feature.drawer

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size as sizeModifier

/** 「すべて」の記号の id。フォルダの記号（DrawerFolders.symbols）とは別に、一覧の先頭だけで使う。 */
internal const val ALL_SYMBOL = "all"

/**
 * フォルダの記号。24×24 の格子に線で描き、[size] に合わせて拡大する。
 * Material のアイコンのライブラリは入れない方針（README の「依存を絞った理由」）なので、自前で描く。
 * 知らない id はフォルダの記号（DrawerFolders.FOLDER_SYMBOL）で描く。
 */
@Composable
internal fun FolderSymbol(
    id: String,
    color: Color,
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.sizeModifier(size)) {
        val u = this.size.minDimension / 24f
        SymbolPen(this, u, color).draw(id)
    }
}

private class SymbolPen(
    val scope: DrawScope,
    val u: Float,
    val color: Color,
) {
    private val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)

    private fun p(
        x: Float,
        y: Float,
    ) = Offset(x * u, y * u)

    private fun line(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ) = scope.drawLine(color, p(x1, y1), p(x2, y2), stroke.width, StrokeCap.Round)

    private fun circle(
        x: Float,
        y: Float,
        r: Float,
        fill: Boolean = false,
    ) = scope.drawCircle(color, r * u, p(x, y), style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else stroke)

    private fun rect(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        r: Float = 2f,
        fill: Boolean = false,
    ) = scope.drawRoundRect(
        color,
        p(x, y),
        Size(w * u, h * u),
        CornerRadius(r * u),
        style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else stroke,
    )

    /** 格子の座標の点を順に結ぶ。[close] なら閉じる。 */
    private fun poly(
        vararg xy: Float,
        close: Boolean = false,
        fill: Boolean = false,
    ) {
        val path = Path()
        path.moveTo(xy[0] * u, xy[1] * u)
        for (i in 2 until xy.size step 2) path.lineTo(xy[i] * u, xy[i + 1] * u)
        if (close) path.close()
        scope.drawPath(path, color, style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else stroke)
    }

    private fun arc(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        start: Float,
        sweep: Float,
    ) = scope.drawArc(color, start, sweep, false, p(x, y), Size(w * u, h * u), style = stroke)

    fun draw(id: String) {
        when (id) {
            ALL_SYMBOL -> {
                rect(4f, 4f, 6.5f, 6.5f)
                rect(13.5f, 4f, 6.5f, 6.5f)
                rect(4f, 13.5f, 6.5f, 6.5f)
                rect(13.5f, 13.5f, 6.5f, 6.5f)
            }

            "game" -> {
                rect(2.5f, 7f, 19f, 11f, r = 5f)
                line(7f, 10.5f, 7f, 14.5f)
                line(5f, 12.5f, 9f, 12.5f)
                circle(15.5f, 11.5f, 1.1f, fill = true)
                circle(18f, 13.5f, 1.1f, fill = true)
            }

            "music" -> {
                circle(7.5f, 17.5f, 2.8f)
                circle(17.5f, 15.5f, 2.8f)
                line(10.3f, 17.5f, 10.3f, 5.5f)
                line(20.3f, 15.5f, 20.3f, 3.5f)
                line(10.3f, 5.5f, 20.3f, 3.5f)
            }

            "mic" -> {
                rect(9f, 2.5f, 6f, 11f, r = 3f)
                arc(5.5f, 5.5f, 13f, 12f, 0f, 180f)
                line(12f, 17.5f, 12f, 21f)
                line(8.5f, 21f, 15.5f, 21f)
            }

            "video" -> {
                rect(2.5f, 5f, 19f, 14f, r = 3f)
                poly(10f, 9f, 15.5f, 12f, 10f, 15f, close = true, fill = true)
            }

            "photo" -> {
                rect(2.5f, 4.5f, 19f, 15f, r = 2.5f)
                circle(8f, 9.5f, 1.8f)
                poly(3f, 18f, 9.5f, 12.5f, 13f, 15.5f, 16f, 12f, 21f, 17f)
            }

            "chat" -> {
                poly(4f, 4.5f, 20f, 4.5f, 20f, 15.5f, 11f, 15.5f, 6.5f, 19.5f, 6.5f, 15.5f, 4f, 15.5f, close = true)
                line(8f, 10f, 8.2f, 10f)
                line(12f, 10f, 12.2f, 10f)
                line(16f, 10f, 16.2f, 10f)
            }

            "globe" -> {
                circle(12f, 12f, 9f)
                scope.drawOval(color, p(8f, 3f), Size(8f * u, 18f * u), style = stroke)
                line(3f, 12f, 21f, 12f)
            }

            "map" -> {
                val path = Path()
                path.moveTo(12f * u, 21.5f * u)
                path.cubicTo(6f * u, 15f * u, 4.5f * u, 12f * u, 4.5f * u, 9.5f * u)
                path.cubicTo(4.5f * u, 5.3f * u, 7.8f * u, 2.5f * u, 12f * u, 2.5f * u)
                path.cubicTo(16.2f * u, 2.5f * u, 19.5f * u, 5.3f * u, 19.5f * u, 9.5f * u)
                path.cubicTo(19.5f * u, 12f * u, 18f * u, 15f * u, 12f * u, 21.5f * u)
                path.close()
                scope.drawPath(path, color, style = stroke)
                circle(12f, 9.5f, 2.5f)
            }

            "plane" -> {
                poly(12f, 2.5f, 13.5f, 4f, 13.5f, 9.5f, 21.5f, 14f, 21.5f, 16f, 13.5f, 13.5f, 13.5f, 18f, 16f, 20f, 16f, 21.5f, 12f, 20.5f, 8f, 21.5f, 8f, 20f, 10.5f, 18f, 10.5f, 13.5f, 2.5f, 16f, 2.5f, 14f, 10.5f, 9.5f, 10.5f, 4f, close = true)
            }

            "food" -> {
                line(7f, 3f, 7f, 21f)
                line(4.5f, 3f, 4.5f, 8f)
                line(9.5f, 3f, 9.5f, 8f)
                arc(4.5f, 4.5f, 5f, 6f, 0f, 180f)
                poly(17f, 21f, 17f, 3f, 14.5f, 6f, 14.5f, 13f, 17f, 13f)
            }

            "bag" -> {
                poly(4f, 8f, 20f, 8f, 19f, 21f, 5f, 21f, close = true)
                arc(8.5f, 3f, 7f, 9f, 180f, 180f)
            }

            "money" -> {
                circle(12f, 12f, 9f)
                poly(8.5f, 7f, 12f, 12f, 15.5f, 7f)
                line(12f, 12f, 12f, 17.5f)
                line(9f, 12.5f, 15f, 12.5f)
                line(9f, 15f, 15f, 15f)
            }

            "home" -> {
                poly(3f, 11.5f, 12f, 3.5f, 21f, 11.5f)
                poly(5.5f, 9.5f, 5.5f, 20.5f, 18.5f, 20.5f, 18.5f, 9.5f)
                rect(10f, 14f, 4f, 6.5f, r = 1f)
            }

            "book" -> {
                poly(12f, 6f, 12f, 20f)
                poly(12f, 6f, 9f, 4.5f, 2.5f, 4.5f, 2.5f, 18.5f, 9f, 18.5f, 12f, 20f, 15f, 18.5f, 21.5f, 18.5f, 21.5f, 4.5f, 15f, 4.5f, close = true)
            }

            "work" -> {
                rect(2.5f, 7f, 19f, 13f, r = 2.5f)
                rect(8.5f, 3.5f, 7f, 3.5f, r = 1.2f)
                line(2.5f, 12.5f, 21.5f, 12.5f)
            }

            "tool" -> {
                // スパナ: 柄と、先端の開いた輪
                line(5f, 19f, 13.5f, 10.5f)
                arc(12f, 3f, 9f, 9f, 135f, 270f)
                circle(5.5f, 18.5f, 1.2f, fill = true)
            }

            "news" -> {
                rect(3f, 4f, 15f, 16f, r = 2f)
                poly(18f, 8f, 21f, 8f, 21f, 18f, 18f, 20f)
                line(6.5f, 8.5f, 14.5f, 8.5f)
                line(6.5f, 12.5f, 14.5f, 12.5f)
                line(6.5f, 16f, 11.5f, 16f)
            }

            "access" -> {
                circle(12f, 4.5f, 1.8f, fill = true)
                line(4.5f, 8.5f, 19.5f, 8.5f)
                line(12f, 8.5f, 12f, 14f)
                poly(8f, 21f, 12f, 14f, 16f, 21f)
            }

            "star" -> {
                poly(12f, 2.8f, 14.7f, 9f, 21.3f, 9.4f, 16.3f, 13.8f, 17.8f, 20.4f, 12f, 17f, 6.2f, 20.4f, 7.7f, 13.8f, 2.7f, 9.4f, 9.3f, 9f, close = true)
            }

            "heart" -> {
                val path = Path()
                path.moveTo(12f * u, 20.5f * u)
                path.cubicTo(4f * u, 15f * u, 2.5f * u, 11.5f * u, 2.5f * u, 8.5f * u)
                path.cubicTo(2.5f * u, 5.5f * u, 4.8f * u, 3.5f * u, 7.5f * u, 3.5f * u)
                path.cubicTo(9.5f * u, 3.5f * u, 11f * u, 4.5f * u, 12f * u, 6.5f * u)
                path.cubicTo(13f * u, 4.5f * u, 14.5f * u, 3.5f * u, 16.5f * u, 3.5f * u)
                path.cubicTo(19.2f * u, 3.5f * u, 21.5f * u, 5.5f * u, 21.5f * u, 8.5f * u)
                path.cubicTo(21.5f * u, 11.5f * u, 20f * u, 15f * u, 12f * u, 20.5f * u)
                path.close()
                scope.drawPath(path, color, style = stroke)
            }

            "dots" -> {
                circle(5.5f, 12f, 1.8f, fill = true)
                circle(12f, 12f, 1.8f, fill = true)
                circle(18.5f, 12f, 1.8f, fill = true)
            }

            else -> {
                // DrawerFolders.FOLDER_SYMBOL と、知らない id
                poly(2.5f, 6f, 2.5f, 19f, 21.5f, 19f, 21.5f, 8f, 11.5f, 8f, 9.5f, 5f, 2.5f, 5f, close = true)
            }
        }
    }
}
