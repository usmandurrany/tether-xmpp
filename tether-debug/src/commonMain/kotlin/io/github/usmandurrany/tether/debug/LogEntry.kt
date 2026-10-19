package io.github.usmandurrany.tether.debug

/** Severity of a log entry. Stanzas are logged at [DEBUG]. */
public enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }

/** Which way a stanza went, seen from this device. */
public enum class Direction { SENT, RECEIVED }

/**
 * The parts of a stanza worth filtering on, so a viewer can search without
 * parsing XML. [payload] is the namespace of the first child element, which
 * names what an IQ is about (for example `jabber:iq:roster`).
 */
public data class StanzaSummary(
    public val name: String,
    public val type: String? = null,
    public val id: String? = null,
    public val from: String? = null,
    public val to: String? = null,
    public val payload: String? = null,
)

/**
 * One line of the debug log: either a stanza ([direction] set) or an event
 * the SDK or the app logged ([direction] null).
 *
 * Entries are already redacted when they are created, so every sink, local or
 * remote, sees the same safe text.
 */
public data class LogEntry(
    /** Increases by one per entry within one [TetherDebug]; lets a server order and de-duplicate. */
    public val seq: Long,
    /** Wall-clock time, milliseconds since the Unix epoch. */
    public val timestampMillis: Long,
    public val level: LogLevel,
    /** `stanza` for traffic; otherwise whatever the caller chose ("app", "connection", ...). */
    public val tag: String,
    /** The account this entry belongs to (full JID once bound), when known. */
    public val account: String?,
    /** The redacted XML for stanzas, the message for events. */
    public val text: String,
    public val direction: Direction? = null,
    public val stanza: StanzaSummary? = null,
    /** Stack trace or error description for events that carry one. */
    public val error: String? = null,
) {
    public val isStanza: Boolean get() = direction != null

    public companion object {
        public const val STANZA_TAG: String = "stanza"
    }
}
