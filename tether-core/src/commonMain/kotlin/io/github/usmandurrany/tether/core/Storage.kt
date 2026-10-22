package io.github.usmandurrany.tether.core

import io.github.usmandurrany.tether.sasl.FastToken
import io.github.usmandurrany.tether.sasl.ScramAlgorithm
import io.github.usmandurrany.tether.sasl.ScramKeys
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64

/**
 * Where Tether keeps state between runs. Platform modules provide durable and
 * encrypted implementations (`TetherAndroid.secureStore`, `FileKeyValueStore`).
 * Writes must be atomic per key: a crash leaves the old value or the new one.
 */
public interface KeyValueStore {
    public suspend fun get(key: String): String?

    /** Stores [value], or removes the key when it is null. */
    public suspend fun put(key: String, value: String?)
}

/** Keeps everything in memory: nothing survives the process. For tests and throwaway sessions. */
public class MemoryKeyValueStore : KeyValueStore {
    private val mutex = Mutex()
    private val values = HashMap<String, String>()

    override suspend fun get(key: String): String? = mutex.withLock { values[key] }

    override suspend fun put(key: String, value: String?) {
        mutex.withLock { if (value == null) values.remove(key) else values[key] = value }
    }
}

/**
 * The account's login secrets, so the app can log in again without asking for
 * the password. After the first login only the SCRAM keys (RFC 5802) and, when
 * the server offers FAST (XEP-0484), a login token are kept, never the
 * password: they work for this server only and cannot be turned back into the
 * password, but they still log in, so the store must be encrypted.
 */
public class CredentialVault(private val store: KeyValueStore) {
    /** Saves the keys a login derived (see [XmppClient.scramKeys]). */
    public suspend fun saveKeys(account: BareJid, keys: ScramKeys) {
        val line = listOf(
            keys.algorithm.name,
            keys.iterations.toString(),
            Base64.Default.encode(keys.salt),
            Base64.Default.encode(keys.clientKey),
            Base64.Default.encode(keys.serverKey),
        ).joinToString(" ")
        store.put(keyFor(account), line)
    }

    /** The stored keys, or an empty list (also for unreadable data). */
    public suspend fun loadKeys(account: BareJid): List<ScramKeys> {
        val line = store.get(keyFor(account)) ?: return emptyList()
        return runCatching {
            val (algorithm, iterations, salt, clientKey, serverKey) = line.split(" ")
            listOf(
                ScramKeys(
                    ScramAlgorithm.valueOf(algorithm),
                    Base64.Default.decode(salt),
                    iterations.toInt(),
                    Base64.Default.decode(clientKey),
                    Base64.Default.decode(serverKey),
                ),
            )
        }.getOrDefault(emptyList())
    }

    /** Saves the FAST token the server issued, or forgets it when [token] is null. */
    public suspend fun saveFastToken(account: BareJid, token: FastToken?) {
        val line = token?.let {
            listOfNotNull(it.mechanism, Base64.Default.encode(it.token.encodeToByteArray()), it.expiry).joinToString(" ")
        }
        store.put(fastKeyFor(account), line)
    }

    /** The stored FAST token, or null (also for unreadable data). */
    public suspend fun loadFastToken(account: BareJid): FastToken? {
        val line = store.get(fastKeyFor(account)) ?: return null
        return runCatching {
            val parts = line.split(" ")
            FastToken(parts[0], Base64.Default.decode(parts[1]).decodeToString(), parts.getOrNull(2))
        }.getOrNull()
    }

    /**
     * The SASL2 user-agent id for [account] on this installation, made on
     * first use. FAST tokens only work with the id they were issued to.
     */
    public suspend fun userAgentId(account: BareJid): String {
        store.get(agentKeyFor(account))?.let { return it }
        return UserAgent.randomUuid().also { store.put(agentKeyFor(account), it) }
    }

    /**
     * True when an earlier login saved SCRAM keys or a FAST token for
     * [account], so it can log in without the password.
     */
    public suspend fun hasKeys(account: BareJid): Boolean =
        loadKeys(account).isNotEmpty() || loadFastToken(account) != null

    /** Forgets the account's secrets, for example on logout. */
    public suspend fun clear(account: BareJid) {
        store.put(keyFor(account), null)
        store.put(fastKeyFor(account), null)
    }

    private fun keyFor(account: BareJid) = "tether.scram.$account"

    private fun fastKeyFor(account: BareJid) = "tether.fast.$account"

    private fun agentKeyFor(account: BareJid) = "tether.agent.$account"
}
