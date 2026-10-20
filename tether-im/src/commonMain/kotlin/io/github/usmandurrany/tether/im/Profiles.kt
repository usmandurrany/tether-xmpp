package io.github.usmandurrany.tether.im

import io.github.usmandurrany.tether.core.BareJid
import io.github.usmandurrany.tether.core.ClientExtension
import io.github.usmandurrany.tether.core.SessionContext
import io.github.usmandurrany.tether.core.StanzaSender
import io.github.usmandurrany.tether.core.XmppClient
import io.github.usmandurrany.tether.crypto.Crypto
import io.github.usmandurrany.tether.crypto.HashAlgorithm
import io.github.usmandurrany.tether.xml.Element
import io.github.usmandurrany.tether.xml.ElementBuilder
import io.github.usmandurrany.tether.xml.element
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64

/** A profile picture: the image bytes and their MIME type, such as `image/jpeg`. */
public class Avatar(public val type: String, public val data: ByteArray) {
    /** SHA-1 of [data] in hex, as contacts see it in our presence (XEP-0153). */
    public val hash: String by lazy { hex(Crypto.hash(HashAlgorithm.SHA1, data)) }

    override fun equals(other: Any?): Boolean = other is Avatar && other.type == type && other.hash == hash

    override fun hashCode(): Int = hash.hashCode()

    override fun toString(): String = "Avatar($type, ${data.size} bytes)"

