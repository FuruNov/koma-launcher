package dev.furunov.komalauncher.platform.apps

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import dev.furunov.komalauncher.model.icons.IconOutline
import dev.furunov.komalauncher.model.icons.OutlineOp
import dev.furunov.komalauncher.model.settings.IconRim
import dev.furunov.komalauncher.model.settings.IconStyle
import dev.furunov.komalauncher.ui.icons.SHADOW_PAD

/**
 * アイコンを設定の形で切り抜き、縁を付けて描く。影はアイコンに焼き込まず、別の画像（[shadow]）にする。
 * 描くときに光の向きと反対へずらすため（ホームのコマでは端末の傾きで回る）。
 * 形と値は 2026-11-21 に実機で見比べた試作（spike/icon-shapes の IconShaper）に合わせてある。
 */
object IconRenderer {
    private const val INK = 0xFF141416.toInt()

    fun icon(
        drawable: Drawable,
        size: Int,
        style: IconStyle,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val outline = IconOutline.of(style.shape, needsClip = style.rim != IconRim.NONE || style.shadow)
        if (outline.isEmpty()) {
            // 端末の標準で縁も影も無ければ、今までどおり Android のアイコンをそのまま描く
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            return bitmap
        }
        val s = size.toFloat()
        val path = path(outline, s)
        canvas.save()
        canvas.clipPath(path)
        if (drawable is AdaptiveIconDrawable) {
            // adaptive icon の層は 108dp で、見える範囲は中央の 72dp。形の大きさに合わせて外側を切り捨てる
            val pad = (s * 0.25f).toInt()
            drawable.background?.apply { setBounds(-pad, -pad, size + pad, size + pad) }?.draw(canvas)
            drawable.foreground?.apply { setBounds(-pad, -pad, size + pad, size + pad) }?.draw(canvas)
        } else {
            // 2 層に分かれていない古い形式のアイコンは、白い台の上に、形の内側で縁に掛からない四角に収めて載せる
            canvas.drawColor(Color.WHITE)
            val box = IconOutline.contentBox(style.shape, style.rim)
            drawable.setBounds((box.l * s).toInt(), (box.t * s).toInt(), (box.r * s).toInt(), (box.b * s).toInt())
            drawable.draw(canvas)
        }
        // 縁は形の縁の内側に描く。線を 2 倍の太さで描き、形で切り抜いて内側の半分を残す
        when (style.rim) {
            IconRim.INK -> {
                // 試作と同じく、外側から白・黒の順に並べる（暗いコマの上でも黒い線が背景に溶けないように）
                canvas.drawPath(path, stroke(INK, s * IconOutline.rimWidth(IconRim.INK) * 2f))
                canvas.drawPath(path, stroke(Color.WHITE, s * IconOutline.INK_WHITE * 2f))
            }

            IconRim.STICKER -> canvas.drawPath(path, stroke(Color.WHITE, s * IconOutline.STICKER * 2f))

            IconRim.NONE -> Unit
        }
        canvas.restore()
        return bitmap
    }

    /**
     * 影の画像。形の黒いシルエットをぼかしたもので、ぼかしがはみ出す分の余白（一辺 × [SHADOW_PAD]）を四方に取る。
     * 影が無い設定なら null。
     */
    fun shadow(
        size: Int,
        style: IconStyle,
    ): Bitmap? {
        if (!style.shadow) return null
        val pad = (size * SHADOW_PAD).toInt()
        val full = size + pad * 2
        val bitmap = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888)
        val path = path(IconOutline.of(style.shape, needsClip = true), size.toFloat()).apply { offset(pad.toFloat(), pad.toFloat()) }
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                maskFilter = BlurMaskFilter(size * 0.04f, BlurMaskFilter.Blur.NORMAL)
            }
        Canvas(bitmap).drawPath(path, paint)
        return bitmap
    }

    private fun stroke(
        c: Int,
        w: Float,
    ) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c
        style = Paint.Style.STROKE
        strokeWidth = w
        strokeJoin = Paint.Join.ROUND
    }

    /** 0〜1 の輪郭を、一辺 [s] の画素の Path にする。 */
    private fun path(
        ops: List<OutlineOp>,
        s: Float,
    ): Path = Path().apply {
        ops.forEach { op ->
            when (op) {
                is OutlineOp.Move -> moveTo(op.x * s, op.y * s)
                is OutlineOp.Line -> lineTo(op.x * s, op.y * s)
                is OutlineOp.Cubic -> cubicTo(op.x1 * s, op.y1 * s, op.x2 * s, op.y2 * s, op.x * s, op.y * s)
                OutlineOp.Close -> close()
            }
        }
    }
}
