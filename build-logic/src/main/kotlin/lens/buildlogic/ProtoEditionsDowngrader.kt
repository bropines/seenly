package lens.buildlogic

/**
 * Rewrites a protobuf editions file into the pre-editions syntax with the same
 * wire behaviour, so that Wire - whose parser rejects `edition` outright
 * ("edition is not currently supported", square/wire#2546) - can read it.
 *
 * chrome-lens-rs does the same for prost on a protoc descriptor set. Wire reads
 * `.proto` source, not descriptors, so here it is a text rewrite:
 *
 * * A file with `option features.field_presence = IMPLICIT;` at column 0
 *   behaves like plain proto3 (a singular scalar has no presence and an empty
 *   value is not on the wire) and becomes `proto3` as written.
 * * A file that declares a CLOSED enum (`option features.enum_type = CLOSED;`,
 *   at any level) becomes `proto2`, the only syntax with closed enums. Editions
 *   forbids field labels, so each singular field outside a oneof gets
 *   `optional`, which is EXPLICIT presence. Edition 2023 packs repeated
 *   scalars and proto2 does not, so those get `[packed = true]` to keep the
 *   bytes Chromium's.
 * * Every other file has edition 2023's defaults - EXPLICIT presence, OPEN
 *   enums, packed repeated fields - which is exactly proto3 with `optional` on
 *   every singular field. That is what it becomes. (Mapping these to proto2,
 *   as chrome-lens-rs does for prost, would make their enums closed, and a
 *   proto3 message may not reference a closed enum.)
 * * `option features.*` lines are dropped once they have decided the above.
 *   `utf8_validation = NONE` has no proto3 spelling; Wire does not validate
 *   UTF-8 on decode either way.
 * * Per-field `features.field_presence` overrides are honoured exactly:
 *   EXPLICIT gets `optional` (`TextLayout.Word.text_separator` keeps telling
 *   "absent" from "empty", which prost loses), IMPLICIT in a proto3 file gets
 *   no label. IMPLICIT in a proto2 file would have no spelling and fails the
 *   build.
 */
object ProtoEditionsDowngrader {
    const val IMPLICIT_FILE_OPTION = "option features.field_presence = IMPLICIT;"

    /**
     * Per-field presence overrides whose handling has been decided. A new one
     * fails the build, and so does one that disappears, because then the
     * reasoning in [ProtoEditionsDowngrader]'s docs is stale.
     */
    val ACCEPTED_PER_FIELD_OVERRIDES = setOf(
        "lens_overlay_image_crop.proto:crop_id",
        "lens_overlay_text.proto:text_separator",
    )

    data class Result(val source: String, val syntax: String?, val overrides: List<String>)

    private val PACKABLE_SCALARS = setOf(
        "int32", "int64", "uint32", "uint64", "sint32", "sint64",
        "fixed32", "fixed64", "sfixed32", "sfixed64", "bool", "float", "double",
    )
    private val NOT_A_TYPE = setOf(
        "message", "enum", "oneof", "service", "extend", "reserved", "option",
        "extensions", "rpc", "import", "package", "syntax", "edition", "map",
    )
    private val EDITION = Regex("""edition\s*=\s*"\d+"\s*;""")
    private val FIELD = Regex(
        """(\s*)(?:(repeated|optional|required)\s+)?([A-Za-z_.][\w.]*)\s+([A-Za-z_]\w*)\s*=\s*(\d+)\s*(\[[^\]]*])?\s*;""",
    )
    private val PRESENCE = Regex("""features\.field_presence\s*=\s*(IMPLICIT|EXPLICIT)""")
    private val BLOCK = Regex("""^\s*(message|enum|oneof|service|extend)\b""")
    private val ENUM_DECL = Regex("""\benum\s+(\w+)\s*\{""")

    /** Simple names of every enum declared in [sources], for packing decisions. */
    fun enumNames(sources: Collection<String>): Set<String> =
        sources.flatMap { src -> ENUM_DECL.findAll(src).map { it.groupValues[1] } }.toSet()

    private val FIELD_TYPE = Regex("""^\s*(?:repeated\s+|optional\s+|required\s+)?([A-Za-z_.][\w.]*)\s+\w+\s*=\s*\d+""", RegexOption.MULTILINE)

