package com.vayunmathur.passwords.util

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.data.PasswordRepository
import com.vayunmathur.passwords.sync.KdbxSyncScheduler
import com.vayunmathur.passwords.sync.KdbxSyncSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Passwords app.
 *
 * Owns:
 *  - Central TOTP ticker as a single shared [StateFlow] (replaces per-row
 *    [androidx.compose.runtime.LaunchedEffect] that ticked once per row).
 *  - Bitwarden-style CSV import (content-resolver read + parse + per-row upsert).
 *  - Edit-form draft state for [Password] (decoupled from composable lifetime).
 *  - Copy-to-clipboard actions, with a [SharedFlow] for one-shot "copied" events.
 *
 * Uses [PasswordRepository] for all persistence. Exposes the password list
 * as a [StateFlow] and provides Composable helpers for per-row reads and
 * editable bindings.
 */
class PasswordsViewModel(
    application: Application,
    private val repository: PasswordRepository,
) : AndroidViewModel(application), PasswordsActions {

    /** Exposed so SettingsPage can build KdbxBackupFormat without exposing DAOs. */
    fun buildBackupFormat(): com.vayunmathur.library.util.BackupFormat =
        KdbxBackupFormat(repository)

    // -- Data -------------------------------------------------------------

    val passwords: StateFlow<List<Password>> = repository.passwords.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val passkeys: StateFlow<List<Passkey>> = repository.passkeys.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun deletePasskey(passkey: Passkey) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deletePasskey(passkey)
            requestSync()
        }
    }

    fun upsert(password: Password, onSaved: ((Long) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val newId = repository.upsertPassword(password)
            onSaved?.invoke(newId)
            requestSync()
        }
    }

    override fun delete(password: Password) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deletePassword(password)
            requestSync()
        }
    }

    private suspend fun requestSync() {
        val ctx = getApplication<Application>()
        if (KdbxSyncSettings.enabled(ctx)) KdbxSyncScheduler.scheduleDebounced(ctx)
    }

    /**
     * Returns a [State] tracking the password with [initialId]. If not yet
     * loaded (or absent), returns [default]. Recomposes when the underlying
     * list changes.
     */
    @Composable
    fun passwordState(initialId: Long, default: () -> Password = { Password() }): State<Password> {
        val list by passwords.collectAsState()
        return remember(initialId) {
            derivedStateOf { list.firstOrNull { it.id == initialId } ?: default() }
        }
    }

    // -- TOTP ticker ------------------------------------------------------

    /**
     * Wall-clock millis, ticked once per second. The flow is shared across
     * every TOTP row so we don't allocate a coroutine per row. It is
     * stopped via [SharingStarted.WhileSubscribed] when no composable is
     * observing, matching the previous per-row `LaunchedEffect` behavior
     * which cancelled on leaving composition.
     */
    val tickerFlow: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1000)
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(1000),
        System.currentTimeMillis(),
    )

    // -- Clipboard --------------------------------------------------------

    private val _copyEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** Emits a short label (e.g. "Password copied") for snackbar feedback. */
    override val copyEvents: SharedFlow<String> = _copyEvents.asSharedFlow()

    // Default arguments live on the PasswordsActions declaration; an override may not
    // repeat them.
    override fun copyToClipboard(label: String, text: String, feedback: String?) {
        val ctx = getApplication<Application>()
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        // Tells the system UI (and any keyboard with a clipboard history) not to show this
        // in the clear. Android 13+; older releases have no way to say it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        if (feedback != null) {
            viewModelScope.launch { _copyEvents.emit(feedback) }
        }
    }

    // -- Edit-form draft --------------------------------------------------

    private val _draft = MutableStateFlow<Password?>(null)
    /** Currently-edited password draft, or null if no edit in progress. */
    val draft: StateFlow<Password?> = _draft.asStateFlow()

    /**
     * Initialize the draft from the persisted [seed] the first time the edit
     * page is shown for a given id. Subsequent calls with the same id are
     * ignored so user edits are not clobbered.
     */
    fun initDraft(seed: Password) {
        val current = _draft.value
        if (current == null || current.id != seed.id) {
            _draft.value = seed
        }
    }

    override fun updateDraft(transform: (Password) -> Password) {
        _draft.value = _draft.value?.let(transform)
    }

    fun clearDraft() {
        _draft.value = null
    }

    /**
     * Persists the current draft. For new rows, the assigned id is reported
     * via [onSaved]. Clears the draft once enqueued.
     */
    override fun saveDraft(onSaved: ((Long) -> Unit)?) {
        val current = _draft.value ?: return
        upsert(current, onSaved)
        _draft.value = null
    }

    // -- CSV import -------------------------------------------------------

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()

    fun importCsv(uri: Uri, source: ImportSource = ImportSource.BITWARDEN) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            _importing.value = true
            _importMessage.value = null
            // Best-effort: persist read access for potential re-reads. Not
            // required for this one-shot import, so failure must not abort it.
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {}
            try {
                val result = withContext(Dispatchers.IO) {
                    importCsvFromUri(ctx.contentResolver, uri, source)
                }
                _importMessage.value =
                    "Imported ${result.inserted} rows, skipped ${result.skipped} rows"
            } catch (e: Exception) {
                _importMessage.value = "Import failed: ${e.message}"
            } finally {
                _importing.value = false
            }
        }
    }

    private data class ImportResult(val inserted: Int, val skipped: Int)

    private suspend fun importCsvFromUri(
        contentResolver: ContentResolver,
        uri: Uri,
        source: ImportSource,
    ): ImportResult {
        val text = contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.bufferedReader().readText()
        } ?: throw Exception("Unable to open selected file")

        val rows = parseCsv(text)
        if (rows.isEmpty()) return ImportResult(0, 0)

        val header = rows.first().map { it.trim().lowercase() }
        fun findCol(vararg names: String): Int =
            names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } } ?: -1

        val nameIdx = findCol(*source.nameHeaders)
        val usernameIdx = findCol(*source.usernameHeaders)
        val passwordIdx = findCol(*source.passwordHeaders)
        val urlIdx = findCol(*source.urlHeaders)
        val totpIdx = findCol(*source.totpHeaders)
        val emailIdx = findCol(*source.emailHeaders)
        val noteIdx = findCol(*source.noteHeaders)
        val typeIdx = findCol(*source.typeHeaders)

        var inserted = 0
        var skipped = 0

        for (row in rows.drop(1)) {
            if (row.all { it.isBlank() }) continue
            try {
                fun col(idx: Int) = if (idx in row.indices) row[idx] else ""
                val rawName = col(nameIdx)
                val rawUrl = col(urlIdx)
                var name = rawName.ifEmpty { rawUrl }
                val username = col(usernameIdx)
                val email = col(emailIdx)
                val password = col(passwordIdx)
                val note = col(noteIdx)
                var totp = col(totpIdx).takeIf { it.isNotEmpty() }

                // Prefix the entry name with its type for non-login entries
                // (e.g. "sshKey: Tyche"), so specialized items stay identifiable.
                val type = col(typeIdx).trim()
                if (type.isNotEmpty() && !type.equals("login", ignoreCase = true)) {
                    name = if (name.isEmpty()) type else "$type: $name"
                }

                if (totp != null && totp.startsWith("otpauth://")) {
                    val match = Regex("[?&]secret=([^&]+)").find(totp)
                    totp = match?.groupValues?.get(1) ?: totp
                }

                val urlSeparators =
                    if (source.splitUrlsOnComma) charArrayOf(',', ';', '\n', '\r')
                    else charArrayOf(';', '\n', '\r')
                val websites = rawUrl.split(*urlSeparators)
                    .mapNotNull { it.trim().takeIf(String::isNotEmpty) }

                repository.upsertPassword(
                    Password(
                        name = name,
                        username = username,
                        email = email,
                        password = password,
                        note = note,
                        totpSecret = totp,
                        websites = websites,
                    ),
                )
                inserted++
            } catch (_: Exception) {
                skipped++
            }
        }

        return ImportResult(inserted, skipped)
    }

    /**
     * Parses CSV text into rows of fields. Respects text qualifiers (double
     * quotes), escaped quotes (""), and quoted fields spanning multiple lines
     * (e.g. JSON stored in a note column). Handles both LF and CRLF line endings.
     */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                        current.append('"')
                        i++
                    }
                    c == '"' -> inQuotes = false
                    else -> current.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> {
                    row.add(current.toString())
                    current.setLength(0)
                }
                c == '\r' -> {} // handled together with '\n'
                c == '\n' -> {
                    row.add(current.toString())
                    current.setLength(0)
                    rows.add(row)
                    row = mutableListOf()
                }
                else -> current.append(c)
            }
            i++
        }
        // Flush a trailing field/row when the file does not end with a newline.
        if (current.isNotEmpty() || row.isNotEmpty()) {
            row.add(current.toString())
            rows.add(row)
        }
        return rows
    }
}

/** Factory for constructing [PasswordsViewModel] with a [PasswordRepository]. */
class PasswordsViewModelFactory(
    private val application: Application,
    private val repository: PasswordRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PasswordsViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return PasswordsViewModel(application, repository) as T
    }
}
