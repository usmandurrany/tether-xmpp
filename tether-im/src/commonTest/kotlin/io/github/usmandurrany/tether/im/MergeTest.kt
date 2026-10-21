package io.github.usmandurrany.tether.im

import io.github.usmandurrany.tether.core.BareJid
import io.github.usmandurrany.tether.im.TetherChat.Companion.changedBetween
import io.github.usmandurrany.tether.im.TetherChat.Companion.mergeInto
import io.github.usmandurrany.tether.im.TetherChat.Companion.mergedWith
import io.github.usmandurrany.tether.im.TetherChat.Companion.sameAs
import io.github.usmandurrany.tether.im.TetherChat.Companion.visibleOnly
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The fast merge must give exactly what the simple one-at-a-time merge gives. */
class MergeTest {
    private val bob = BareJid.parse("bob@example.com")

    /** The original merge: scan, then copy, per message. */
    private fun reference(list: List<Message>, batch: List<Message>): List<Message> {
        var result = list
        for (m in batch) {
            val index = result.indexOfFirst { it.sameAs(m) }
            result = if (index >= 0) {
                val merged = result[index].mergedWith(m)
                if (merged == result[index]) result else result.toMutableList().also { it[index] = merged }
            } else {
                val at = result.indexOfLast { it.timestamp <= m.timestamp } + 1
                result.toMutableList().also { it.add(at, m) }
            }
        }
        return result
    }

    private fun random(r: Random, n: Int, idSpace: Int): List<Message> = List(n) {
        val id = r.nextInt(idSpace)
        val outgoing = r.nextBoolean()
        Message(
            id = "m$id",
            peer = bob,
            outgoing = outgoing,
            body = "b$id",
            timestamp = r.nextLong(0, 50),
            status = MessageStatus.entries[r.nextInt(MessageStatus.entries.size)],
            // A server id belongs to one message, as on a real server.
            serverId = if (r.nextInt(3) == 0) "s$id-$outgoing" else null,
        )
    }

    @Test
    fun matchesTheSimpleMergeForSmallAndLargeBatches() {
        val r = Random(7)
        repeat(400) { round ->
            val start = reference(emptyList(), random(r, r.nextInt(0, 60), 80))
            val batch = random(r, if (round % 2 == 0) r.nextInt(1, 6) else r.nextInt(9, 80), 80)
            val (fast, saved) = mergeInto(start, batch, { true }) { old, new -> old.mergedWith(new) }
            val want = reference(start, batch)
            assertEquals(want, fast, "round $round")
            // What to save: exactly what is new or changed.
            assertEquals(want.filter { w -> start.none { it === w || it == w } }.toSet(), saved.toSet(), "saved, round $round")
        }
    }

    @Test
    fun aStoreLoadSavesNothingBack() {
        // As a store holds them: each message once.
        val loaded = List(200) { Message("m$it", bob, it % 2 == 0, "b", (it * 7L) % 50, MessageStatus.READ, serverId = "s$it") }.shuffled(Random(1))
        val (list, saved) = mergeInto(emptyList(), loaded, { false }) { old, new -> old.mergedWith(new) }
        assertEquals(reference(emptyList(), loaded), list)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun changesAreFoundByPosition() {
        val list = reference(emptyList(), random(Random(2), 100, 1000))
        val changed = list.mapIndexed { i, m -> if (i % 10 == 0) m.copy(status = MessageStatus.READ, body = "x") else m }
        assertEquals(changed.filterIndexed { i, _ -> i % 10 == 0 }.filter { it !in list }, changedBetween(list, changed))
        val removed = list.filterIndexed { i, _ -> i != 5 }
        assertTrue(changedBetween(list, removed).isEmpty())
    }

    @Test
    fun anUnchangedChatKeepsItsVisibleList() {
        val carol = BareJid.parse("carol@example.com")
        val visible = visibleOnly()
        val a = mapOf(bob to listOf(Message("1", bob, false, "x", 1, MessageStatus.READ)), carol to emptyList())
        val first = visible(a)
        val second = visible(a + (carol to listOf(Message("2", carol, false, "y", 2, MessageStatus.READ, hidden = true))))
        assertSame(first.getValue(bob), second.getValue(bob))
        assertTrue(second.getValue(carol).isEmpty())
    }
}
