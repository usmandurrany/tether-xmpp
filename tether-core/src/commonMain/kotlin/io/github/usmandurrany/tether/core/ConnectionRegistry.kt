package io.github.usmandurrany.tether.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Hands out one [ConnectionSupervisor] per account (design §4.1a, rule 1).
 * Keep a single registry per process; asking for the same account again
 * returns the existing supervisor instead of creating a second connection.
 */
public class ConnectionRegistry {
    private val mutex = Mutex()
    private val supervisors = HashMap<BareJid, ConnectionSupervisor>()

    public suspend fun getOrCreate(account: BareJid, create: () -> ConnectionSupervisor): ConnectionSupervisor =
        mutex.withLock { supervisors.getOrPut(account, create) }

    /** Closes and forgets the account's supervisor, for example on logout. */
    public suspend fun remove(account: BareJid) {
        mutex.withLock { supervisors.remove(account) }?.close()
    }
}
