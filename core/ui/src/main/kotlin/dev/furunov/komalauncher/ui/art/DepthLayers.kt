package dev.furunov.komalauncher.ui.art

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

/**
 * 試作: 深度マップで絵を画素ごとにずらし、傾けると奥行きがあるように見せる層。
 * 層は PC で作り（深度の推定・人物の切り抜き・隠れた背景の塗りつぶし）、アプリの外部ファイルのディレクトリの depth3d/ に置く。
 * - <画像の id>.alpha.png: 人物の層の不透明度（灰色）
 * - <画像の id>.depths.png: 深度 3 つ（R 人物・G 背景・B 背景の後ろ。白いほど手前）。R は人物の範囲の外が 0
 * - <画像の id>.scene.jpg: 人物を塗りつぶした背景
 * - <画像の id>.back.jpg: 背景の段差の手前側を塗りつぶした、背景の後ろの層
 * メモリを減らすため、不透明度は 1 チャンネル（ALPHA_8）にし、ほかは縦横半分に縮めて GPU 側だけに置く。
 * 背景の層のうち元の絵と違うのは人物を塗りつぶした範囲（R が 0 でない所）だけなので、それ以外は元の絵を読む。
 * 塗りつぶした所と背景の後ろの層はぼやけた色なので、半分の解像度でも見た目は変わらない。
 * - config.json: {"strength": 絵の幅に対するずらす最大量, "focus": 動かない奥行き}
 * ファイルが無い画像は、今までどおり絵を一様にずらす。
 */
class DepthLayers(
    val alpha: Bitmap,
    val depths: Bitmap,
    val scene: Bitmap,
    val back: Bitmap,
    /** 人物の深度の平均。人物はこの深度の 1 枚の板として動かす。 */
    val personDepth: Float,
)

data class DepthLayerConfig(
    val strength: Float = 0.03f,
    val focus: Float = 0.5f,
)

/** depth3d/ の層を読む。[ids] は絵のある画像の id。 */
@Composable
fun rememberDepthLayers(
    directory: File?,
    ids: Set<String>,
    revision: Long,
): State<Pair<Map<String, DepthLayers>, DepthLayerConfig>> = produceState(emptyMap<String, DepthLayers>() to DepthLayerConfig(), directory, ids, revision) {
    if (directory == null) return@produceState
    value =
        withContext(Dispatchers.IO) {
            val layers = ids.mapNotNull { id -> loadLayers(directory, id)?.let { id to it } }.toMap()
            layers to loadConfig(File(directory, "config.json"))
        }
}

private fun loadConfig(file: File): DepthLayerConfig = try {
    if (!file.isFile) {
        DepthLayerConfig()
    } else {
        val o = JSONObject(file.readText())
        DepthLayerConfig(o.optDouble("strength", 0.03).toFloat(), o.optDouble("focus", 0.5).toFloat())
    }
} catch (e: Exception) {
    Log.w("DepthLayers", "$file を読めない", e)
    DepthLayerConfig()
}

private fun loadLayers(
    directory: File,
    id: String,
): DepthLayers? {
    val (alphaFile, depthsFile, sceneFile, backFile) = listOf("$id.alpha.png", "$id.depths.png", "$id.scene.jpg", "$id.back.jpg").map { File(directory, it) }
    if (!listOf(alphaFile, depthsFile, sceneFile, backFile).all { it.isFile }) return null
    return try {
        DepthLayers(
            alpha = decodeAlpha(alphaFile),
            depths = decodeHalf(depthsFile),
            scene = decodeHalf(sceneFile),
            back = decodeHalf(backFile),
            personDepth = meanPersonDepth(depthsFile),
        )
    } catch (e: Exception) {
        Log.w("DepthLayers", "$id の層を読めない", e)
        null
    }
}

/** 縦横半分に縮め、画素を GPU 側のメモリに置く。 */
private fun decodeHalf(file: File): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, info, _ ->
    d.allocator = ImageDecoder.ALLOCATOR_HARDWARE
    d.setTargetSize((info.size.width / 2).coerceAtLeast(1), (info.size.height / 2).coerceAtLeast(1))
}

/** 灰色の画像を、1 画素 1 バイトの不透明度（ALPHA_8）にする。GPU 側に転送できれば転送する。 */
private fun decodeAlpha(file: File): Bitmap {
    val soft = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
    val w = soft.width
    val h = soft.height
    val px = IntArray(w * h).also { soft.getPixels(it, 0, w, 0, 0, w, h) }
    soft.recycle()
    val a8 = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
    // ALPHA_8 の 1 行のバイト数は幅より大きいことがある
    val stride = a8.rowBytes
    val bytes = ByteArray(stride * h)
    for (y in 0 until h) for (x in 0 until w) bytes[y * stride + x] = (px[y * w + x] shr 16 and 0xFF).toByte()
    a8.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
    val hw = runCatching { a8.copy(Bitmap.Config.HARDWARE, false) }.getOrNull()
    return if (hw != null) {
        a8.recycle()
        hw
    } else {
        a8
    }
}

/** 人物の範囲（R が 0 でない所）の、人物の深度の平均。人物が無ければ 0.5。小さく縮めて数える。 */
private fun meanPersonDepth(file: File): Float {
    val small = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, info, _ ->
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        d.setTargetSize((info.size.width / 4).coerceAtLeast(1), (info.size.height / 4).coerceAtLeast(1))
    }
    val n = small.width * small.height
    val px = IntArray(n).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
    small.recycle()
    var sum = 0L
    var count = 0
    for (p in px) {
        val r = p shr 16 and 0xFF
        if (r > 8) {
            sum += r
            count++
        }
    }
    return if (count == 0) 0.5f else sum / count / 255f
}

