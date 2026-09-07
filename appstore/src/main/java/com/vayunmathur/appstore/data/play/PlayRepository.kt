package com.vayunmathur.appstore.data.play

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.data.models.PlayFile
import com.aurora.gplayapi.helpers.AuthHelper
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.searchCandidate
import com.vayunmathur.appstore.domain.SearchRanking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private val Context.authDataStore by preferencesDataStore(name = "play_auth")
private val PLAY_AUTH_JSON_KEY = stringPreferencesKey("play_auth_json")
private val PLAY_AUTH_DISPENSED_AT_KEY = longPreferencesKey("play_auth_dispensed_at")

/**
 * How long a dispensed anonymous account is trusted before it is replaced.
 *
 * The dispenser hands out shared accounts that Google eventually invalidates,
 * and a dead one fails silently - Play calls just start erroring and the app
 * falls back to scraping. Cycling on age keeps that from being the way we find
 * out.
 */
private const val PLAY_AUTH_MAX_AGE_MS = 12L * 60 * 60 * 1000

sealed interface PlayAuthState {
    data object Idle : PlayAuthState
    data object Authenticating : PlayAuthState
    data class Authenticated(val authData: AuthData) : PlayAuthState
    data class Error(val message: String) : PlayAuthState
}

/**
 * Everything that needs a Play session, behind one object.
 *
 * This used to be spread across the ViewModel — the anonymous account, its persistence,
 * its expiry, the retry-once-on-a-dead-account dance, and every call site that had to
 * remember to use it. Keeping it here means a caller can ask for search results without
 * knowing that an account has to be dispensed first, and the "cycle a dead account and
 * try again" rule exists in exactly one place.
 */
class PlayRepository(private val context: Context) {

    private val anonAuthRepo = AnonymousAuthRepository()
    private val httpClient = PlayHttpClient()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _authState = MutableStateFlow<PlayAuthState>(PlayAuthState.Idle)
    val authState: StateFlow<PlayAuthState> = _authState.asStateFlow()

    private var cachedAuthData: AuthData? = null

    /** When [cachedAuthData] was dispensed, for age-based cycling. */
    private var dispensedAt: Long = 0L

    /**
     * Serialises dispensing.
     *
     * Browse, the update check and an install can all discover a dead account within a
     * few milliseconds of each other; without this each would dispense its own, and the
     * last writer would win while the others' accounts leaked.
     */
    private val authMutex = Mutex()

