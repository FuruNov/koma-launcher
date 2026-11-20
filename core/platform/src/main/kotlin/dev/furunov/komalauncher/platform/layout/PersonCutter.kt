package dev.furunov.komalauncher.platform.layout

import android.graphics.ImageDecoder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import dev.furunov.komalauncher.model.layout.MaskMath
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 端末の中で人物を切り抜く（ML Kit の被写体の切り抜き）。モデルは Google Play 開発者サービスが初回に取得する。
 * 結果は「人物らしさ」の濃さなので、PC で作った切り抜きと同じく 0.35〜0.65 を詰めて反転し、[ArtImporter.saveMask] で書く。
 */
class PersonCutter(
    private val art: ArtImporter,
) {
    /** 失敗したら理由を返す。IO のスレッドで呼ぶ。 */
    suspend fun cutOut(image: String): String? {
        val file = File(art.directory, "$image.webp")
        if (!file.isFile) return "画像が見つかりません"
        val bitmap =
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val scale = ArtImporter.MASK_SIDE.toFloat() / max(info.size.width, info.size.height)
                    if (scale < 1f) decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (e: Exception) {
                return "画像を読み込めません: ${e.message}"
            }
        // Google Play 開発者サービスが無い端末では、作る時点で例外になることがある
        val segmenter =
            try {
                SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
            } catch (e: Exception) {
                bitmap.recycle()
                return "この端末では切り抜きを実行できません（${e.message ?: e::class.simpleName}）。PC で作成した切り抜き画像をインポートしてください"
            }
        try {
            val result =
                suspendCancellableCoroutine { cont ->
                    segmenter
                        .process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { cont.resume(Result.success(it)) }
                        .addOnFailureListener { cont.resume(Result.failure(it)) }
                }.getOrElse { return "切り抜きに失敗しました（${it.message ?: it::class.simpleName}）。Google Play 開発者サービスがモデルをダウンロード中の可能性があります。しばらくしてから再試行してください" }
            val buffer = result.foregroundConfidenceMask ?: return "切り抜きの結果を取得できませんでした"
            val w = bitmap.width
            val h = bitmap.height
            val person = FloatArray(w * h)
            buffer.rewind()
            buffer.get(person)
            art.saveMask(image, MaskMath.keepFromPerson(person), w, h)
            return null
        } finally {
            segmenter.close()
            bitmap.recycle()
        }
    }
}