/**
 * 層を描くシェーダー。モックアップ（人物と背景の 2 層）と同じ計算をする。
 * 手前から順に光線を進め、最初に当たった画素を取る（近いものが遠いものを隠す）。
 * 当たる直前の点が当たった面よりずっと奥なら段差の壁に当たったとみなし、背景の後ろの層を使う。
 * 人物の層は、人物の深度の平均にある 1 枚の板として一様にずらし、不透明度で背景の層の上に重ねる。
 * 板にするのは、人物の影・縁取り・輪郭の光・はみ出し（人物の形を一様にずらして描く効果）と位置を合わせるため。
 * 半透明の画素（毛先の間から背景が透けている所）は、元の絵の色に背景が混ざっている。塗りつぶした背景の色を背景の推定として差し引き、
 * 人物の色だけをずらした先の背景に重ねる。傾けていないときは元の絵と同じになる。
 * 背景の層のものが人物より手前なら重ねない。
 */
private const val SHADER = """
uniform shader img;
uniform shader alp;
uniform shader deps;
uniform shader scn;
uniform shader bck;
uniform float2 off;
uniform float focus;
uniform float pdepth;

const float N = 16.0;
const float STEP = 1.0 / 16.0;

float depthAt(float2 p, int ch) {
    half4 c = deps.eval(p);
    return ch == 0 ? float(c.r) : (ch == 1 ? float(c.g) : float(c.b));
}

// 戻り値: xy が当たった点、z が当たる直前の点の奥行きの差（壁の高さ）
float3 march(float2 p, int ch) {
    float h = 1.0;
    float2 prev = p - off * (h - focus);
    float prevDiff = depthAt(prev, ch) - h;
    float3 r = float3(prev, 0.0);
    for (float i = 1.0; i <= N; i += 1.0) {
        h = 1.0 - i * STEP;
        float2 q = p - off * (h - focus);
        float diff = depthAt(q, ch) - h;
        if (diff >= 0.0) {
            float k = clamp(prevDiff / (prevDiff - diff), 0.0, 1.0);
            return float3(mix(prev, q, k), -prevDiff);
        }
        prev = q;
        prevDiff = diff;
        r = float3(q, 0.0);
    }
    return r;
}

half4 main(float2 p) {
    float3 s = march(p, 1);
    // 人物を塗りつぶした範囲（R が 0 でない所）だけ背景の層を、それ以外は元の絵を読む
    float filled = smoothstep(0.004, 0.03, depthAt(s.xy, 0));
    half4 c = mix(img.eval(s.xy), scn.eval(s.xy), half(filled));
    float sd = depthAt(s.xy, 1);
    float wall = smoothstep(1.2 * STEP, 2.5 * STEP, s.z);
    if (wall > 0.0) {
        float3 b = march(p, 2);
        c = mix(c, bck.eval(b.xy), half(wall));
        sd = mix(sd, depthAt(b.xy, 2), wall);
    }
    float2 m = p - off * (pdepth - focus);
    half a = alp.eval(m).a;
    half4 fg = max(img.eval(m) - (1.0 - a) * scn.eval(m), half4(0.0));
    // 前後の判定は、その画素の人物の奥行き（R）で行う。人物全体の平均で比べると、手前にある足などが床の後ろに隠れる
    half front = half(step(sd - 0.02, depthAt(m, 0)));
    return mix(c, fg + (1.0 - a) * c, front * step(half(0.002), a));
}
"""

/** 人物が傾き 1 のときに動く量（画素）。人物の形を使う効果と、文字を人物の後ろに回す切り抜きを、この量だけ動かす。 */
fun depthPersonShift(
    layers: DepthLayers,
    config: DepthLayerConfig,
    artWidth: Float,
): Float = config.strength * artWidth * (layers.personDepth - config.focus)

/** 層を描く道具。コマの形か絵の位置が変わったときだけ作り直す。 */
class DepthArtPainter(
    art: Bitmap,
    layers: DepthLayers,
    artOffset: Offset,
    artWidth: Float,
    artHeight: Float,
    private val config: DepthLayerConfig,
) {
    private val shader = RuntimeShader(SHADER)
    private val brush = ShaderBrush(shader)

    /** 傾きが 1 のときにずらす最大量（画素）。 */
    val maxShift = config.strength * artWidth

    init {
        fun input(
            name: String,
            b: Bitmap,
        ) {
            val s =
                BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    filterMode = BitmapShader.FILTER_MODE_LINEAR
                    setLocalMatrix(
                        Matrix().apply {
                            setScale(artWidth / b.width, artHeight / b.height)
                            postTranslate(artOffset.x, artOffset.y)
                        },
                    )
                }
            shader.setInputShader(name, s)
        }
        input("img", art)
        input("alp", layers.alpha)
        input("deps", layers.depths)
        input("scn", layers.scene)
        input("bck", layers.back)
        shader.setFloatUniform("focus", config.focus)
        shader.setFloatUniform("pdepth", layers.personDepth)
    }

    /** [tilt] は傾き（-1〜1）。手前のものほど傾けた向きへ動く。 */
    fun DrawScope.draw(
        tilt: Offset,
        face: PanelFace,
        /** 塗る範囲。縮めた層に描くときは、縮める前の大きさを渡す。 */
        area: androidx.compose.ui.geometry.Size = size,
    ) {
        shader.setFloatUniform("off", tilt.x * maxShift, tilt.y * maxShift)
        drawRect(brush, size = area, colorFilter = face.toneFilter)
    }
}
