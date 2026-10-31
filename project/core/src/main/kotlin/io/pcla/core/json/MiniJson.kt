package io.pcla.core.json

/**
 * Minimal zero-dependency JSON parser.
 *
 * Why not kotlinx.serialization: the core module deliberately takes no third-party
 * dependency, so it can be built into the Android APK, run unit tests directly with `kotlinc`
 * in Termux, and be compile-checked by CI with no Android SDK installed.
 *
 * Supports: object / array / string (with escapes) / number / true / false / null.
 * Does not support: comments, trailing commas. Invalid input throws [JsonParseException] — no guessing.
 */
object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.readValue()
        p.finish()
        return v
    }
}

class JsonParseException(message: String) : Exception(message)

private class Parser(private val s: String) {
    private var i = 0

    fun finish() {
        skipWs()
        if (i < s.length) fail("解析结束后仍有内容")
    }

    fun readValue(): Any? {
        skipWs()
        if (i >= s.length) fail("意外的文件结尾")
        return when (val c = s[i]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> {
                if (c == '-' || c in '0'..'9') readNumber() else fail("非法字符 '$c'")
            }
        }
    }

    private fun literal(word: String, value: Any?): Any? {
        if (!s.startsWith(word, i)) fail("期望 $word")
        i += word.length
        return value
    }

    private fun readObject(): Map<String, Any?> {
        i++ // {
        val out = LinkedHashMap<String, Any?>()
        skipWs()
        if (i < s.length && s[i] == '}') { i++; return out }
        while (true) {
            skipWs()
            if (i >= s.length || s[i] != '"') fail("对象键必须是字符串")
            val key = readString()
            skipWs()
            if (i >= s.length || s[i] != ':') fail("缺少冒号")
            i++
            out[key] = readValue()
            skipWs()
            if (i >= s.length) fail("对象未闭合")
            when (s[i]) {
                ',' -> { i++; continue }
                '}' -> { i++; return out }
                else -> fail("对象中期望 ',' 或 '}'")
            }
        }
    }

    private fun readArray(): List<Any?> {
        i++ // [
        val out = ArrayList<Any?>()
        skipWs()
        if (i < s.length && s[i] == ']') { i++; return out }
        while (true) {
            out.add(readValue())
            skipWs()
            if (i >= s.length) fail("数组未闭合")
            when (s[i]) {
                ',' -> { i++; continue }
                ']' -> { i++; return out }
                else -> fail("数组中期望 ',' 或 ']'")
            }
        }
    }

    private fun readString(): String {
        i++ // opening quote
        val sb = StringBuilder()
        while (i < s.length) {
            when (val c = s[i]) {
                '"' -> { i++; return sb.toString() }
                '\\' -> {
                    i++
                    if (i >= s.length) fail("转义序列未完成")
                    when (val e = s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 >= s.length) fail("\\u 需要 4 位十六进制")
                            val hex = s.substring(i + 1, i + 5)
                            sb.append(hex.toIntOrNull(16)?.toChar() ?: fail("非法 \\u 转义 $hex"))
                            i += 4
                        }
                        else -> fail("未知转义 \\$e")
                    }
                    i++
                }
                else -> { sb.append(c); i++ }
            }
        }
        fail("字符串未闭合")
    }

    private fun readNumber(): Double {
        val start = i
        if (i < s.length && s[i] == '-') i++
        while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
        val raw = s.substring(start, i)
        return raw.toDoubleOrNull() ?: fail("非法数字 $raw")
    }

    private fun skipWs() {
        while (i < s.length && s[i] in " \t\r\n") i++
    }

    private fun fail(msg: String): Nothing = throw JsonParseException("$msg（位置 $i）")
}
