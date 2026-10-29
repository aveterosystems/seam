// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import com.avetero.seam.session.SessionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewPairingTest {
    @Test fun firstPairingShowsTheChecklist() {
        assertTrue(isNewPairing(null, "111 222", SessionState.Connected("Mac")))
    }

    @Test fun sr_11_06_aFailedRePairBackToTheOldMacDoesNot() {
        assertFalse(isNewPairing("111 222", "111 222", SessionState.Connecting("Mac")))
    }

    @Test fun replacingTheMacDoes() {
        assertTrue(isNewPairing("111 222", "333 444", SessionState.Idle("Other Mac")))
    }

    @Test fun aFailureDoesNot() {
        assertFalse(isNewPairing(null, null, SessionState.Failed("no")))
    }
}
