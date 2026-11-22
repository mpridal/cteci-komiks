package io.github.mpridal.ctecikomiks.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private val policy = UpdatePolicy("cteci-komiks")

    private fun release(tag: String, vararg names: String, draft: Boolean = false, pre: Boolean = false) =
        Release(tag, draft, pre, names.map { ReleaseAsset(it, "https://example.invalid/$it", 10) })

    @Test fun semverParseAndOrder() {
        assertEquals(SemVer(0, 1, 2), SemVer.parse("v0.1.2"))
        assertNull(SemVer.parse("0.1.0-protoA"))
        assertTrue(SemVer(0, 2, 0) > SemVer(0, 1, 9))
        assertEquals(10203, SemVer(1, 2, 3).versionCode)
    }

    @Test fun newerReleaseWithApkAndChecksumDownloads() {
        val d = policy.decide("0.1.0", release("v0.1.1", "cteci-komiks-0.1.1.apk", "cteci-komiks-0.1.1.apk.sha256"))
        assertTrue(d is UpdateDecision.Download)
        assertEquals("cteci-komiks-0.1.1.apk", (d as UpdateDecision.Download).apk.name)
    }

    @Test fun sameOrOlderIsUpToDate() {
        assertEquals(UpdateDecision.UpToDate, policy.decide("0.1.1", release("v0.1.1", "cteci-komiks-0.1.1.apk")))
        assertEquals(UpdateDecision.UpToDate, policy.decide("0.2.0", release("v0.1.1")))
    }

    @Test fun incompleteDraftOrDevBuildIsIgnored() {
        assertTrue(policy.decide("0.1.0", release("v0.1.1", "cteci-komiks-0.1.1.apk")) is UpdateDecision.Ignore)
        assertTrue(policy.decide("0.1.0", release("v0.1.1", draft = true)) is UpdateDecision.Ignore)
        assertTrue(policy.decide("0.1.0", release("v0.1.1", pre = true)) is UpdateDecision.Ignore)
        assertTrue(policy.decide("0.0.0-dev", release("v0.1.1")) is UpdateDecision.Ignore)
        assertTrue(policy.decide("0.1.0", null) is UpdateDecision.Ignore)
    }

    @Test fun checksumParsing() {
        val h = "a".repeat(64)
        assertEquals(h, UpdatePolicy.parseSha256("$h  cteci-komiks-0.1.1.apk\n"))
        assertNull(UpdatePolicy.parseSha256("nesmysl"))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UpdatePolicy.sha256("abc".byteInputStream()))
    }
}
