// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipRulesTest {
    @Test fun sendsNewCopiesOnce() {
        val r = ClipRules()
        assertEquals("a", r.outgoing("a"))
        assertNull("the same copy seen twice", r.outgoing("a"))
        assertEquals("b", r.outgoing("b"))
        assertEquals("copying a again later still sends it", "a", r.outgoing("a"))
    }

    @Test fun neverEchoesWhatTheMacSent() {
        val r = ClipRules()
        assertEquals("from mac", r.incoming("from mac"))
        assertNull("writing it here looks like a copy: don't bounce it back", r.outgoing("from mac"))
        assertEquals("mine", r.outgoing("mine"))
    }

    @Test fun skipsEmptyAndOversized() {
        val r = ClipRules(maxBytes = 4)
        assertNull(r.outgoing(null))
        assertNull(r.outgoing(""))
        assertNull(r.outgoing("12345"))
        assertNull(r.incoming("12345"))
        assertNull("bytes, not characters", r.outgoing("ééé"))
        assertEquals("1234", r.outgoing("1234"))
    }

    @Test fun anExplicitSendAlwaysGoes() {
        val r = ClipRules()
        r.incoming("x")
        assertEquals("x", r.forced("x"))
        assertNull(r.forced(""))
        assertTrue(ClipRules.MAX_BYTES < 256 * 1024)
    }
}
