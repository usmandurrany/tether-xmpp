package io.github.usmandurrany.tether.im

import io.github.usmandurrany.tether.core.BareJid
import io.github.usmandurrany.tether.xml.element
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfilesTest {
    private val juliet = BareJid.parse("juliet@example.com")
    private val romeo = BareJid.parse("romeo@example.net")
    private val picture = byteArrayOf(1, 2, 3, 4, 5)

    // SHA-1 of bytes 1..5.
    private val pictureHash = "11966ab9c099f8fabefac54c08d5be2bd8c903af"

    private fun vcardAnswer(iq: io.github.usmandurrany.tether.xml.Element, from: String? = null) =
        element("iq", ImNs.CLIENT) {
            attribute("type", "result")
            attribute("id", iq.attribute("id"))
            attribute("from", from)
            child(Profile(nickname = "Jules", avatar = Avatar("image/png", picture)).toVCard())
        }

    private fun TestScope.setUp(sender: FakeSender): Pair<Profiles, Presences> {
        val profiles = Profiles(sender, juliet, backgroundScope)
        val presences = Presences(sender)
        presences.decorate = profiles::presenceUpdate
        profiles.reannounce = presences::reannounce
        return profiles to presences
    }

    @Test
    fun aVCardRoundTripKeepsWhatItDoesNotModel() {
        val vcard = xml(
            """<vCard xmlns='vcard-temp'>
                <FN>Romeo Montague</FN><N><FAMILY>Montague</FAMILY><GIVEN>Romeo</GIVEN></N>
                <EMAIL><INTERNET/><USERID>romeo@example.net</USERID></EMAIL>
                <EMAIL><HOME/><USERID>r@home.example</USERID></EMAIL>
                <TEL><VOICE/><NUMBER>+1 555</NUMBER></TEL>
                <ADR><LOCALITY>Verona</LOCALITY></ADR>
                <PHOTO><TYPE>image/png</TYPE><BINVAL>AQID
                BAU=</BINVAL></PHOTO>
            </vCard>""",
        )
        val profile = Profile.fromVCard(vcard)
        assertEquals("Romeo Montague", profile.fullName)
        assertEquals("Romeo", profile.givenName)
        assertEquals("romeo@example.net", profile.email)
        assertEquals("+1 555", profile.phone)
        assertContentEquals(picture, profile.avatar?.data)
        assertEquals(pictureHash, profile.avatar?.hash)
        assertEquals("Romeo Montague", profile.displayName)
        // The second address and the postal one are kept for the next save.
        assertEquals(listOf("EMAIL", "ADR"), profile.other.map { it.name })
        assertEquals(profile, Profile.fromVCard(profile.toVCard()))
    }

    @Test
    fun ourProfileIsReadOnLoginAndThePresenceCarriesItsHash() = runTest {
        val sender = FakeSender { vcardAnswer(it) }
        val (profiles, presences) = setUp(sender)
        val session = FakeSession(sender, serverFeatures = setOf(ImNs.VCARD))
        profiles.onSessionStarted(session)
        presences.onSessionStarted(session)
        assertEquals("Jules", profiles.mine.value?.nickname)
        val photo = sender.sent.single().child("x", ImNs.VCARD_UPDATE)?.child("photo", ImNs.VCARD_UPDATE)
        assertEquals(pictureHash, photo?.text)
    }

    @Test
    fun withoutServerSupportThePresenceSaysNotReady() = runTest {
        val sender = FakeSender()
        val (profiles, presences) = setUp(sender)
        val session = FakeSession(sender)
        profiles.onSessionStarted(session)
        presences.onSessionStarted(session)
        assertTrue(sender.requests.isEmpty())
        val x = sender.sent.single().child("x", ImNs.VCARD_UPDATE)
        assertNull(x?.child("photo", ImNs.VCARD_UPDATE))
    }

    @Test
    fun publishingANewPictureTellsContacts() = runTest {
        val sender = FakeSender { if (it.attribute("type") == "get") FakeSender.error(it, "item-not-found") else FakeSender.result(it) }
        val (profiles, presences) = setUp(sender)
        val session = FakeSession(sender, serverFeatures = setOf(ImNs.VCARD))
        profiles.onSessionStarted(session)
        presences.onSessionStarted(session)
        assertEquals(Profile(), profiles.mine.value)
        assertEquals("", sender.sent.last().child("x", ImNs.VCARD_UPDATE)?.child("photo", ImNs.VCARD_UPDATE)?.text)

        profiles.publish(Profile(nickname = "Jules", avatar = Avatar("image/png", picture)))
        assertEquals("set", sender.requests.last().attribute("type"))
        assertEquals(pictureHash, sender.sent.last().child("x", ImNs.VCARD_UPDATE)?.child("photo", ImNs.VCARD_UPDATE)?.text)
        assertEquals("Jules", profiles.mine.value?.nickname)
    }

    @Test
    fun contactsPicturesComeFromTheirPresence() = runTest {
        val sender = FakeSender { vcardAnswer(it) }
        val (profiles, _) = setUp(sender)
        profiles.onSessionStarted(FakeSession(sender, serverFeatures = setOf(ImNs.VCARD)))
        val requests = sender.requests.size
        profiles.onStanza(xml("<presence from='romeo@example.net/o'><x xmlns='vcard-temp:x:update'><photo>ABC</photo></x></presence>"))
        assertEquals(mapOf(romeo to "abc"), profiles.avatarHashes.value)
        // "Not ready" changes nothing.
        profiles.onStanza(xml("<presence from='romeo@example.net/o'><x xmlns='vcard-temp:x:update'/></presence>"))
        assertEquals(mapOf(romeo to "abc"), profiles.avatarHashes.value)
        // Our own presence, echoed by the server, is not news.
        profiles.onStanza(xml("<presence from='juliet@example.com/phone'><x xmlns='vcard-temp:x:update'><photo>other</photo></x></presence>"))
        runCurrent()
        assertEquals(requests, sender.requests.size)
        // Another of our devices with another picture: read ours again.
        profiles.onStanza(xml("<presence from='juliet@example.com/laptop'><x xmlns='vcard-temp:x:update'><photo>other</photo></x></presence>"))
        runCurrent()
        assertEquals(requests + 1, sender.requests.size)
    }

    @Test
    fun fetchingAContact() = runTest {
        var from: String? = "romeo@example.net"
        var notFound = false
        val sender = FakeSender { if (notFound) FakeSender.error(it, "item-not-found") else vcardAnswer(it, from) }
        val (profiles, _) = setUp(sender)
        assertEquals("Jules", profiles.fetch(romeo).nickname)
        assertEquals("romeo@example.net", sender.requests.last().attribute("to"))
        assertEquals("Jules", profiles.known.value[romeo]?.nickname)

        from = "mallory@example.org"
        assertFailsWith<XmppStanzaException> { profiles.fetch(romeo) }

        notFound = true
        assertEquals(Profile(), profiles.fetch(romeo))
    }
}
