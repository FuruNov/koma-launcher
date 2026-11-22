package dev.furunov.komalauncher.model.layout

import dev.furunov.komalauncher.model.geometry.Point
import dev.furunov.komalauncher.model.geometry.Polygon
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlin.math.abs

sealed interface LayoutResult {
    data class Ok(
        val layout: Layout,
    ) : LayoutResult

    data class Error(
        val message: String,
    ) : LayoutResult
}

/**
 * layout.json の読み書き。JSON の形（DTO）とアプリ内のモデルを分けておき、
 * 形式を変えるときは version を見てここで読み替える。
 *
 * - version 1: 1 枚の絵と、絵に対する相対座標の多角形（`image`・`aspect`・`panels`）
 * - version 2: 線の木とコマごとの画像（`frame`・`tree`）。木の形はモックアップの state と同じ
 */
object LayoutParser {
    const val CURRENT_VERSION = 2

    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    private val pretty =
        Json {
            prettyPrint = true
            explicitNulls = false
        }

    fun parse(text: String): LayoutResult = try {
        val dto = json.decodeFromString(LayoutDto.serializer(), text)
        validate(dto)?.let { LayoutResult.Error(it) } ?: LayoutResult.Ok(dto.toModel())
    } catch (e: Exception) {
        // kotlinx.serialization の例外の文言は長いので、最初の行だけを見せる
        LayoutResult.Error("layout.json を読めません: ${e.message?.lineSequence()?.firstOrNull() ?: e::class.simpleName}")
    } catch (e: StackOverflowError) {
        // 入れ子が極端に深い JSON は、読む途中で止まる。アプリを止めずに誤りとして扱う
        LayoutResult.Error("layout.json の入れ子が深すぎます")
    }

    /** 内部の layout.json に書く形。version 1 のレイアウトは version 1 の形で書く（多角形を木に直せないため）。 */
    fun encode(layout: Layout): String = pretty.encodeToString(LayoutDto.serializer(), LayoutDto.of(layout))

    /**
     * JsonFileStore で内部の layout.json を保存するための serializer。読むときは [parse] と同じ検査をし、
     * 誤りがあれば例外にする（JsonFileStore が壊れたファイルとして退避する）。
     */
    val serializer: KSerializer<Layout> =
        object : KSerializer<Layout> {
            override val descriptor: SerialDescriptor = LayoutDto.serializer().descriptor

            override fun serialize(
                encoder: Encoder,
                value: Layout,
            ) = encoder.encodeSerializableValue(LayoutDto.serializer(), LayoutDto.of(value))

            override fun deserialize(decoder: Decoder): Layout {
                val dto = decoder.decodeSerializableValue(LayoutDto.serializer())
                validate(dto)?.let { throw SerializationException(it) }
                return dto.toModel()
            }
        }

    private fun validate(dto: LayoutDto): String? {
        if (dto.version > CURRENT_VERSION) return "layout.json の version ${dto.version} にはまだ対応していません"
        return if (dto.version >= 2) validateTree(dto) else validateFixed(dto)
    }

    private fun validateFixed(dto: LayoutDto): String? {
        val panels = dto.panels.orEmpty()
        if (panels.isEmpty()) return "コマが 1 つもありません"
        val aspect = dto.aspect ?: return "aspect を書いてください"
        if (!aspect.isFinite() || aspect <= 0f) return "aspect は正の数にしてください"
        // 絵は files/art/ の下にこの名前で写すので、別の場所を指せないようにする
        if (dto.image != null && !FILE_NAME.matches(dto.image)) return "image にはファイル名だけを書いてください: ${dto.image}"
        duplicate(panels.map { it.id })?.let { return it }
        // version 1 では描かないが、art.image も version 2 と同じく、別の場所を指せないようにしておく
        panels.forEach { p -> p.art?.let { if (!IMAGE_ID.matches(it.image)) return "コマ ${p.id} の art.image には英数字と - _ だけを使ってください" } }
        panels.forEach { p ->
            val polygon = p.polygon ?: return "コマ ${p.id} の polygon がありません"
            if (polygon.size < 3) return "コマ ${p.id} の頂点が 3 つ未満です"
            if (polygon.any { it.size != 2 }) return "コマ ${p.id} の頂点は [x, y] の形で書いてください"
        }
        return null
    }

