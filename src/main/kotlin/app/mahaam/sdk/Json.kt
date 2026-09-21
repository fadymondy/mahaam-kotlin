package app.mahaam.sdk

/**
 * A minimal JSON writer for exactly the shapes an envelope contains.
 *
 * Hand-rolled on purpose. This library has no dependencies, so it cannot become the reason an
 * app's dependency graph conflicts — and an error reporter that fails to build is worth nothing.
 * The envelope is a flat object of strings, a string map and a free-form `extra`, which is little
 * enough to encode correctly and test exhaustively.
 */
internal object Json {

    fun obj(fields: List<Pair<String, String>>): String =
        fields.joinToString(",", "{", "}") { (k, v) -> "${str(k)}:$v" }

    fun strMap(map: Map<String, String>): String =
        map.entries.joinToString(",", "{", "}") { "${str(it.key)}:${str(it.value)}" }

    fun anyMap(map: Map<String, Any?>): String =
        map.entries.joinToString(",", "{", "}") { "${str(it.key)}:${value(it.value)}" }

    fun value(v: Any?): String = when (v) {
        null -> "null"
        is String -> str(v)
        is Boolean -> v.toString()
        // Infinities and NaN are not JSON. A report is worth more than its precision here.
        is Double -> if (v.isFinite()) v.toString() else str(v.toString())
        is Float -> if (v.isFinite()) v.toString() else str(v.toString())
        is Number -> v.toString()
        is Map<*, *> -> anyMap(v.entries.associate { (k, x) -> k.toString() to x })
        is Iterable<*> -> v.joinToString(",", "[", "]") { value(it) }
        else -> str(v.toString())
    }

    fun str(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else ->
                    // Control characters are not legal raw in a JSON string. Everything above
                    // them, including the whole of Arabic, goes through as UTF-8.
                    if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