    /**
     * Downgrades a whole set, keyed by file name.
     *
     * A proto3 message may not reference a closed (proto2) enum, while an
     * EXPLICIT editions message may. So an EXPLICIT file that uses an enum from
     * a proto2 file - vendored as proto2, or made proto2 here - becomes proto2
     * itself, which can close its own enums in turn; repeat until stable.
     * Enum names are matched by simple name, so a clash can push a file to
     * proto2 needlessly. That costs nothing on the wire: presence stays
     * EXPLICIT and repeated scalars stay packed.
     */
    fun downgradeAll(texts: Map<String, String>): Map<String, Result> {
        val enums = enumNames(texts.values)
        val forced = mutableSetOf<String>()
        while (true) {
            val results = texts.mapValues { (name, text) -> downgrade(name, text, enums, name in forced) }
            val closedEnums = results.filter { (name, result) ->
                result.syntax == Mode.PROTO2.label ||
                    (result.syntax == null && texts.getValue(name).contains(Regex("""^syntax\s*=\s*"proto2"""", RegexOption.MULTILINE)))
            }.keys.flatMap { enumNames(listOf(texts.getValue(it))) }.toSet()
            val newly = results.filter { (name, result) ->
                result.syntax == Mode.PROTO3_EXPLICIT.label &&
                    FIELD_TYPE.findAll(texts.getValue(name)).any { it.groupValues[1].substringAfterLast('.') in closedEnums }
            }.keys
            if (newly.isEmpty()) return results
            forced += newly
        }
    }

    fun downgrade(fileName: String, source: String, enums: Set<String>, forceProto2: Boolean = false): Result {
        val lines = source.lines()
        val editionLine = lines.indexOfFirst { EDITION.matches(it.trim()) }
        if (editionLine < 0) return Result(source, null, emptyList())

        val mode = when {
            // Column 0 only: the same option indented is scoped to a message.
            lines.any { it.trimEnd() == IMPLICIT_FILE_OPTION } -> Mode.PROTO3_IMPLICIT
            forceProto2 || lines.any { it.trim() == CLOSED_ENUM_OPTION } -> Mode.PROTO2
            else -> Mode.PROTO3_EXPLICIT
        }
        val overrides = mutableListOf<String>()
        val scopes = ArrayDeque<String>()

        val out = lines.mapIndexed { index, line ->
            val rewritten = when {
                index == editionLine -> "syntax = \"${mode.syntax}\";"
                // Blank rather than removed, so line numbers in errors still match the source.
                line.trimStart().startsWith("option features.") -> ""
                else -> rewriteField(fileName, line, scopes.lastOrNull(), mode, enums, overrides)
            }
            trackScopes(codeOf(line), scopes)
            rewritten
        }
        return Result(out.joinToString("\n"), mode.label, overrides)
    }

    private enum class Mode(val syntax: String, val label: String) {
        PROTO3_IMPLICIT("proto3", "proto3"),
        PROTO3_EXPLICIT("proto3", "proto3+optional"),
        PROTO2("proto2", "proto2"),
    }

    private const val CLOSED_ENUM_OPTION = "option features.enum_type = CLOSED;"

    private fun rewriteField(
        fileName: String,
        line: String,
        scope: String?,
        mode: Mode,
        enums: Set<String>,
        overrides: MutableList<String>,
    ): String {
        // Fields live directly in a message or an extend block. A oneof member
        // may not carry a label, and enum values only look like fields.
        if (scope != "message" && scope != "extend") return line
        val code = codeOf(line)
        val comment = line.substring(code.length)
        val match = FIELD.matchEntire(code.trimEnd()) ?: return line
        val (indent, labelIn, type, name, number, optionsIn) = match.destructured
        if (type in NOT_A_TYPE) return line

        var label = labelIn.ifEmpty { null }
        val options = optionsIn.removeSurrounding("[", "]")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

        // What the field's presence is once the file default and any override apply.
        var explicit = mode != Mode.PROTO3_IMPLICIT
        options.firstOrNull { PRESENCE.matches(it) }?.let { presence ->
            overrides += "$fileName:$name"
            options.remove(presence)
            explicit = PRESENCE.find(presence)!!.groupValues[1] == "EXPLICIT"
            check(explicit || mode != Mode.PROTO2) {
                "$fileName:$name is IMPLICIT inside a file that must be proto2, which cannot say that"
            }
        }
        if (label == null && explicit) label = "optional"
        if (mode == Mode.PROTO2) {
            val simpleType = type.substringAfterLast('.')
            val packable = simpleType in PACKABLE_SCALARS || simpleType in enums
            if (label == "repeated" && packable && options.none { it.startsWith("packed") }) {
                options += "packed = true"
            }
        }

        val labelText = label?.let { "$it " }.orEmpty()
        val optionText = if (options.isEmpty()) "" else " [${options.joinToString(", ")}]"
        val rebuilt = "$indent$labelText$type $name = $number$optionText;"
        return if (rebuilt == code.trimEnd()) line else rebuilt + comment
    }

    /** [line] without a trailing `//` comment, ignoring `//` inside string literals. */
    private fun codeOf(line: String): String {
        var quote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quote != null && c == '\\' -> i++
                quote != null && c == quote -> quote = null
                quote == null && (c == '"' || c == '\'') -> quote = c
                quote == null && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private fun trackScopes(code: String, scopes: ArrayDeque<String>) {
        var kind = BLOCK.find(code)?.groupValues?.get(1)
        for (c in code) {
            when (c) {
                '{' -> {
                    scopes.addLast(kind ?: "other")
                    kind = null
                }
                '}' -> scopes.removeLastOrNull()
            }
        }
    }
}
