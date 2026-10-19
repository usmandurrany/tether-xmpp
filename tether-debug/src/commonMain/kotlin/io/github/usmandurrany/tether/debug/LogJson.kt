package io.github.usmandurrany.tether.debug

/**
 * The upload format (version 1), written by hand to keep the SDK free of a
 * JSON library. The portal's reference is docs/debug-tools.md.
 *
 * ```
 * {"v":1,
 *  "device":{"installId":"…","platform":"android","osVersion":"14 (API 34)","model":"Google Pixel 8","appId":"…","appVersion":"1.2.0"},
 *  "session":{"id":"…","startedAt":1760887801123,"label":"beta-3"},
 *  "dropped":0,
 *  "entries":[{"seq":1,"ts":1760887801200,"level":"debug","tag":"stanza","account":"alice@example.com/phone",
 *              "dir":"out","text":"<message …/>","stanza":{"name":"message","type":"chat","id":"…","to":"bob@example.com"}}]}
 * ```
 * Absent values are left out rather than written as null.
 */
internal object LogJson {
    fun batch(config: RemoteLogConfig, sessionId: String, startedAt: Long, entries: List<String>, dropped: Long): String = buildString {
        append("{\"v\":1,\"device\":{")
        val d = config.device
        fields(this, "installId" to d.installId, "platform" to d.platform, "osVersion" to d.osVersion, "model" to d.model, "appId" to d.appId, "appVersion" to d.appVersion)
        append("},\"session\":{\"id\":")
        string(this, sessionId)
        append(",\"startedAt\":").append(startedAt)
        config.label?.let { append(",\"label\":"); string(this, it) }
        append("},\"dropped\":").append(dropped)
        append(",\"entries\":[")
        entries.forEachIndexed { i, e -> if (i > 0) append(','); append(e) }
        append("]}")
    }

    fun entry(e: LogEntry): String = buildString {
        append("{\"seq\":").append(e.seq).append(",\"ts\":").append(e.timestampMillis).append(',')
        fields(
            this,
            "level" to e.level.name.lowercase(),
            "tag" to e.tag,
            "account" to e.account,
            "dir" to when (e.direction) {
                Direction.SENT -> "out"
                Direction.RECEIVED -> "in"
                null -> null
            },
            "text" to e.text,
            "error" to e.error,
        )
        e.stanza?.let { s ->
            append(",\"stanza\":{")
            fields(this, "name" to s.name, "type" to s.type, "id" to s.id, "from" to s.from, "to" to s.to, "payload" to s.payload)
            append('}')
        }
        append('}')
    }

    private fun fields(out: StringBuilder, vararg pairs: Pair<String, String?>) {
        var first = true
        for ((key, value) in pairs) {
            if (value == null) continue
            if (!first) out.append(',')
            first = false
            string(out, key)
            out.append(':')
            string(out, value)
        }
    }

    fun string(out: StringBuilder, value: String) {
        out.append('"')
        for (c in value) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                // Control characters, and U+2028/2029 which some JavaScript parsers reject.
                c < ' ' || c == ' ' || c == ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
