package dev.furunov.komalauncher.platform.layout

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.util.Log
import dev.furunov.komalauncher.model.layout.Layout
import dev.furunov.komalauncher.model.layout.LayoutShape
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * コマの画像を内部の files/art/ へ写す。
 *
 * - version 2 の画像は、長辺 [MAX_SIDE] 以下に縮めて <id>.webp に書く。コマは画面の一部なので、
 *   4096 の元画像を持っても表示には使わず、取り込みと読み込みの時間とメモリが増えるだけのため
 * - version 1 の絵は縮めずに、ファイル名のまま写す（画面全体に広げるので、縮めると粗くなる）
 */
class ArtImporter(
    val directory: File,
) {
    class Result(
        /** 画像の縦横比を書き込んだレイアウト。 */
        val layout: Layout,
        /** 取り込み口にも内部にも見つからなかった画像の id。 */
        val missing: List<String>,
        /** 取り込み口から写し終えた画像のファイル。取り込みが済んだら消す。 */
        val consumed: List<File>,
    )

    /** version 1 の絵はファイル名、version 2 の画像は <id>.webp。 */
    fun fileOf(
        layout: Layout,
        image: String,
    ): File = if (layout.shape is LayoutShape.Fixed) File(directory, image) else File(directory, "$image.webp")

    /** 画像の id → 内部のファイルの絶対パス。ファイルがある画像だけを返す。 */
    fun existing(layout: Layout): Map<String, String> = layout.images.mapNotNull { id -> fileOf(layout, id).takeIf { it.isFile }?.let { id to it.absolutePath } }.toMap()

    /** [layout] が参照する画像を、取り込み口の [source]（とその下の art/）から内部へ写す。IO のスレッドで呼ぶ。 */
    fun import(
        layout: Layout,
        source: File,
    ): Result {
        directory.mkdirs()
        val missing = ArrayList<String>()
        val consumed = ArrayList<File>()
        val aspects = HashMap<String, Float>()
        for (id in layout.images) {
            val target = fileOf(layout, id)
            val found = candidates(layout, id, source).firstOrNull { it.isFile }
            when {
                found != null && layout.shape is LayoutShape.Fixed -> {
                    copyAtomically(found, target)
                    consumed.add(found)
                }

                found != null -> {
                    val aspect = shrinkToWebp(found, target)
                    if (aspect != null) {
                        aspects[id] = aspect
                        consumed.add(found)
                    } else {
                        missing.add(id)
                    }
                }

                target.isFile -> aspectOf(target)?.let { aspects[id] = it }

                else -> missing.add(id)
            }
        }
        val withAspects = layout.mapPanels { p -> p.art?.let { a -> aspects[a.image]?.let { p.copy(art = a.copy(aspect = it)) } } ?: p }
        return Result(withAspects, missing, consumed)
    }

    private fun candidates(
        layout: Layout,
        id: String,
        source: File,
    ): List<File> = if (layout.shape is LayoutShape.Fixed) {
        // 一晩目の形式では、絵を layout.json と同じ場所に置いていた
        listOf(File(source, id), File(source, "art/$id"))
    } else {
        EXTENSIONS.map { File(source, "art/$id.$it") }
    }

    /** [from] を長辺 [MAX_SIDE] 以下に縮めて WebP で [to] に書く。元の画像の縦横比を返す。読めなければ null。 */
    private fun shrinkToWebp(
        from: File,
        to: File,
    ): Float? = try {
        var aspect = 1f
        val bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(from)) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                // 幅か高さが 0 の壊れた画像は、縦横比を求められないので取り込まない
                require(w > 0 && h > 0) { "大きさが ${w}x$h の画像" }
                aspect = w.toFloat() / h
                val scale = MAX_SIDE.toFloat() / max(w, h)
                if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt(), (h * scale).roundToInt())
                // 縮めて書き出すので、GPU 側ではなく CPU 側に読む
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        val tmp = File(to.path + ".tmp")
        try {
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, it) }
        } finally {
            bitmap.recycle()
        }
        if (!tmp.renameTo(to)) error("$to に書けない")
        aspect
    } catch (e: Exception) {
        Log.w(TAG, "$from を取り込めない", e)
        null
    }

    private fun copyAtomically(
        from: File,
        to: File,
    ) {
        val tmp = File(to.path + ".tmp")
        from.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(to)) error("$to に書けない")
    }

    private fun aspectOf(file: File): Float? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else null
    }

    private companion object {
        const val TAG = "ArtImporter"
        const val MAX_SIDE = 2048
        const val QUALITY = 90
        val EXTENSIONS = listOf("webp", "jpg", "jpeg", "png")
    }
}
