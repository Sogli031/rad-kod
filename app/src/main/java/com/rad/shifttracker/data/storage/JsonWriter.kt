package com.rad.shifttracker.data.storage

object JsonWriter {
    fun write(value: Any?): String {
        return writeValue(value, 0)
    }

    private fun writeValue(value: Any?, level: Int): String {
        return when (value) {
            null -> "null"
            is String -> "\"${escape(value)}\""
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> writeMap(@Suppress("UNCHECKED_CAST") (value as Map<String, Any?>), level)
            is List<*> -> writeList(value, level)
            else -> "\"${escape(value.toString())}\""
        }
    }

    private fun writeMap(map: Map<String, Any?>, level: Int): String {
        if (map.isEmpty()) return "{}"
        val indent = "  "
        val pad = indent.repeat(level)
        val sb = StringBuilder()
        sb.append("{\n")
        val entries = map.entries.toList()
        for (i in entries.indices) {
            val entry = entries[i]
            sb.append(pad).append(indent)
            sb.append("\"").append(escape(entry.key)).append("\": ")
            sb.append(writeValue(entry.value, level + 1))
            if (i < entries.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append(pad).append("}")
        return sb.toString()
    }

    private fun writeList(list: List<*>, level: Int): String {
        if (list.isEmpty()) return "[]"
        val indent = "  "
        val pad = indent.repeat(level)
        val sb = StringBuilder()
        sb.append("[\n")
        for (i in list.indices) {
            sb.append(pad).append(indent)
            sb.append(writeValue(list[i], level + 1))
            if (i < list.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append(pad).append("]")
        return sb.toString()
    }

    private fun escape(value: String): String {
        return buildString {
            for (ch in value) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
    }
}
