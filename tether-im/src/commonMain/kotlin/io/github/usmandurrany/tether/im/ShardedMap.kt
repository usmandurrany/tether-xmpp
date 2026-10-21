package io.github.usmandurrany.tether.im

/**
 * An immutable map that changes one key by copying only the part holding it:
 * the keys are spread over [SHARDS] small maps, and [with] copies one of
 * them. With thousands of chats, a new message then copies a few dozen
 * entries instead of every chat. Equality compares shard by shard, the same
 * objects first, so telling an updated map from the one before is cheap too.
 */
internal class ShardedMap<K, V> private constructor(
    private val shards: Array<Map<K, V>>,
    override val size: Int,
) : AbstractMap<K, V>() {
    override fun get(key: K): V? = shards[shardOf(key)][key]

    override fun containsKey(key: K): Boolean = shards[shardOf(key)].containsKey(key)

    override val entries: Set<Map.Entry<K, V>> = object : AbstractSet<Map.Entry<K, V>>() {
        override val size: Int get() = this@ShardedMap.size

        override fun iterator(): Iterator<Map.Entry<K, V>> = shards.asSequence().flatMap { it.entries.asSequence() }.iterator()
    }

    /** This map with [key] set to [value]. */
    fun with(key: K, value: V): ShardedMap<K, V> {
        val i = shardOf(key)
        val shard = shards[i]
        if (shard[key] === value && (value != null || shard.containsKey(key))) return this
        val copy = HashMap<K, V>(shard.size * 2 + 2).apply { putAll(shard); put(key, value) }
        return ShardedMap(shards.copyOf().also { it[i] = copy }, if (shard.containsKey(key)) size else size + 1)
    }

    /** Each shard through [transform], reusing [previous]'s result for the shards [previousSource] shares with this map. */
    fun <R> mapShards(
        previousSource: ShardedMap<K, V>?,
        previous: ShardedMap<K, R>?,
        transform: (Map<K, V>) -> Map<K, R>,
    ): ShardedMap<K, R> {
        var count = 0
        val result = Array(SHARDS) { i ->
            val shard = shards[i]
            val mapped = if (previousSource != null && previous != null && previousSource.shards[i] === shard) previous.shards[i] else transform(shard)
            count += mapped.size
            mapped
        }
        return ShardedMap(result, count)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ShardedMap<*, *>) return super.equals(other)
        if (size != other.size) return false
        return shards.indices.all { shards[it] === other.shards[it] || shards[it] == other.shards[it] }
    }

    override fun hashCode(): Int = shards.sumOf { it.hashCode() }

    companion object {
        const val SHARDS = 64

        private fun shardOf(key: Any?): Int {
            val h = key.hashCode()
            return (h xor (h ushr 16)) and (SHARDS - 1)
        }

        fun <K, V> empty(): ShardedMap<K, V> = ShardedMap(Array(SHARDS) { emptyMap() }, 0)

        fun <K, V> of(map: Map<K, V>): ShardedMap<K, V> {
            if (map is ShardedMap<K, V>) return map
            val shards = Array(SHARDS) { HashMap<K, V>() }
            for ((k, v) in map) shards[shardOf(k)][k] = v
            @Suppress("UNCHECKED_CAST")
            return ShardedMap(shards as Array<Map<K, V>>, map.size)
        }
    }
}