    private companion object {
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

/**
 * Someone's public profile: their vCard (XEP-0054). Every field is optional.
 *
 * Fields the SDK does not model are kept in [other], so saving a profile you
 * read never drops what another app put there.
 */
public data class Profile(
    val fullName: String? = null,
    val nickname: String? = null,
    val givenName: String? = null,
    val familyName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val organization: String? = null,
    val title: String? = null,
    val url: String? = null,
    /** As written in the vCard, normally `YYYY-MM-DD`. */
    val birthday: String? = null,
    /** A short "about me". */
    val note: String? = null,
    val avatar: Avatar? = null,
    /** The vCard's other fields, as received. */
    val other: List<Element> = emptyList(),
) {
    /** The name to show: nickname, then full name, then given and family name. */
    val displayName: String?
        get() = nickname?.ifBlank { null } ?: fullName?.ifBlank { null }
            ?: listOfNotNull(givenName, familyName).joinToString(" ").ifBlank { null }

    /** The vCard element, as sent to the server. */
    public fun toVCard(): Element = element("vCard", ImNs.VCARD) {
        text("FN", fullName)
        if (givenName != null || familyName != null) {
            element("N") {
                text("FAMILY", familyName)
                text("GIVEN", givenName)
            }
        }
        text("NICKNAME", nickname)
        email?.let { element("EMAIL") { element("INTERNET") {}; element("USERID") { text(it) } } }
        phone?.let { element("TEL") { element("VOICE") {}; element("NUMBER") { text(it) } } }
        organization?.let { element("ORG") { element("ORGNAME") { text(it) } } }
        text("TITLE", title)
        text("URL", url)
        text("BDAY", birthday)
        text("DESC", note)
        avatar?.let {
            element("PHOTO") {
                element("TYPE") { text(it.type) }
                element("BINVAL") { text(Base64.Default.encode(it.data)) }
            }
        }
        other.forEach { child(it) }
    }

    public companion object {
        private val MODELED = setOf("FN", "N", "NICKNAME", "TITLE", "URL", "BDAY", "DESC", "PHOTO")

        /** Reads a vCard element; anything it cannot read is kept in [Profile.other]. */
        public fun fromVCard(vcard: Element): Profile {
            fun field(name: String) = vcard.child(name, ImNs.VCARD)?.text?.trim()?.ifEmpty { null }
            val n = vcard.child("N", ImNs.VCARD)
            // The first EMAIL, TEL and ORG are modeled; more of them stay in [other].
            val email = vcard.children("EMAIL", ImNs.VCARD).firstOrNull()
            val tel = vcard.children("TEL", ImNs.VCARD).firstOrNull()
            val org = vcard.children("ORG", ImNs.VCARD).firstOrNull()
            val photo = vcard.child("PHOTO", ImNs.VCARD)?.let { p ->
                val data = p.child("BINVAL", ImNs.VCARD)?.text?.filterNot(Char::isWhitespace)?.ifEmpty { null }
                    ?.let { runCatching { Base64.Default.decode(it) }.getOrNull() }
                data?.let { Avatar(p.child("TYPE", ImNs.VCARD)?.text?.trim()?.ifEmpty { null } ?: "image/png", it) }
            }
            val modeled = listOfNotNull(email, tel, org)
            return Profile(
                fullName = field("FN"),
                nickname = field("NICKNAME"),
                givenName = n?.child("GIVEN", ImNs.VCARD)?.text?.trim()?.ifEmpty { null },
                familyName = n?.child("FAMILY", ImNs.VCARD)?.text?.trim()?.ifEmpty { null },
                email = email?.child("USERID", ImNs.VCARD)?.text?.trim()?.ifEmpty { null },
                phone = tel?.child("NUMBER", ImNs.VCARD)?.text?.trim()?.ifEmpty { null },
                organization = org?.child("ORGNAME", ImNs.VCARD)?.text?.trim()?.ifEmpty { null },
                title = field("TITLE"),
                url = field("URL"),
                birthday = field("BDAY"),
                note = field("DESC"),
                avatar = photo,
                other = vcard.elements.filter { it.name !in MODELED && modeled.none { m -> m === it } },
            )
        }

        private fun ElementBuilder.text(name: String, value: String?) {
            if (value != null) element(name) { text(value) }
        }
    }
}

/**
 * Profiles and profile pictures (XEP-0054 vcard-temp, XEP-0153 avatar hashes).
 *
 * Our own profile is read on each new login. Contacts tell us in their
 * presence when their picture changes; [avatarHashes] has the latest, and
 * [fetch] gets the profile. [TetherChat] does the fetching for you.
 *
 * Install before [Presences], so our first presence already carries our
 * picture's hash.
 */
public class Profiles internal constructor(
    private val client: StanzaSender,
    private val account: BareJid,
    private val scope: CoroutineScope,
) : ClientExtension {
    public constructor(client: XmppClient) : this(client, client.account, client.coroutineScope)

    private val _mine = MutableStateFlow<Profile?>(null)

    /** Our profile, once read from the server; null until then or without a connection. */
    public val mine: StateFlow<Profile?> = _mine.asStateFlow()

    private val _known = MutableStateFlow<Map<BareJid, Profile>>(emptyMap())

    /** Contacts' profiles fetched so far, kept in memory. */
    public val known: StateFlow<Map<BareJid, Profile>> = _known.asStateFlow()

    private val _avatarHashes = MutableStateFlow<Map<BareJid, String>>(emptyMap())

    /** The picture each contact's presence advertises: a SHA-1 hex, or "" for no picture. */
    public val avatarHashes: StateFlow<Map<BareJid, String>> = _avatarHashes.asStateFlow()

    /** Our picture's hash; null while unknown. */
    @Volatile
    private var ownHash: String? = null

    @Volatile
    private var started = false

    /** This device's full address, to skip our own presence when the server echoes it. */
    @Volatile
    private var ownFull: String? = null

    /** Sends our presence again, so contacts see a new picture hash. Set by [InstantMessaging]. */
    internal var reannounce: suspend () -> Unit = {}

    override suspend fun onSessionStarted(session: SessionContext) {
        val firstInProcess = !started
        started = true
        ownFull = session.jid.toString()
        if (session.resumed && !firstInProcess) return
        if (!session.capabilities.serverSupports(ImNs.VCARD)) return
        // Short timeout: going online matters more than the profile, which [refreshMine] can get later.
        runCatching { readMine(session.request(iq("get") { element("vCard", ImNs.VCARD) {} }, timeoutMillis = 5_000)) }
    }

    override suspend fun onStanza(stanza: Element): Boolean {
        if (stanza.name != "presence" || stanza.attribute("type") != null) return false
        val update = stanza.child("x", ImNs.VCARD_UPDATE) ?: return false
        val photo = update.child("photo", ImNs.VCARD_UPDATE) ?: return false // "not ready": no news
        val hash = photo.text.trim().lowercase()
        val from = bareOf(stanza.attribute("from")) ?: return false
        if (from == account) {
            if (stanza.attribute("from") == ownFull) return false
            // Another of our devices changed the picture.
            if (hash != ownHash) scope.launch { runCatching { refreshMine() } }
        } else {
            _avatarHashes.update { if (it[from] == hash) it else it + (from to hash) }
        }
        return false // Presences reads the rest.
    }

    /** Reads our profile from the server again. */
    public suspend fun refreshMine(): Profile {
        val answer = client.request(iq("get") { element("vCard", ImNs.VCARD) {} }, timeoutMillis = 15_000)
        return readMine(answer)
    }

    /**
     * Replaces our profile on the server, and tells contacts when the picture
     * changed. Keep the picture small (a 192×192 JPEG of 10–30 KB): servers
     * limit the size of one stanza. Throws [XmppStanzaException] if the server
     * refuses.
     */
    public suspend fun publish(profile: Profile) {
        client.request(iq("set") { child(profile.toVCard()) }, timeoutMillis = 30_000).requireResult()
        _mine.value = profile
        setOwnHash(profile.avatar?.hash ?: "")
    }

    /**
     * Fetches [jid]'s profile from their server. A contact with no vCard has
     * an empty [Profile]. Throws [XmppStanzaException] for other errors.
     */
    public suspend fun fetch(jid: BareJid): Profile {
        val answer = client.request(iq("get", to = jid.toString()) { element("vCard", ImNs.VCARD) {} }, timeoutMillis = 15_000)
        val profile = parseAnswer(answer, from = jid)
        _known.update { it + (jid to profile) }
        return profile
    }

    /** The `<x xmlns='vcard-temp:x:update'/>` for our presence. */
    internal fun presenceUpdate(): Element = element("x", ImNs.VCARD_UPDATE) {
        ownHash?.let { element("photo") { if (it.isNotEmpty()) text(it) } }
    }

    private suspend fun readMine(answer: Element): Profile {
        val profile = parseAnswer(answer, from = null)
        _mine.value = profile
        setOwnHash(profile.avatar?.hash ?: "")
        return profile
    }

    private suspend fun setOwnHash(hash: String) {
        val changed = ownHash != null && ownHash != hash
        ownHash = hash
        if (changed) reannounce()
    }

    private fun parseAnswer(answer: Element, from: BareJid?): Profile {
        if (from != null && answer.attribute("from") != null && bareOf(answer.attribute("from")) != from) {
            throw XmppStanzaException("bad-request", answer)
        }
        if (from == null && !fromOwnAccount(answer, account)) throw XmppStanzaException("bad-request", answer)
        if (answer.attribute("type") == "error") {
            if (errorCondition(answer) == "item-not-found") return Profile()
            throw XmppStanzaException(errorCondition(answer), answer)
        }
        return answer.child("vCard", ImNs.VCARD)?.let(Profile::fromVCard) ?: Profile()
    }
}
