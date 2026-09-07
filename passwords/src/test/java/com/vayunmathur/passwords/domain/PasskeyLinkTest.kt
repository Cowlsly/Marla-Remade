package com.vayunmathur.passwords.domain

import com.vayunmathur.passwords.data.PASSKEY_LINK_DETACHED
import com.vayunmathur.passwords.data.Passkey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PasskeyLinkTest {

    @Test fun blankAndNullBothMeanAuto() {
        assertEquals(PasskeyLink.Auto, PasskeyLink.fromColumn(null))
        assertEquals(PasskeyLink.Auto, PasskeyLink.fromColumn(""))
        assertEquals(PasskeyLink.Auto, PasskeyLink.fromColumn("   "))
    }

    @Test fun columnRoundTrips() {
        assertNull(PasskeyLink.Auto.toColumn())
        assertEquals(PASSKEY_LINK_DETACHED, PasskeyLink.Detached.toColumn())
        val syncId = "0123456789abcdef0123456789abcdef"
        assertEquals(syncId, PasskeyLink.To(syncId).toColumn())
        assertEquals(PasskeyLink.To(syncId), PasskeyLink.fromColumn(syncId))
        assertEquals(PasskeyLink.Detached, PasskeyLink.fromColumn(PASSKEY_LINK_DETACHED))
    }

    @Test fun linkReadsTheEntityColumn() {
        assertEquals(PasskeyLink.Auto, Passkey().link())
        assertEquals(
            PasskeyLink.Detached,
            Passkey(linkedPasswordSyncId = PASSKEY_LINK_DETACHED).link(),
        )
    }
}