    private fun validateTree(dto: LayoutDto): String? {
        val tree = dto.tree ?: return "tree を書いてください"
        val leaves = ArrayList<PanelDto>()

        fun walk(
            node: NodeDto,
            path: String,
        ): String? {
            val where = if (path.isEmpty()) "tree" else "tree の $path"
            if (path.length > MAX_DEPTH) return "tree の入れ子が深すぎます（$MAX_DEPTH 段まで）"
            if (node.panel != null) {
                if (node.cut != null || node.a != null || node.b != null) return "$where に panel と cut の両方があります"
                leaves.add(node.panel)
                return null
            }
            if (node.cut == null || node.a == null || node.b == null) return "$where には panel か、cut・a・b の組を書いてください"
            if (!node.cut.ta.isFinite() || !node.cut.tb.isFinite()) return "$where の cut の値が数ではありません"
            return walk(node.a, path + "a") ?: walk(node.b, path + "b")
        }
        walk(tree, "")?.let { return it }
        duplicate(leaves.map { it.id })?.let { return it }
        leaves.forEach { p ->
            p.art?.let { a ->
                if (a.image.isBlank()) return "コマ ${p.id} の art.image が空です"
                if (a.aspect != null && !(a.aspect.isFinite() && a.aspect in ASPECT_RANGE)) return "コマ ${p.id} の art.aspect は $ASPECT_RANGE の数にしてください"
                // 大きすぎる値は描画の大きさを桁外れにするので、読み込みの時点で止める。範囲内の値は表示のたびに収め直す
                if (!(a.zoom.isFinite() && a.zoom > 0f && a.zoom <= MAX_ART_VALUE)) return "コマ ${p.id} の art.zoom は 0 より大きく $MAX_ART_VALUE 以下にしてください"
                if (!(a.dx.isFinite() && a.dy.isFinite() && abs(a.dx) <= MAX_ART_VALUE && abs(a.dy) <= MAX_ART_VALUE)) return "コマ ${p.id} の art.dx・dy は -$MAX_ART_VALUE〜$MAX_ART_VALUE にしてください"
                // 画像の id は files/art/ の下のファイル名になるので、別の場所を指せないようにする
                if (!IMAGE_ID.matches(a.image)) return "コマ ${p.id} の art.image には英数字と - _ だけを使ってください"
            }
        }
        val frame = dto.frame ?: FrameDto()
        if (!frame.refWidth.isFinite() || frame.refWidth <= 0f) return "frame.refWidth は正の数にしてください"
        // 長さは画面の幅の 4 分の 1 まで。それより大きいと、コマが細くなって描けなくなる
        val maxLength = frame.refWidth / 4f
        if (listOf(frame.margin, frame.gutter, frame.border.width).any { !it.isFinite() || it < 0f || it > maxLength }) {
            return "frame の margin・gutter・border.width は 0〜${maxLength.toInt()} にしてください"
        }
        listOfNotNull(frame.gutterColor, frame.border.color).forEach { if (parseColor(it) == null) return "色 $it は #rrggbb の形で書いてください" }
        dto.look?.let { l ->
            if (listOf(l.strength, l.inner, l.sheen).any { !it.isFinite() || it !in 0f..1f }) return "look の strength・inner・sheen は 0〜1 にしてください"
        }
        dto.text?.let { t ->
            if (!t.mastheadY.isFinite() || t.mastheadY !in 0f..1f) return "text.mastheadY は 0〜1 にしてください"
            if (t.masthead.length > 40) return "text.masthead は 40 文字までにしてください"
        }
        dto.fx?.let { if (!it.strength.isFinite() || it.strength !in 0f..3f) return "fx.strength は 0〜3 にしてください" }
        dto.depth?.let { d ->
            if (listOf(d.shadow.alpha, d.rim.alpha, d.blur.amount, d.blur.haze, d.outline.alpha).any { !it.isFinite() || it !in 0f..1f }) {
                return "depth の alpha・amount・haze は 0〜1 にしてください"
            }
            if (listOf(d.shadow.distance, d.shadow.blur, d.rim.width, d.outline.width, d.pop.width).any { !it.isFinite() || it < 0f || it > maxLength }) {
                return "depth の distance・blur・width は 0〜${maxLength.toInt()} にしてください"
            }
        }
        dto.clock?.let { c ->
            if (listOf(c.x, c.y).any { !it.isFinite() || it !in 0f..1f }) return "clock.x・y は 0〜1 にしてください"
            if (!c.size.isFinite() || c.size !in 0.2f..3f) return "clock.size は 0.2〜3 にしてください"
        }
        dto.widgets?.let { ws ->
            ws.groupBy { it.id }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { return "ウィジェットの id が重複しています: $it" }
            ws.forEach { w ->
                if (!IMAGE_ID.matches(w.id)) return "widgets.id は英数字・_・- の 64 文字以内にしてください"
                if (!PROVIDER.matches(w.provider)) return "widgets.provider は「パッケージ名/クラス名」にしてください"
                if (listOf(w.x, w.y).any { !it.isFinite() || it !in 0f..1f }) return "widgets.x・y は 0〜1 にしてください"
                if (listOf(w.w, w.h).any { !it.isFinite() || it !in 0.02f..1f }) return "widgets.w・h は 0.02〜1 にしてください"
            }
        }
        return null
    }

