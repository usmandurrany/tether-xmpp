package io.github.usmandurrany.tether.im

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class ShardedMapTest {
    @Test
    fun behavesLikeAMap() {
        val r = Random(3)
        var sharded = ShardedMap.empty<String, Int>()
        val plain = LinkedHashMap<String, Int>()
        repeat(2_000) {
            val k = "k${r.nextInt(500)}"
            val v = r.nextInt()
            sharded = sharded.with(k, v)
            plain[k] = v
        }
        assertEquals(plain.size, sharded.size)
        assertEquals<Map<String, Int>>(plain, sharded)
        assertEquals<Map<String, Int>>(sharded, plain)
        assertEquals(plain.toMap(), sharded.toMap())
        plain.forEach { (k, v) -> assertEquals(v, sharded[k]) }
        assertEquals(null, sharded["missing"])
    }

    @Test
    fun settingTheSameValueKeepsTheMapAndChangesAreSeen() {
        val value = listOf(1)
        val a = ShardedMap.empty<String, List<Int>>().with("x", value)
        assertSame(a, a.with("x", value))
        val b = a.with("x", listOf(2))
        assertNotEquals(a, b)
        assertEquals(a, b.with("x", listOf(1)), "equal by content too")
    }
}
