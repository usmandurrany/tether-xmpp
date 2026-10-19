package io.github.usmandurrany.tether.im

import io.github.usmandurrany.tether.core.XmppClient

/**
 * The usual chat features installed on a client in the right order: the
 * roster first, then carbons, then presence (so contacts see us only once the
 * roster is in place), then messaging and the archive.
 *
 * ```
 * val client = TetherJvm.client(config, scope)   // or TetherAndroid.client(...)
 * val im = InstantMessaging(client)
 * scope.launch { im.messaging.events.collect { show(it) } }
 * client.connect()
 * ```
 */
public class InstantMessaging(
    client: XmppClient,
    /** Keeps the roster between runs, so reconnects only fetch changes. */
    rosterStore: RosterStore? = null,
    presence: OwnPresence = OwnPresence(),
) {
    public val roster: Roster = Roster(client, rosterStore)
    public val carbons: Carbons = Carbons()
    public val presences: Presences = Presences(client, presence)
    public val messaging: Messaging = Messaging(client)
    public val history: History = History(client)
    public val push: Push = Push(client)

    init {
        client.install(roster)
        client.install(carbons)
        client.install(presences)
        client.install(messaging)
        client.install(history)
        client.install(push)
    }
}
