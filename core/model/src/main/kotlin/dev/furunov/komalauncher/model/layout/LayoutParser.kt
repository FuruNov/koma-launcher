package dev.furunov.komalauncher.model.layout

import dev.furunov.komalauncher.model.geometry.Point
import dev.furunov.komalauncher.model.geometry.Polygon
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

sealed interface LayoutResult {
    data class Ok(
        val layout: Layout,
    ) : LayoutResult

    data class Error(
        val message: String,
    ) : LayoutResult
}

/**
 * layout.json の読み込み。JSON の形（DTO）とアプリ内のモデルを分けておき、
 * 形式を変えるときは version を見てここで読み替える。
 */
object LayoutParser {
    const val CURRENT_VERSION = 1

    private val json =
        Json {
            ignoreUnknownKeys = true
            classDiscriminator = "kind"
        }

    fun parse(text: String): LayoutResult = try {
        val dto = json.decodeFromString(LayoutDto.serializer(), text)
        validate(dto)?.let { LayoutResult.Error(it) } ?: LayoutResult.Ok(dto.toModel())
    } catch (e: Exception) {
        // kotlinx.serialization の例外の文言は長いので、最初の行だけを見せる
        LayoutResult.Error("layout.json を読めません: ${e.message?.lineSequence()?.firstOrNull() ?: e::class.simpleName}")
    }

    private fun validate(dto: LayoutDto): String? {
        if (dto.version > CURRENT_VERSION) return "layout.json の version ${dto.version} にはまだ対応していません"
        if (dto.panels.isEmpty()) return "コマが 1 つもありません"
        if (dto.aspect <= 0f) return "aspect は正の数にしてください"
        val dup = dto.panels.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (dup.isNotEmpty()) return "コマの id が重複しています: ${dup.joinToString()}"
        dto.panels.forEach { p ->
            if (p.polygon.size < 3) return "コマ ${p.id} の頂点が 3 つ未満です"
            if (p.polygon.any { it.size != 2 }) return "コマ ${p.id} の頂点は [x, y] の形で書いてください"
        }
        return null
    }

    @Serializable
    private data class LayoutDto(
        val version: Int = CURRENT_VERSION,
        val image: String? = null,
        val aspect: Float,
        val panels: List<PanelDto>,
    ) {
        fun toModel() = Layout(version, image, aspect, panels.map { it.toModel() })
    }

    @Serializable
    private data class PanelDto(
        val id: String,
        val kind: Kind,
        val polygon: List<List<Float>>,
        val label: String? = null,
        val apps: List<String> = emptyList(),
    ) {
        fun toModel() = Panel(
            id = PanelId(id),
            polygon = Polygon(polygon.map { Point(it[0], it[1]) }),
            content =
            when (kind) {
                Kind.FOLDER -> PanelContent.Folder(apps)
                Kind.WIDGET -> PanelContent.Widget
            },
            label = label,
        )
    }

    @Serializable
    private enum class Kind {
        @SerialName("folder")
        FOLDER,

        @SerialName("widget")
        WIDGET,
    }
}
