package com.vayunmathur.email.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * One-time backfill that computes [EmailMessage.peekContent] for rows that have a
 * body but no stored preview yet — rows persisted before the `peekContent` column
 * existed (default `''`, added by `MIGRATION_19_20`). The snippet is derived with
 * [previewText], whose HTML strip can't be expressed in SQL, so it runs in Kotlin.
 * Called from `MainActivity.onCreate`; a no-op once every bodied row has a preview.
 */
object PeekContentBackfill {

    fun runIfNeeded(scope: CoroutineScope, context: Context) {
        scope.launch(Dispatchers.IO) {
            val dao = EmailRepository.get(context).getDatabase().emailDao()
            var batch = dao.getRowsWithEmptyPeek()
            var fixed = 0
            while (batch.isNotEmpty()) {
                for (row in batch) {
                    val peek = row.previewText(PEEK_LEN)
                    // Guard against an all-whitespace/empty preview: writing '' would
                    // leave the row eligible again and loop forever. Fall back to a
                    // single space so the row is marked done.
                    dao.updatePeekContent(
                        row.accountEmail,
                        row.folderName,
                        row.id,
                        peek.ifEmpty { " " },
                    )
                    fixed++
                }
                batch = dao.getRowsWithEmptyPeek()
            }
            if (fixed > 0) Log.d("PeekContentBackfill", "Backfilled $fixed row(s)")
        }
    }
}
