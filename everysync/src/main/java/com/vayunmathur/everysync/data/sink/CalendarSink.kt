package com.vayunmathur.everysync.sink

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import androidx.core.database.getStringOrNull
import com.vayunmathur.everysync.auth.AccountStore
import com.vayunmathur.everysync.model.RemoteEvent

/** A locally-edited event detected via the platform DIRTY/DELETED flags. */
data class LocalEventChange(
    val eventId: Long,
    val syncId: String?,
    val etag: String?,
    val deleted: Boolean,
    val event: RemoteEvent?,
)

/**
 * Creates one CalendarContract calendar per remote collection under the EverySync
 * account and writes events via sync-adapter URIs. The remote UID goes in
 * Events._SYNC_ID, the ETag in SYNC_DATA1 and the href in SYNC_DATA2.
 */
object CalendarSink {
    private const val TAG = "CalendarSink"
    private val ACCOUNT_TYPE = AccountStore.ACCOUNT_TYPE

    private fun Uri.asSyncAdapter(accountName: String): Uri =
        buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
            .build()

    fun getOrCreateCalendarId(
        context: Context,
        accountName: String,
        remoteCalendarId: String,
        displayName: String,
        color: Int?,
    ): Long {
        val existing = mutableListOf<Long>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND ${CalendarContract.Calendars.NAME} = ?",
                arrayOf(accountName, ACCOUNT_TYPE, remoteCalendarId),
                "${CalendarContract.Calendars._ID} ASC",
            )?.use { while (it.moveToNext()) existing += it.getLong(0) }
        } catch (e: Exception) {
            Log.e(TAG, "query calendar failed", e)
        }
        if (existing.isNotEmpty()) {
            // Delete any duplicates created by earlier races; keep the first.
            existing.drop(1).forEach { dupId ->
                try {
                    context.contentResolver.delete(
                        CalendarContract.Calendars.CONTENT_URI.asSyncAdapter(accountName),
                        "${CalendarContract.Calendars._ID} = ?", arrayOf(dupId.toString()),
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "delete duplicate calendar failed", e)
                }
            }
            return existing.first()
        }

        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, accountName)
            put(CalendarContract.Calendars.NAME, remoteCalendarId)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, displayName)
            put(CalendarContract.Calendars.CALENDAR_COLOR, color ?: 0xFF3F51B5.toInt())
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, java.util.TimeZone.getDefault().id)
        }
        return try {
            context.contentResolver.insert(
                CalendarContract.Calendars.CONTENT_URI.asSyncAdapter(accountName), values,
            )?.lastPathSegment?.toLong() ?: -1L
        } catch (e: Exception) {
            Log.e(TAG, "create calendar failed", e)
            -1L
        }
    }

    fun localUidToEtag(context: Context, accountName: String, localCalendarId: Long): Map<String, String?> {
        val out = mutableMapOf<String, String?>()
        try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._SYNC_ID, CalendarContract.Events.SYNC_DATA1),
                "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DELETED} = 0",
                arrayOf(localCalendarId.toString()),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val uid = c.getStringOrNull(0) ?: continue
                    out[uid] = c.getStringOrNull(1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "localUidToEtag failed", e)
        }
        return out
    }

    private fun eventId(context: Context, localCalendarId: Long, uid: String): Long? =
        try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID),
                "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events._SYNC_ID} = ?",
                arrayOf(localCalendarId.toString(), uid),
                null,
            )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        } catch (e: Exception) {
            Log.e(TAG, "eventId failed", e); null
        }

    fun upsertEvent(context: Context, accountName: String, localCalendarId: Long, e: RemoteEvent) {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, localCalendarId)
            put(CalendarContract.Events._SYNC_ID, e.uid)
            put(CalendarContract.Events.SYNC_DATA1, e.etag)
            put(CalendarContract.Events.SYNC_DATA2, e.href)
            put(CalendarContract.Events.TITLE, e.summary)
            put(CalendarContract.Events.DESCRIPTION, e.description)
            put(CalendarContract.Events.EVENT_LOCATION, e.location)
            put(CalendarContract.Events.DTSTART, e.startMillis)
            put(CalendarContract.Events.ALL_DAY, if (e.allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (e.allDay) "UTC" else e.timezone)
            put(CalendarContract.Events.DIRTY, 0)
            if (e.rrule.isNullOrBlank()) {
                put(CalendarContract.Events.DTEND, e.endMillis)
            } else {
                // Recurring events must use DURATION instead of DTEND.
                put(CalendarContract.Events.RRULE, e.rrule.removePrefix("RRULE:"))
                val durSecs = ((e.endMillis - e.startMillis) / 1000).coerceAtLeast(0)
                put(CalendarContract.Events.DURATION, "P${durSecs}S")
            }
        }
        try {
            val existing = eventId(context, localCalendarId, e.uid)
            if (existing != null) {
                context.contentResolver.update(
                    CalendarContract.Events.CONTENT_URI.asSyncAdapter(accountName),
                    values, "${CalendarContract.Events._ID} = ?", arrayOf(existing.toString()),
                )
            } else {
                context.contentResolver.insert(
                    CalendarContract.Events.CONTENT_URI.asSyncAdapter(accountName), values,
                )
            }
        } catch (e2: Exception) {
            Log.e(TAG, "upsertEvent failed", e2)
        }
    }

    fun deleteEvent(context: Context, accountName: String, localCalendarId: Long, uid: String) {
        val id = eventId(context, localCalendarId, uid) ?: return
        try {
            context.contentResolver.delete(
                CalendarContract.Events.CONTENT_URI.asSyncAdapter(accountName),
                "${CalendarContract.Events._ID} = ?", arrayOf(id.toString()),
            )
        } catch (e: Exception) {
            Log.e(TAG, "deleteEvent failed", e)
        }
    }

    /**
     * Removes every calendar (and, by cascade, its events) for this account from
     * the on-device provider. Used when the user turns calendar sync off; the
     * account itself stays, so a later re-enable can repopulate it from a fresh
     * pull. Deleting via the sync-adapter URI hard-removes the rows.
     */
    fun purge(context: Context, accountName: String) {
        try {
            context.contentResolver.delete(
                CalendarContract.Calendars.CONTENT_URI.asSyncAdapter(accountName),
                "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
                arrayOf(accountName, ACCOUNT_TYPE),
            )
        } catch (e: Exception) {
            Log.e(TAG, "purge failed", e)
        }
    }

    fun localCalendars(context: Context, accountName: String): List<Long> {
        val ids = mutableListOf<Long>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
                arrayOf(accountName, ACCOUNT_TYPE), null,
            )?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
        } catch (e: Exception) {
            Log.e(TAG, "localCalendars failed", e)
        }
        return ids
    }

    fun getLocalChanges(context: Context, accountName: String, localCalendarId: Long): List<LocalEventChange> {
        val changes = mutableListOf<LocalEventChange>()
        try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    CalendarContract.Events._ID,
                    CalendarContract.Events._SYNC_ID,
                    CalendarContract.Events.SYNC_DATA1,
                    CalendarContract.Events.DELETED,
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DESCRIPTION,
                    CalendarContract.Events.EVENT_LOCATION,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND,
                    CalendarContract.Events.ALL_DAY,
                    CalendarContract.Events.EVENT_TIMEZONE,
                    CalendarContract.Events.RRULE,
                ),
                "${CalendarContract.Events.CALENDAR_ID} = ? AND (${CalendarContract.Events.DIRTY} = 1 OR ${CalendarContract.Events.DELETED} = 1)",
                arrayOf(localCalendarId.toString()), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val deleted = c.getInt(3) == 1
                    val uid = c.getStringOrNull(1)
                    changes += LocalEventChange(
                        eventId = c.getLong(0),
                        syncId = uid,
                        etag = c.getStringOrNull(2),
                        deleted = deleted,
                        event = if (deleted || uid == null) null else RemoteEvent(
                            uid = uid,
                            calendarId = localCalendarId.toString(),
                            summary = c.getStringOrNull(4) ?: "",
                            description = c.getStringOrNull(5) ?: "",
                            location = c.getStringOrNull(6) ?: "",
                            startMillis = c.getLong(7),
                            endMillis = c.getLong(8),
                            allDay = c.getInt(9) == 1,
                            timezone = c.getStringOrNull(10) ?: "UTC",
                            rrule = c.getStringOrNull(11),
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getLocalChanges failed", e)
        }
        return changes
    }

    fun clearDirty(context: Context, accountName: String, eventId: Long) {
        try {
            context.contentResolver.update(
                CalendarContract.Events.CONTENT_URI.asSyncAdapter(accountName),
                ContentValues().apply { put(CalendarContract.Events.DIRTY, 0) },
                "${CalendarContract.Events._ID} = ?", arrayOf(eventId.toString()),
            )
        } catch (e: Exception) {
            Log.e(TAG, "clearDirty failed", e)
        }
    }
}
