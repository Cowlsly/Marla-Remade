package com.vayunmathur.files.data.saf

import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.files.R
import com.vayunmathur.files.platform.FileBrowserItem
import com.vayunmathur.files.platform.FilesViewModel
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconFile
import com.vayunmathur.library.ui.IconFolder
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The system Storage Access Framework picker, standing in for DocumentsUI when Files is the
 * privileged system documents UI (MAOS). Reuses [FilesViewModel] for browsing and returns
 * ExternalStorageProvider-backed URIs (see [StorageUris]) so results are queryable and, when
 * privileged, persistable.
 *
 * Handles OPEN_DOCUMENT, GET_CONTENT, OPEN_DOCUMENT_TREE and CREATE_DOCUMENT. The component is
 * disabled on non-privileged installs (see FilesApp), so this never runs there.
 */
class DocumentPickerActivity : ComponentActivity() {

    private enum class Mode { OPEN, GET_CONTENT, TREE, CREATE }

    private val vm: FilesViewModel by viewModels()

    private lateinit var mode: Mode
    private var allowMultiple = false
    private var mimeFilters: List<String> = emptyList()
    private var initialName: String = "untitled"

    /** True when Files holds MANAGE_DOCUMENTS, i.e. it is the system documents UI. */
    private val privileged: Boolean
        get() = checkSelfPermission(android.Manifest.permission.MANAGE_DOCUMENTS) ==
            PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mode = when (intent?.action) {
            Intent.ACTION_OPEN_DOCUMENT -> Mode.OPEN
            Intent.ACTION_GET_CONTENT -> Mode.GET_CONTENT
            Intent.ACTION_OPEN_DOCUMENT_TREE -> Mode.TREE
            Intent.ACTION_CREATE_DOCUMENT -> Mode.CREATE
            else -> {
                setResult(RESULT_CANCELED)
                finish()
                return
            }
        }
        allowMultiple = (mode == Mode.OPEN || mode == Mode.GET_CONTENT) &&
            intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        // EXTRA_MIME_TYPES wins over the bare type when present, matching SAF semantics.
        mimeFilters = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList()
            ?: intent.type?.let { listOf(it) }
            ?: emptyList()
        initialName = intent.getStringExtra(Intent.EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "untitled"

        enableEdgeToEdge()
        setContent { DynamicTheme { PickerRoot() } }
    }

