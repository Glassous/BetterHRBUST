package com.glassous.betterhrbust.core.network

import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

object CharsetDecoderHelper {
    val GBK: Charset = Charset.forName("GBK")
    val UTF_8: Charset = StandardCharsets.UTF_8

    fun decode(bytes: ByteArray, contentTypeHeader: String?, preferredCharset: Charset? = null): String {
        if (bytes.isEmpty()) return ""

        var headerCharsetName: String? = null
        if (!contentTypeHeader.isNullOrBlank()) {
            val parts = contentTypeHeader.split(";")
            for (part in parts) {
                val trimmed = part.trim()
                if (trimmed.startsWith("charset=", ignoreCase = true)) {
                    headerCharsetName = trimmed.substring("charset=".length).trim().trim('"', '\'')
                }
            }
        }

        // 响应头明确声明了编码（非 Servlet 缺省占位的 ISO-8859-1）时优先遵从响应头，
        // 避免类似培养方案页（meta 写 GBK、HTTP 头返回 UTF-8）被端点兜底编码覆盖导致整页乱码
        val explicitHeaderCharset = when {
            headerCharsetName.isNullOrBlank() -> null
            headerCharsetName.equals("ISO-8859-1", ignoreCase = true) -> null
            headerCharsetName.contains("utf", ignoreCase = true) -> UTF_8
            headerCharsetName.contains("gb", ignoreCase = true) -> GBK
            else -> runCatching { Charset.forName(headerCharsetName) }.getOrNull()
        }
        if (explicitHeaderCharset != null) {
            return decodeWithFallback(bytes, explicitHeaderCharset)
        }

        if (preferredCharset != null) {
            return decodeWithFallback(bytes, preferredCharset)
        }

        val headerSlice = String(bytes.take(2048).toByteArray(), StandardCharsets.ISO_8859_1)
        val metaMatch = Regex("""<meta[^>]+charset=["']?([a-zA-Z0-9_\-]+)""", RegexOption.IGNORE_CASE).find(headerSlice)
        val metaCharset = metaMatch?.groupValues?.get(1)

        val primaryCharset = when {
            metaCharset?.contains("utf", ignoreCase = true) == true -> UTF_8
            metaCharset?.contains("gb", ignoreCase = true) == true -> GBK
            else -> GBK
        }
        return decodeWithFallback(bytes, primaryCharset)
    }

    private fun decodeWithFallback(bytes: ByteArray, primaryCharset: Charset): String {
        val decoded = String(bytes, primaryCharset)
        val primaryReplacements = countReplacements(decoded)
        if (primaryReplacements == 0) return decoded
        val fallbackCharset = if (primaryCharset == UTF_8) GBK else UTF_8
        val retry = String(bytes, fallbackCharset)
        val retryReplacements = countReplacements(retry)
        if (retryReplacements == 0 || (hasExcessiveReplacements(decoded, bytes.size) && retryReplacements < primaryReplacements)) {
            return retry
        }
        return decoded
    }

    private fun countReplacements(text: String): Int = text.count { it == '\uFFFD' }

    private fun hasExcessiveReplacements(text: String, byteLength: Int): Boolean {
        if (byteLength == 0) return false
        val count = countReplacements(text)
        return count > 5 && (count.toDouble() / byteLength.toDouble()) > 0.005
    }

    fun encodeGbk(value: String): String = URLEncoder.encode(value, "GBK")
}