    /** Reload a persisted account, if it is young enough and still alive. */
    suspend fun restore() {
        try {
            val prefs = context.authDataStore.data.first()
            val jsonStr = prefs[PLAY_AUTH_JSON_KEY]?.takeIf { it.isNotBlank() } ?: return
            val storedAt = prefs[PLAY_AUTH_DISPENSED_AT_KEY] ?: 0L
            if (System.currentTimeMillis() - storedAt > PLAY_AUTH_MAX_AGE_MS) {
                // Past its useful life; ensureAuth will dispense a new one rather than
                // spend a round-trip proving this is dead.
                invalidate()
                return
            }
            val authData = runCatching {
                json.decodeFromString(AuthData.serializer(), jsonStr)
            }.getOrNull() ?: return
            if (isValid(authData)) {
                cachedAuthData = authData
                dispensedAt = storedAt
                _authState.value = PlayAuthState.Authenticated(authData)
            } else {
                invalidate()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Run a Play call with whatever account is already available, cycling it if it has
     * died.
     *
     * Returns null when there is no account to use or both attempts failed, so callers
     * keep their existing fallback. Deliberately does *not* dispense when nothing is
     * cached: being signed out is the caller's cue to use the scraping path, and
     * dispensing here would put a retrying network call in front of every keystroke of
     * search.
     */
    suspend fun <T> withApi(block: suspend (PlayStoreApi) -> T): T? {
        val cached = cachedAuthData ?: return null
        try {
            return block(PlayStoreApi(cached, httpClient))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Play call failed; cycling anonymous account", e)
        }

        invalidate()
        val fresh = ensureAuth().getOrNull() ?: return null
        return try {
            block(PlayStoreApi(fresh, httpClient))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Play call failed again after cycling", e)
            null
        }
    }

    /** Like [withApi], but dispenses an account first if there isn't one. */
    suspend fun <T> withFreshApi(block: suspend (PlayStoreApi) -> T): T? {
        if (cachedAuthData == null && ensureAuth().isFailure) return null
        return withApi(block)
    }

    suspend fun ensureAuth(): Result<AuthData> = authMutex.withLock {
        cachedAuthData?.let { cached ->
            if (System.currentTimeMillis() - dispensedAt <= PLAY_AUTH_MAX_AGE_MS && isValid(cached)) {
                return@withLock Result.success(cached)
            }
            invalidate()
        }

        _authState.value = PlayAuthState.Authenticating

        // Use the same curated Pixel 9a profile Aurora + its dispenser are tuned to,
        // so the dispensed anonymous accounts and gplayapi's DFE headers stay consistent.
        val deviceProps = DeviceInfoProvider.auroraProfile(context)
        val result = anonAuthRepo.ensureAuthData(context, deviceProps)
        val authData = result.getOrElse { err ->
            _authState.value = PlayAuthState.Error(anonAuthRepo.errorMessage(err))
            return@withLock Result.failure(err)
        }

        cachedAuthData = authData
        dispensedAt = System.currentTimeMillis()
        _authState.value = PlayAuthState.Authenticated(authData)
        persist(authData)
        Result.success(authData)
    }

    // --- Convenience wrappers -----------------------------------------------------

    /**
     * Play's search: more pages while the answer is unsatisfying, then the first word.
     *
     * A phrase Play does not recognise comes back either as filler or as nothing at all —
     * the reason "Google Maps" returned Facebook and Netflix and "chase mobile" returned
     * nothing (issue #595). Neither is worth showing, so the search reads on, and failing
     * that asks the broader question. [SearchRanking] decides what any of it is worth, and
     * is also what decides whether an answer counts as one.
     */
    suspend fun search(query: String): List<UnifiedApp> {
        val answered: (List<UnifiedApp>) -> Boolean = { results ->
            results.any { SearchRanking.score(it.searchCandidate(), query) != null }
        }
        val direct = withApi { it.search(query, SEARCH_MAX_PAGES, answered) }.orEmpty()
        val tokens = SearchRanking.tokenize(query)
        if (tokens.size < 2 || answered(direct)) return direct
        val broader = withApi { it.search(tokens.first(), SEARCH_MAX_PAGES, answered) }.orEmpty()
        return (direct + broader).distinctBy { it.packageName }
    }

    suspend fun details(packageName: String): UnifiedApp? =
        withApi { it.getDetails(packageName) }

    suspend fun details(packageNames: List<String>): List<UnifiedApp> =
        withFreshApi { it.getDetails(packageNames) }.orEmpty()

    suspend fun topChart(): List<UnifiedApp> =
        withFreshApi { it.topChart() }.orEmpty()

    suspend fun homeClusters(): List<PlayCluster> =
        withFreshApi { it.homeClusters() }.orEmpty()

    /** Throws, unlike the readers above: an install has no silent fallback. */
    suspend fun purchase(
        packageName: String,
        versionCode: Long,
        offerType: Int,
        certHash: String?,
    ): List<PlayFile> {
        val authData = ensureAuth().getOrThrow()
        return PlayStoreApi(authData, httpClient)
            .purchase(context, packageName, versionCode, offerType, certHash)
    }

    // --- Internals ----------------------------------------------------------------

    private suspend fun isValid(authData: AuthData): Boolean = withContext(Dispatchers.IO) {
        try {
            AuthHelper.using(httpClient).isValid(authData)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Forget the current anonymous account so the next Play call dispenses a
     * fresh one. Clears the persisted copy too, or a restart would restore the
     * dead credentials.
     */
    private suspend fun invalidate() {
        cachedAuthData = null
        dispensedAt = 0L
        _authState.value = PlayAuthState.Idle
        try {
            context.authDataStore.edit { prefs ->
                prefs.remove(PLAY_AUTH_JSON_KEY)
                prefs.remove(PLAY_AUTH_DISPENSED_AT_KEY)
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun persist(authData: AuthData) {
        try {
            val jsonStr = json.encodeToString(AuthData.serializer(), authData)
            context.authDataStore.edit { prefs ->
                prefs[PLAY_AUTH_JSON_KEY] = jsonStr
                prefs[PLAY_AUTH_DISPENSED_AT_KEY] = dispensedAt
            }
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val TAG = "PlayRepository"

        /**
         * How far into Play's search stream to read before giving up on a query. Only
         * reached when the pages so far hold nothing that answers it.
         */
        const val SEARCH_MAX_PAGES = 3
    }
}
