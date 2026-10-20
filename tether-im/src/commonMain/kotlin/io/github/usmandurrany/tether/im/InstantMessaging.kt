package io.github.usmandurrany.tether.im

import io.github.usmandurrany.tether.core.ClientExtension
import io.github.usmandurrany.tether.core.SessionContext
import io.github.usmandurrany.tether.core.XmppClient
import io.github.usmandurrany.tether.xml.element

/**
 * Which chat features to turn on. Everything is on by default; switch off what
 * the app does not want. Each feature also checks that the server supports it,
 * so leaving one on against a server without it is harmless.
 */
public data class ChatFeatures(
    /** Ask contacts' devices to confirm delivery, and confirm theirs (XEP-0184): two ticks. */
    val deliveryReceipts: Boolean = true,
    /** See when contacts read our messages (XEP-0333): blue ticks. */
    val readMarkers: Boolean = true,
    /**
     * Tell contacts when we read theirs. A privacy setting: with it off, we
     * still see their read markers when [readMarkers] is on. Can also be
     * changed at run time with [TetherChat.sendReadMarkers].
     */
    val sendReadMarkers: Boolean = true,
    /** Typing indicators both ways (XEP-0085). */
    val typingIndicators: Boolean = true,
    /** The contact list, kept on the server (RFC 6121 roster). */
    val contacts: Boolean = true,
    /**
     * Contacts' online status and subscription requests. With it off, the
     * account still goes online (the server only routes messages to an online
     * device), but contacts' presence is not tracked.
     */
    val presence: Boolean = true,
    /** Copies of messages sent and received on our other devices (XEP-0280). */
    val multiDevice: Boolean = true,
    /** The server's message archive (XEP-0313): fills chats on a new device and catches up after being offline. */
    val history: Boolean = true,
    /** When contacts were last online (XEP-0012), on [TetherChat.contacts] and [TetherChat.conversations]. Needs [presence]. */
    val lastSeen: Boolean = true,
    /**
     * Delete for everyone (XEP-0424): [TetherChat.deleteForEveryone], and
     * contacts' deletions remove their messages here. With it off, contacts'
     * deletions are ignored and their messages stay.
     */
    val deleteForEveryone: Boolean = true,
    /**
     * Accept every contact request at once: the requester sees our presence and
     * we ask to see theirs, so both become contacts. Off by default, because
     * anyone who knows the address could then see when we are online. With it
     * off, requests wait in [TetherChat.contactRequests] for [TetherChat.approve]
     * or [TetherChat.deny]. Needs [presence].
     */
    val autoAcceptContacts: Boolean = false,
)

/**
 * The chat features installed on a client in the right order: the roster
 * first, then carbons, then presence (so contacts see us only once the roster
 * is in place), then messaging and the archive.
 *
 * Most apps should use [TetherChat], which builds this and handles message
 * state, read markers and typing for you. Use [InstantMessaging] directly to
 * work with the protocol-level events yourself.
 *
 * ```
 * val client = TetherJvm.client(config, scope)   // or TetherAndroid.client(...)
 * val im = InstantMessaging(client)
 * scope.launch { im.messaging.events.collect { show(it) } }
 * client.connect()
 * ```
 *
 * Features switched off in [features] are still constructed, so the
 * properties are always there, but never installed: their flows stay empty.
 */
public class InstantMessaging(
    client: XmppClient,
    /** Keeps the roster between runs, so reconnects only fetch changes. */
    rosterStore: RosterStore? = null,
    presence: OwnPresence = OwnPresence(),
    public val features: ChatFeatures = ChatFeatures(),
) {
    public val roster: Roster = Roster(client, rosterStore)
    public val carbons: Carbons = Carbons()
    public val presences: Presences = Presences(client, presence)
    public val messaging: Messaging = Messaging(
        client,
        sendReceipts = features.deliveryReceipts,
        requestReceipts = features.deliveryReceipts,
        markable = features.readMarkers,
        chatStates = features.typingIndicators,
    )
    public val history: History = History(client)
    public val push: Push = Push(client)
    public val lastActivity: LastActivity = LastActivity(client)

    init {
        if (features.contacts) client.install(roster)
        if (features.multiDevice) client.install(carbons)
        client.install(if (features.presence) presences else Availability(presence))
        client.install(messaging)
        if (features.history) client.install(history)
        client.install(push)
    }
}

/** Goes online on each new session without tracking anyone else's presence. */
private class Availability(private val own: OwnPresence) : ClientExtension {
    override suspend fun onSessionStarted(session: SessionContext) {
        if (session.resumed) return
        session.send(
            element("presence", ImNs.CLIENT) {
                own.show?.let { element("show") { text(it.wire) } }
                own.status?.let { element("status") { text(it) } }
                if (own.priority != 0) element("priority") { text(own.priority.toString()) }
            },
        )
    }
}