    private fun duplicate(ids: List<String>): String? {
        val dup = ids.groupBy { it }.filterValues { it.size > 1 }.keys
        return if (dup.isEmpty()) null else "コマの id が重複しています: ${dup.joinToString()}"
    }

    private val ASPECT_RANGE = 0.05f..20f
    private const val MAX_ART_VALUE = 10f

    /** 木の深さの上限。画面を 2^32 に割ることは無いので、これを超える木は壊れていると見なす。 */
    private const val MAX_DEPTH = 32
    private val IMAGE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val PROVIDER = Regex("[A-Za-z0-9_.]{1,256}/[A-Za-z0-9_.$]{1,256}")
    private val FILE_NAME = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}")

    private fun parseColor(s: String): Long? {
        if (!Regex("#[0-9a-fA-F]{6}").matches(s)) return null
        return 0xFF000000 or s.substring(1).toLong(16)
    }

    private fun formatColor(c: Long): String = "#%06x".format(c and 0xFFFFFF)

    @Serializable
    private data class LayoutDto(
        val version: Int = 1,
        // version 1
        val image: String? = null,
        val aspect: Float? = null,
        val panels: List<PanelDto>? = null,
        // version 2
        val frame: FrameDto? = null,
        val tree: NodeDto? = null,
        val look: LookDto? = null,
        val text: TextDto? = null,
        val clock: ClockDto? = null,
        val fx: FxDto? = null,
        val depth: DepthDto? = null,
        val widgets: List<WidgetDto>? = null,
    ) {
        fun toModel(): Layout = if (version >= 2) {
            Layout(LayoutShape.Tree(requireNotNull(tree).toModel(), (frame ?: FrameDto()).toModel(), (look ?: LookDto()).toModel(), (text ?: TextDto()).toModel(), (clock ?: ClockDto()).toModel(), (fx ?: FxDto()).toModel(), (depth ?: DepthDto()).toModel(), widgets.orEmpty().map { it.toModel() }))
        } else {
            Layout(
                LayoutShape.Fixed(
                    image = image,
                    aspect = requireNotNull(aspect),
                    panels = panels.orEmpty().map { FixedPanel(it.toModel(), Polygon(requireNotNull(it.polygon).map { p -> Point(p[0], p[1]) })) },
                ),
            )
        }

        companion object {
            fun of(layout: Layout): LayoutDto = when (val s = layout.shape) {
                is LayoutShape.Tree -> LayoutDto(version = 2, frame = FrameDto.of(s.frame), tree = NodeDto.of(s.root), look = LookDto.of(s.look), text = TextDto.of(s.text), clock = ClockDto.of(s.clock), fx = FxDto(s.fx.parallax, s.fx.strength), depth = DepthDto.of(s.depth).takeIf { it != DepthDto() }, widgets = s.widgets.map(WidgetDto::of).ifEmpty { null })

                is LayoutShape.Fixed ->
                    LayoutDto(
                        version = 1,
                        image = s.image,
                        aspect = s.aspect,
                        panels = s.panels.map { PanelDto.of(it.panel).copy(polygon = it.polygon.points.map { p -> listOf(p.x, p.y) }) },
                    )
            }
        }
    }

    @Serializable
    private data class TextDto(
        val label: LabelDto = LabelDto.NONE,
        val lang: String = "en",
        val masthead: String = "",
        val mastheadY: Float = 0.09f,
        val mastheadPanel: String? = null,
        val icon: Boolean = true,
        val mastheadScale: Float = 1f,
    ) {
        fun toModel() = TextSpec(
            label =
            when (label) {
                LabelDto.NONE -> LabelMode.NONE
                LabelDto.VERTICAL -> LabelMode.VERTICAL
                LabelDto.HORIZONTAL -> LabelMode.HORIZONTAL
            },
            english = lang != "ja",
            masthead = masthead,
            mastheadY = mastheadY,
            mastheadPanel = mastheadPanel?.let(::PanelId),
            mastheadScale = mastheadScale.coerceIn(0.5f, 2f),
            icon = icon,
        )

        companion object {
            fun of(t: TextSpec) = TextDto(
                label =
                when (t.label) {
                    LabelMode.NONE -> LabelDto.NONE
                    LabelMode.VERTICAL -> LabelDto.VERTICAL
                    LabelMode.HORIZONTAL -> LabelDto.HORIZONTAL
                },
                lang = if (t.english) "en" else "ja",
                masthead = t.masthead,
                mastheadY = t.mastheadY,
                mastheadScale = t.mastheadScale,
                icon = t.icon,
                mastheadPanel = t.mastheadPanel?.value,
            )
        }
    }

    @Serializable
    private enum class LayerDto(
        val model: LayerKind,
    ) {
        @SerialName("icons")
        ICONS(LayerKind.ICONS),

        @SerialName("label")
        LABEL(LayerKind.LABEL),

        @SerialName("masthead")
        MASTHEAD(LayerKind.MASTHEAD),

        @SerialName("clock")
        CLOCK(LayerKind.CLOCK),

        @SerialName("person")
        PERSON(LayerKind.PERSON),
        ;

        companion object {
            fun of(l: LayerKind) = entries.first { it.model == l }
        }
    }

    @Serializable
    private enum class LabelDto {
        @SerialName("none")
        NONE,

        @SerialName("vertical")
        VERTICAL,

        @SerialName("horizontal")
        HORIZONTAL,
    }

    @Serializable
    private data class FxDto(
        val parallax: Boolean = false,
        val strength: Float = 0.4f,
    ) {
        fun toModel() = FxSpec(parallax, strength)
    }

    /** 前景の人物の立体感。省いた項目は既定の値（どの効果も切ってある）。 */
    @Serializable
    private data class DepthDto(
        val shadow: ShadowDto = ShadowDto(),
        val rim: RimDto = RimDto(),
        val blur: BlurDto = BlurDto(),
        val outline: OutlineDto = OutlineDto(),
        val pop: PopDto = PopDto(),
    ) {
        fun toModel() = DepthSpec(
            ShadowFx(shadow.on, shadow.alpha, shadow.distance, shadow.blur),
            RimFx(rim.on, rim.alpha, rim.width),
            BackBlurFx(blur.on, blur.amount, blur.haze),
            OutlineFx(outline.on, outline.width, outline.alpha),
            PopFx(pop.width),
        )

        companion object {
            fun of(d: DepthSpec) = DepthDto(
                ShadowDto(d.shadow.enabled, d.shadow.alpha, d.shadow.distance, d.shadow.blur),
                RimDto(d.rim.enabled, d.rim.alpha, d.rim.width),
                BlurDto(d.blur.enabled, d.blur.amount, d.blur.haze),
                OutlineDto(d.outline.enabled, d.outline.width, d.outline.alpha),
                PopDto(d.pop.width),
            )
        }
    }

    @Serializable
    private data class ShadowDto(
        val on: Boolean = false,
        val alpha: Float = 0.55f,
        val distance: Float = 22f,
        val blur: Float = 10f,
    )

    @Serializable
    private data class RimDto(
        val on: Boolean = false,
        val alpha: Float = 0.7f,
        val width: Float = 4f,
    )

    @Serializable
    private data class BlurDto(
        val on: Boolean = false,
        val amount: Float = 0.6f,
        val haze: Float = 0.12f,
    )

    @Serializable
    private data class OutlineDto(
        val on: Boolean = false,
        val width: Float = 4f,
        val alpha: Float = 0.9f,
    )

    @Serializable
    private data class PopDto(
        val width: Float = 40f,
    )

    @Serializable
    private data class WidgetDto(
        val id: String,
        val provider: String,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val front: Boolean = false,
    ) {
        fun toModel() = WidgetSpec(id, provider, x, y, w, h, front)

        companion object {
            fun of(w: WidgetSpec) = WidgetDto(w.id, w.provider, w.x, w.y, w.w, w.h, w.front)
        }
    }

    @Serializable
    private data class ClockDto(
        val enabled: Boolean = false,
        val panel: String? = null,
        val x: Float = 0.5f,
        val y: Float = 0.83f,
        val size: Float = 0.78f,
    ) {
        fun toModel() = ClockSpec(enabled, panel?.let(::PanelId), x, y, size)

        companion object {
            fun of(c: ClockSpec) = ClockDto(c.enabled, c.panel?.value, c.x, c.y, c.size)
        }
    }

    @Serializable
    private data class LookDto(
        val tone: ToneDto = ToneDto.NONE,
        val strength: Float = 1f,
        val inner: Float = 0f,
        val sheen: Float = 0f,
    ) {
        fun toModel() = Look(if (tone == ToneDto.SOFT) Tone.SOFT else Tone.NONE, strength, inner, sheen)

        companion object {
            fun of(l: Look) = LookDto(if (l.tone == Tone.SOFT) ToneDto.SOFT else ToneDto.NONE, l.strength, l.inner, l.sheen)
        }
    }

    @Serializable
    private enum class ToneDto {
        @SerialName("none")
        NONE,

        @SerialName("soft")
        SOFT,
    }

    @Serializable
    private data class FrameDto(
        val refWidth: Float = 1220f,
        val margin: Float = 24f,
        val gutter: Float = 26f,
        val gutterColor: String? = null,
        val bleed: Boolean = false,
        val border: BorderDto = BorderDto(),
    ) {
        fun toModel() = Frame(refWidth, margin, gutter, gutterColor?.let(::parseColor), bleed, border.toModel())

        companion object {
            fun of(f: Frame) = FrameDto(f.refWidth, f.margin, f.gutter, f.gutterColor?.let(::formatColor), f.bleed, BorderDto.of(f.border))
        }
    }

    @Serializable
    private data class BorderDto(
        val style: Style = Style.SINGLE,
        val width: Float = 10f,
        /** 省略すると自動（すき間の色に合わせて白か黒）。 */
        val color: String? = null,
    ) {
        fun toModel() = Border(
            style =
            when (style) {
                Style.NONE -> BorderStyle.NONE
                Style.SINGLE -> BorderStyle.SINGLE
                Style.DOUBLE -> BorderStyle.DOUBLE
            },
            width = width,
            color = color?.let { requireNotNull(parseColor(it)) },
        )

        companion object {
            fun of(b: Border) = BorderDto(
                style =
                when (b.style) {
                    BorderStyle.NONE -> Style.NONE
                    BorderStyle.SINGLE -> Style.SINGLE
                    BorderStyle.DOUBLE -> Style.DOUBLE
                },
                width = b.width,
                color = b.color?.let(::formatColor),
            )
        }
    }

    @Serializable
    private enum class Style {
        @SerialName("none")
        NONE,

        @SerialName("single")
        SINGLE,

        @SerialName("double")
        DOUBLE,
    }

    /** 葉なら [panel]、枝なら [cut]・[a]・[b]。モックアップの state の木と同じ形。 */
    @Serializable
    private data class NodeDto(
        val panel: PanelDto? = null,
        val cut: CutDto? = null,
        val a: NodeDto? = null,
        val b: NodeDto? = null,
    ) {
        fun toModel(): LayoutNode = if (panel != null) {
            LayoutNode.Leaf(panel.toModel())
        } else {
            LayoutNode.Split(requireNotNull(cut).ta, cut.tb, requireNotNull(a).toModel(), requireNotNull(b).toModel())
        }

        companion object {
            fun of(n: LayoutNode): NodeDto = when (n) {
                is LayoutNode.Leaf -> NodeDto(panel = PanelDto.of(n.panel))
                is LayoutNode.Split -> NodeDto(cut = CutDto(n.ta, n.tb), a = of(n.a), b = of(n.b))
            }
        }
    }

    @Serializable
    private data class CutDto(
        val ta: Float,
        val tb: Float,
    )

    @Serializable
    private data class ArtDto(
        val image: String,
        val aspect: Float? = null,
        val zoom: Float = 1f,
        val dx: Float = 0f,
        val dy: Float = 0f,
        val dim: Float = 0f,
    )

    @Serializable
    private data class PanelDto(
        val id: String,
        val kind: Kind,
        val label: String? = null,
        val labelEn: String? = null,
        /** アイコンのまとまりの中心 [x, y]（コマの外接長方形に対する割合）。省くと自動。 */
        val iconAt: List<Float>? = null,
        val labelScale: Float = 1f,
        val iconScale: Float = 1f,
        /** true なら人物を枠の外へはみ出させる（depth.pop）。 */
        val pop: Boolean = false,
        /** 重ね順（先頭が背景の絵のすぐ上）。"icons"・"label"・"masthead"・"clock"・"person"。省くと既定の順。 */
        val order: List<LayerDto>? = null,
        val apps: List<String>? = null,
        val art: ArtDto? = null,
        // version 1 だけ
        val polygon: List<List<Float>>? = null,
    ) {
        fun toModel() = Panel(
            id = PanelId(id),
            content =
            when (kind) {
                Kind.FOLDER -> PanelContent.Folder(apps.orEmpty())

                // ウィジェットのコマは廃止した（ウィジェットはコマから独立して置く）。古い layout.json ではフォルダとして読む
                Kind.WIDGET -> PanelContent.Folder(apps.orEmpty())

                Kind.DRAWER -> PanelContent.Drawer
            },
            label = label,
            labelEn = labelEn,
            iconAt = iconAt?.takeIf { it.size == 2 }?.let { Point(it[0].coerceIn(0f, 1f), it[1].coerceIn(0f, 1f)) },
            labelScale = labelScale.coerceIn(0.5f, 2f),
            iconScale = iconScale.coerceIn(0.5f, 2f),
            pop = pop,
            order = order?.map { it.model },
            art = art?.let { PanelArt(it.image, it.aspect, it.zoom, it.dx, it.dy, it.dim.coerceIn(0f, 1f)) },
        )

        companion object {
            fun of(p: Panel) = PanelDto(
                id = p.id.value,
                kind =
                when (p.content) {
                    is PanelContent.Folder -> Kind.FOLDER
                    PanelContent.Drawer -> Kind.DRAWER
                },
                label = p.label,
                labelEn = p.labelEn,
                iconAt = p.iconAt?.let { listOf(it.x, it.y) },
                labelScale = p.labelScale,
                iconScale = p.iconScale,
                pop = p.pop,
                order = p.order?.map { LayerDto.of(it) },
                apps = (p.content as? PanelContent.Folder)?.initialApps?.takeIf { it.isNotEmpty() },
                art = p.art?.let { ArtDto(it.image, it.aspect, it.zoom, it.dx, it.dy, it.dim) },
            )
        }
    }

    @Serializable
    private enum class Kind {
        @SerialName("folder")
        FOLDER,

        @SerialName("widget")
        WIDGET,

        @SerialName("drawer")
        DRAWER,
    }
}
