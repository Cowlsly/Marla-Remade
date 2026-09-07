package com.vayunmathur.library.util

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [copyEntriesFrom], the decision logic behind
 * [DataStoreUtils.seedDeviceProtectedStorage].
 *
 * The device-protected store is a separate file from the default one with no migration
 * between them, so an app that flips to `deviceProtected = true` used to read an empty store
 * and silently do nothing. The copy is what fixes that, and each property asserted here is
 * load-bearing: it must not move (the credential-encrypted original stays authoritative), it
 * must not copy anything the caller did not name (device-protected storage is readable
 * without the user's passcode), and it must be safe to re-run, because
 * `LOCKED_BOOT_COMPLETED` fires when an app leaves the stopped state and not only at a real
 * boot.
 *
 * These run against in-memory [androidx.datastore.preferences.core.Preferences] rather than
 * real stores. That is not only for speed — see [DataStoreSeedFileTest] for why a file-backed
 * suite cannot cover this ground on Windows.
 */
class DataStoreSeedTest {

    private val identity = byteArrayOf(1, 2, 3, 4)

    private fun sourceWithEverything() = mutablePreferencesOf(
        longPreferencesKey("userid") to 42L,
        byteArrayPreferencesKey("ff_pqcKemPub") to identity,
        stringPreferencesKey("display_name") to "ada",
        booleanPreferencesKey("tracking_enabled") to true,
    )

    @Test fun copiesNamedKeysWhenTargetIsEmpty() {
        val target = mutablePreferencesOf()

        val copied = target.copyEntriesFrom(
            sourceWithEverything(),
            setOf("userid", "ff_pqcKemPub", "display_name", "tracking_enabled"),
            overwrite = false,
        )

        assertEquals(4, copied)
        assertEquals(42L, target[longPreferencesKey("userid")])
        assertEquals("ada", target[stringPreferencesKey("display_name")])
        assertEquals(true, target[booleanPreferencesKey("tracking_enabled")])
        // Each value keeps the type it was stored with, so a byte-array blob survives as a
        // byte array rather than arriving as some other preference type.
        assertTrue(identity.contentEquals(target[byteArrayPreferencesKey("ff_pqcKemPub")]))
    }

    /** The source is only read, never cleared: this is a copy, not the move Room does. */
    @Test fun leavesTheSourceIntact() {
        val source = sourceWithEverything()

        mutablePreferencesOf().copyEntriesFrom(source, setOf("userid"), overwrite = false)

        assertEquals(42L, source[longPreferencesKey("userid")])
        assertEquals(4, source.asMap().size)
    }

    @Test fun doesNotDisturbAValueTheTargetAlreadyHas() {
        val target = mutablePreferencesOf(longPreferencesKey("userid") to 7L)

        val copied = target.copyEntriesFrom(
            sourceWithEverything(),
            setOf("userid"),
            overwrite = false,
        )

        assertEquals(0, copied)
        assertEquals(7L, target[longPreferencesKey("userid")])
    }

    /**
     * Opt-in for values that must track later changes. findfamily needs this for the identity
     * keys: credential-encrypted storage is authoritative and can legitimately re-mint them,
     * and a mirror that kept the older copy would publish under an identity peers do not
     * recognise.
     */
    @Test fun overwriteReplacesAValueTheTargetAlreadyHas() {
        val target = mutablePreferencesOf(longPreferencesKey("userid") to 7L)

        val copied = target.copyEntriesFrom(
            sourceWithEverything(),
            setOf("userid"),
            overwrite = true,
        )

        assertEquals(1, copied)
        assertEquals(42L, target[longPreferencesKey("userid")])
    }

    @Test fun doesNothingWhenTheSourceHasNothingToGive() {
        val target = mutablePreferencesOf(longPreferencesKey("userid") to 7L)

        val copied = target.copyEntriesFrom(
            mutablePreferencesOf(),
            setOf("userid"),
            overwrite = false,
        )

        assertEquals(0, copied)
        assertEquals(7L, target[longPreferencesKey("userid")])
    }

    /**
     * The allowlist is the whole privacy story. Anything the caller did not name has to stay
     * behind the passcode, even when it is sitting right next to a key that is being copied.
     */
    @Test fun copiesOnlyTheKeysTheCallerNamed() {
        val source = mutablePreferencesOf(
            longPreferencesKey("userid") to 42L,
            stringPreferencesKey("home_address") to "221B Baker Street",
            byteArrayPreferencesKey("share_link_key") to byteArrayOf(9, 9, 9),
        )
        val target = mutablePreferencesOf()

        val copied = target.copyEntriesFrom(source, setOf("userid"), overwrite = false)

        assertEquals(1, copied)
        assertEquals(42L, target[longPreferencesKey("userid")])
        assertNull(target[stringPreferencesKey("home_address")])
        assertNull(target[byteArrayPreferencesKey("share_link_key")])
    }

    @Test fun anEmptyAllowlistCopiesNothing() {
        val target = mutablePreferencesOf()

        assertEquals(0, target.copyEntriesFrom(sourceWithEverything(), emptySet(), false))
        assertTrue(target.asMap().isEmpty())
    }

    /** Re-running must be a no-op, not a rewrite: LOCKED_BOOT_COMPLETED is not once-per-boot. */
    @Test fun repeatedSeedsAreIdempotent() {
        val source = sourceWithEverything()
        val target = mutablePreferencesOf()
        val keys = setOf("userid")

        assertEquals(1, target.copyEntriesFrom(source, keys, overwrite = false))
        assertEquals(0, target.copyEntriesFrom(source, keys, overwrite = false))
        assertEquals(0, target.copyEntriesFrom(source, keys, overwrite = false))
        assertEquals(42L, target[longPreferencesKey("userid")])
    }
}

/**
 * One end-to-end pass of [DataStoreUtils.copyFrom] over real files, to prove the plumbing
 * around [copyEntriesFrom] is wired up: that the source snapshot is actually read and the
 * result actually persisted.
 *
 * WHY THERE IS ONLY ONE TEST HERE, AND WHY IT WRITES EACH FILE EXACTLY ONCE. DataStore
 * commits by writing a `.tmp` and calling `File.renameTo` on it. On Windows `renameTo` fails
 * when the destination already exists, so the *second* write to any given store throws
 * `IOException: Unable to rename ...` — with a message blaming multiple DataStore instances,
 * which is a red herring. That makes a file-backed suite unusable here: fixtures needing more
 * than one key, and any assertion about overwriting, fail for reasons that have nothing to do
 * with the code under test. Hence the decision logic is tested in memory above. Do not add
 * a second write to either store below.
 */
class DataStoreSeedFileTest {

    @Test fun copiesAcrossRealStoresAndLeavesTheSourceIntact() = runBlocking {
        val dir = Files.createTempDirectory("datastoreutils").toFile()
        fun store(name: String) =
            DataStoreUtils(PreferenceDataStoreFactory.create { dir.resolve("$name.preferences_pb") })

        val ce = store("ce")
        val de = store("de")
        ce.setLong("userid", 42L)

        val copied = de.copyFrom(ce, listOf("userid"), overwrite = false)

        assertEquals(1, copied)
        assertEquals(42L, de.getLongAwait("userid"))
        assertEquals(42L, ce.getLongAwait("userid"))
    }
}