    @Composable
    private fun PickerRoot() {
        val granted by vm.isFilesGranted.collectAsState()
        LaunchedEffect(granted) { if (granted) vm.openInternalStorage() }
        if (!granted) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.grant_all_files_access))
            }
            return
        }
        PickerContent()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PickerContent() {
        val currentDir by vm.currentDirectory.collectAsState()
        val entries by vm.entries.collectAsState()
        val (dirs, files) = entries
        val root = vm.rootDirectory

        val visibleFiles = remember(files, mode) {
            if (mode == Mode.TREE) emptyList()
            else files.filter { it.realFile?.let { f -> StorageUris.matchesMime(f, mimeFilters) } == true }
        }

        val selected = remember { mutableStateMapOf<String, File>() }
        var createName by remember { mutableStateOf(initialName) }

        val atRoot = currentDir.absolutePath == root.absolutePath
        fun navUp() {
            if (atRoot) cancel() else vm.navigateTo(currentDir.parentFile ?: root)
        }
        BackHandler { navUp() }

        AppScaffold(
            title = titleFor(mode),
            modifier = Modifier.imePadding(),
            onNavigateBack = { navUp() },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Text(
                    text = currentDir.absolutePath,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()

                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(dirs, key = { "d:" + it.key }) { dir ->
                        PickerRow(
                            item = dir,
                            checked = false,
                            onClick = { dir.realFile?.let { vm.navigateTo(it) } },
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    items(visibleFiles, key = { "f:" + it.key }) { file ->
                        PickerRow(
                            item = file,
                            checked = selected.containsKey(file.key),
                            onClick = {
                                val f = file.realFile
                                if (f != null) {
                                    if (allowMultiple) {
                                        if (selected.containsKey(file.key)) selected.remove(file.key)
                                        else selected[file.key] = f
                                    } else {
                                        returnFiles(listOf(f))
                                    }
                                }
                            },
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }

                BottomBar(
                    mode = mode,
                    selectedCount = selected.size,
                    createName = createName,
                    onCreateNameChange = { createName = it },
                    onUseFolder = { returnTree(currentDir) },
                    onConfirmSelection = { returnFiles(selected.values.toList()) },
                    onCreate = { createAndReturn(currentDir, createName) },
                    onCancel = { cancel() },
                )
            }
        }
    }

    @Composable
    private fun PickerRow(item: FileBrowserItem, checked: Boolean, onClick: () -> Unit) {
        Box(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
            ListItem(
                content = { Text(item.name.ifEmpty { "/" }) },
                leadingContent = { if (item.isDirectory) IconFolder() else IconFile() },
                trailingContent = { if (checked) IconCheck() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }

    @Composable
    private fun BottomBar(
        mode: Mode,
        selectedCount: Int,
        createName: String,
        onCreateNameChange: (String) -> Unit,
        onUseFolder: () -> Unit,
        onConfirmSelection: () -> Unit,
        onCreate: () -> Unit,
        onCancel: () -> Unit,
    ) {
        // A single tap already returns in single-open mode, so no confirm bar is needed there.
        val needsBar = mode == Mode.TREE || mode == Mode.CREATE || allowMultiple
        if (!needsBar) return
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (mode) {
                    Mode.CREATE -> {
                        TextField(
                            value = createName,
                            onValueChange = onCreateNameChange,
                            label = { Text(stringResource(R.string.file_name_label)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = onCreate, enabled = createName.isNotBlank()) {
                            Text(stringResource(R.string.saf_save))
                        }
                    }
                    Mode.TREE -> {
                        TextButton(onClick = onCancel) { Text(stringResource(com.vayunmathur.library.ui.R.string.cancel)) }
                        Box(Modifier.weight(1f))
                        Button(onClick = onUseFolder) { Text(stringResource(R.string.saf_use_this_folder)) }
                    }
                    else -> {
                        TextButton(onClick = onCancel) { Text(stringResource(com.vayunmathur.library.ui.R.string.cancel)) }
                        Box(Modifier.weight(1f))
                        Button(onClick = onConfirmSelection, enabled = selectedCount > 0) {
                            Text(stringResource(R.string.saf_select_count, selectedCount))
                        }
                    }
                }
            }
        }
    }

    private fun titleFor(mode: Mode): String = getString(
        when (mode) {
            Mode.CREATE -> R.string.saf_title_create
            Mode.TREE -> R.string.saf_title_tree
            else -> R.string.saf_title_open
        }
    )

    // ---- Result plumbing ----

    private fun uriForFile(file: File): Uri? =
        if (privileged) StorageUris.documentUriFor(this, file) ?: StorageUris.fileProviderUriFor(this, file)
        else StorageUris.fileProviderUriFor(this, file)

    private fun returnFiles(fileList: List<File>) {
        val uris = fileList.mapNotNull { uriForFile(it) }
        if (uris.isEmpty()) { cancel(); return }
        val persistable = privileged && mode != Mode.GET_CONTENT
        finishWithResult(uris, write = false, persistable = persistable)
    }

    private fun returnTree(dir: File) {
        val uri = if (privileged) StorageUris.treeUriFor(this, dir) else null
        if (uri == null) { cancel(); return }
        finishWithResult(listOf(uri), write = true, persistable = privileged)
    }

    private fun createAndReturn(dir: File, rawName: String) {
        val name = rawName.trim().ifEmpty { return }
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                val target = uniqueChild(dir, name)
                runCatching { target.parentFile?.mkdirs(); target.createNewFile() }
                target.takeIf { it.exists() }
            }
            if (file == null) { cancel(); return@launch }
            val uri = uriForFile(file)
            if (uri == null) { cancel(); return@launch }
            finishWithResult(listOf(uri), write = true, persistable = privileged)
        }
    }

    private fun uniqueChild(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (candidate.exists()) { candidate = File(dir, "$base ($n)$ext"); n++ }
        return candidate
    }

    private fun finishWithResult(uris: List<Uri>, write: Boolean, persistable: Boolean) {
        val result = Intent()
        var flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (write) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (persistable) flags = flags or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        if (uris.size == 1) {
            result.data = uris[0]
        } else {
            val clip = ClipData.newUri(contentResolver, "files", uris[0])
            for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
            result.clipData = clip
        }
        result.addFlags(flags)
        setResult(RESULT_OK, result)
        finish()
    }

    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }
}
