// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateFeedTest {
    private fun release(tag: String, draft: Boolean = false, apkUrl: String? = null) = """
        {"tag_name":"$tag","draft":$draft,"prerelease":false,"assets":[
          {"name":"Seam-${tag.removePrefix("v")}-android.apk","browser_download_url":"${apkUrl ?: "https://github.com/aveterosystems/seam/releases/download/$tag/Seam-${tag.removePrefix("v")}-android.apk"}"},
          {"name":"SHA256SUMS","browser_download_url":"https://github.com/aveterosystems/seam/releases/download/$tag/SHA256SUMS"}]}
    """.trimIndent()

    @Test fun offersANewerRelease() {
        val r = UpdateFeed.newer(release("v0.1.1"), "0.1.0")!!
        assertEquals("0.1.1", r.version)
        assertTrue(r.apkUrl.endsWith("/v0.1.1/Seam-0.1.1-android.apk"))
    }

    @Test fun readsWhatChanged() {
        val body = "Seam 0.3.0 for Android and Mac.\r\n\r\n## What's changed\r\n\r\n- Faster **pairing**\r\n- Fixed `copy`\r\n\r\n## Android\r\nDownload it."
        assertEquals("• Faster pairing\n• Fixed copy", UpdateFeed.changes(body))
        assertEquals("", UpdateFeed.changes("No section here"))
        val json = release("v0.3.0").replaceFirst("{", "{\"body\":${org.json.JSONObject.quote(body)},")
        assertEquals("• Faster pairing\n• Fixed copy", UpdateFeed.newer(json, "0.2.1")!!.changes)
        assertEquals("", UpdateFeed.newer(release("v0.3.0"), "0.2.1")!!.changes)
    }

    @Test fun ignoresTheSameOrOlderVersion() {
        assertNull(UpdateFeed.newer(release("v0.1.0"), "0.1.0"))
        assertNull(UpdateFeed.newer(release("v0.0.9"), "0.1.0"))
    }

    @Test fun comparesNumbersNotText() {
        assertTrue(UpdateFeed.isNewer("v0.10.0", "0.9.9"))
        assertFalse(UpdateFeed.isNewer("v0.9.9", "0.10.0"))
        assertFalse(UpdateFeed.isNewer("v1.0", "0.9.9"))
        assertFalse(UpdateFeed.isNewer("latest", "0.1.0"))
    }

    @Test fun ignoresDraftsAndDownloadsFromElsewhere() {
        assertNull(UpdateFeed.newer(release("v0.2.0", draft = true), "0.1.0"))
        assertNull(UpdateFeed.newer(release("v0.2.0", apkUrl = "https://evil.example/Seam-0.2.0-android.apk"), "0.1.0"))
        assertNull(UpdateFeed.newer(release("v0.2.0", apkUrl = "https://github.com/someone/seam/releases/download/v0.2.0/Seam-0.2.0-android.apk"), "0.1.0"))
        assertNull(UpdateFeed.newer("not json", "0.1.0"))
    }

    @Test fun findsTheChecksumForTheApk() {
        val a = "a".repeat(64); val b = "b".repeat(64)
        val sums = "$a  Seam-0.1.1-android.apk\n$b  Seam-0.1.1-mac.zip\n"
        assertEquals(a, UpdateFeed.sumFor(sums, "Seam-0.1.1-android.apk"))
        assertEquals(b, UpdateFeed.sumFor(sums, "Seam-0.1.1-mac.zip"))
        assertNull(UpdateFeed.sumFor(sums, "other.apk"))
        assertNull(UpdateFeed.sumFor("xyz  Seam-0.1.1-android.apk", "Seam-0.1.1-android.apk"))
    }

    @Test fun ignoresPrereleasesAndReleasesMissingAFile() {
        assertNull(UpdateFeed.newer(release("v0.2.0").replace("\"prerelease\":false", "\"prerelease\":true"), "0.1.0"))
        val noSums = """{"tag_name":"v0.2.0","assets":[{"name":"Seam-0.2.0-android.apk","browser_download_url":"https://github.com/aveterosystems/seam/releases/download/v0.2.0/Seam-0.2.0-android.apk"}]}"""
        assertNull(UpdateFeed.newer(noSums, "0.1.0"))
        assertNull(UpdateFeed.newer("""{"tag_name":"v0.2.0","assets":[]}""", "0.1.0"))
    }

    @Test fun sr_17_16_onlyThisTagsDownload() {
        val other = "https://github.com/aveterosystems/seam/releases/download/v0.1.9/Seam-0.2.0-android.apk"
        assertNull(UpdateFeed.newer(release("v0.2.0", apkUrl = other), "0.1.0"))
        assertNull(UpdateFeed.newer(release("v0.2.0", apkUrl = "https://github.com/aveterosystems/seam/releases/download/v0.2.0/../Seam-0.2.0-android.apk"), "0.1.0"))
        assertNull(UpdateFeed.newer(release("v0.2.0", apkUrl = "https://github.com/aveterosystems/seam/releases/download/v0.2.0/Seam-0.2.0-android.apk?x=1"), "0.1.0"))
    }

    @Test fun oddVersionsFailClosed() {
        assertFalse(UpdateFeed.isNewer("v99999999999.0.0", "0.1.0"))
        assertFalse(UpdateFeed.isNewer("V0.2.0", "0.1.0"))
        assertFalse(UpdateFeed.isNewer("v0.2.0", "not a version"))
    }

    @Test fun checksumLinesMatchTheExactName() {
        val a = "a".repeat(64)
        assertEquals(a, UpdateFeed.sumFor("$a *Seam-0.1.1-android.apk\r\n", "Seam-0.1.1-android.apk"))
        assertNull(UpdateFeed.sumFor("$a  x-Seam-0.1.1-android.apk", "Seam-0.1.1-android.apk"))
        assertNull(UpdateFeed.sumFor("$a  Seam-0.1.1-android.apk.idsig", "Seam-0.1.1-android.apk"))
    }

    private fun nightly(tag: String, version: String, apkUrl: String? = null) = """
        {"tag_name":"$tag","draft":false,"prerelease":true,"assets":[
          {"name":"Seam-$version-android.apk","browser_download_url":"${apkUrl ?: "https://github.com/aveterosystems/seam/releases/download/$tag/Seam-$version-android.apk"}"},
          {"name":"SHA256SUMS","browser_download_url":"https://github.com/aveterosystems/seam/releases/download/$tag/SHA256SUMS"}]}
    """.trimIndent()

    @Test fun aNightlySitsAfterItsReleaseAndBeforeTheNext() {
        assertTrue(UpdateFeed.isNewer("0.3.1-nightly.202611030200", "0.3.0"))
        assertTrue(UpdateFeed.isNewer("0.3.1-nightly.202611040200", "0.3.1-nightly.202611030200"))
        assertTrue("the release beats its nightlies", UpdateFeed.isNewer("v0.3.1", "0.3.1-nightly.202611040200"))
        assertTrue(UpdateFeed.isNewer("0.4.0", "0.3.1-nightly.202611040200"))
        assertFalse(UpdateFeed.isNewer("0.3.1-nightly.202611040200", "0.3.1"))
        assertFalse(UpdateFeed.isNewer("0.3.1-beta.1", "0.3.0"))
    }

    private fun feed(vararg releases: String) = releases.joinToString(",", "[", "]")

    @Test fun nightlyBuildsOfferTheNewestOfReleasesAndNightlies() {
        val rolling = """{"tag_name":"nightly","draft":false,"prerelease":true,"assets":[{"name":"appcast.xml","browser_download_url":"https://github.com/aveterosystems/seam/releases/download/nightly/appcast.xml"}]}"""
        val n = nightly("nightly-202611040200", "0.3.1-nightly.202611040200")
        val r = UpdateFeed.newest(feed(rolling, n, release("v0.3.0")), "0.3.0")!!
        assertEquals("0.3.1-nightly.202611040200", r.version)
        assertTrue(r.apkUrl.endsWith("/nightly-202611040200/Seam-0.3.1-nightly.202611040200-android.apk"))
        assertEquals("a release after it wins", "0.3.1", UpdateFeed.newest(feed(release("v0.3.1"), n), "0.3.0")!!.version)
        assertNull("nothing newer", UpdateFeed.newest(feed(n, rolling), "0.3.1-nightly.202611040200"))
        assertNull("a stable check never offers a nightly", UpdateFeed.newer(n, "0.3.0"))
    }

    @Test fun aNightlyMustComeFromItsOwnTag() {
        val elsewhere = "https://github.com/aveterosystems/seam/releases/download/nightly-1/Seam-0.3.1-nightly.2-android.apk"
        assertNull(UpdateFeed.newest("[${nightly("nightly-2", "0.3.1-nightly.2", apkUrl = elsewhere)}]", "0.3.0"))
        assertNull("a pre-release with a release tag isn't a nightly", UpdateFeed.newest("[${nightly("v0.3.1", "0.3.1-nightly.2")}]", "0.3.0"))
        assertNull(UpdateFeed.newest("not json", "0.3.0"))
    }
}
