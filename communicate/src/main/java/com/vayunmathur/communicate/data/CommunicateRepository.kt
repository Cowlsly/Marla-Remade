package com.vayunmathur.communicate.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceClient
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceSession
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceWebSender
import com.vayunmathur.communicate.data.googlevoice.GvCall
import com.vayunmathur.communicate.data.googlevoice.GvCallType
import com.vayunmathur.communicate.data.googlevoice.GvMessage
import com.vayunmathur.communicate.data.googlevoice.GvThread
import com.vayunmathur.communicate.data.signal.SignalCachedMessage
import com.vayunmathur.communicate.data.signal.SignalClient
import com.vayunmathur.communicate.data.signal.SignalConversation
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.SignalFeature
import com.vayunmathur.communicate.data.signal.SignalLineSession
import com.vayunmathur.communicate.data.signal.SignalServiceData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppCachedMessage
import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient
import com.vayunmathur.communicate.data.whatsapp.WhatsAppConversation
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import com.vayunmathur.communicate.data.whatsapp.WhatsAppLineSession
import com.vayunmathur.communicate.data.whatsapp.WhatsAppServiceData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.vayunmathur.library.ui.ExternalIntents

object CommunicateRepository {
    fun loadContacts(context: Context): List<CommunicateContact> {
        if (!context.hasPermission(Manifest.permission.READ_CONTACTS)) return emptyList()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone._ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
        )
        return runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC",
            )?.use { cursor ->
                buildList {
                    val id = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone._ID)
                    val name = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                    val number = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val type = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
                    val label = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LABEL)
                    val seenNumbers = mutableSetOf<String>()

                    while (cursor.moveToNext()) {
                        val rawNumber = cursor.getString(number).orEmpty().trim()
                        if (rawNumber.isEmpty()) continue
                        val normalized = rawNumber.filter { it.isDigit() || it == '+' }
                        if (!seenNumbers.add(normalized.ifEmpty { rawNumber })) continue
                        add(
                            CommunicateContact(
                                id = cursor.getLong(id),
                                name = cursor.getString(name).orEmpty().ifBlank { rawNumber },
                                phoneNumber = rawNumber,
                                label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                                    context.resources,
                                    cursor.getInt(type),
                                    cursor.getString(label),
                                ).toString(),
                            )
                        )
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    fun loadCallLogs(context: Context): List<CommunicateCallLogEntry> {
        if (!context.hasPermission(Manifest.permission.READ_CALL_LOG)) return emptyList()

        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.PHONE_ACCOUNT_ID,
        )
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                buildList {
                    val id = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                    val name = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                    val number = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                    val type = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                    val date = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                    val duration = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                    val account = cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
                    val activeSubs = SimManager.activeSims(context).map { it.subscriptionId }.toSet()

                    while (cursor.moveToNext()) {
                        val phoneNumber = cursor.getString(number).orEmpty().ifBlank { "Unknown" }
                        // PHONE_ACCOUNT_ID is the SIM's subscription id on most devices; keep it
                        // only when it maps to an active SIM so we can label the row by SIM.
                        val subId = account.takeIf { it >= 0 }
                            ?.let { cursor.getString(it) }
                            ?.toIntOrNull()
                            ?.takeIf { it in activeSubs }
                        add(
                            CommunicateCallLogEntry(
                                id = cursor.getLong(id),
                                displayName = cursor.getString(name)?.takeIf { it.isNotBlank() },
                                phoneNumber = phoneNumber,
                                type = cursor.getInt(type).toCommunicateCallType(),
                                timestampMillis = cursor.getLong(date),
                                durationSeconds = cursor.getLong(duration),
                                subscriptionId = subId,
                            )
                        )
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    fun loadSmsThreads(context: Context): List<SmsThread> {
        if (!context.hasPermission(Manifest.permission.READ_SMS)) return emptyList()
        val recipientsByThread = loadThreadRecipients(context)
        // Merge SMS + MMS messages so group MMS threads (which have no SMS rows) still appear.
        val byThread = (loadSmsMessages(context, threadId = null) + loadMmsMessages(context, threadId = null))
            .groupBy { it.threadId }
        return byThread.values.mapNotNull { messages ->
            val newest = messages.maxByOrNull { it.timestampMillis } ?: return@mapNotNull null
            val threadId = newest.threadId
            val participants = recipientsByThread[threadId]
                ?: messages.mapNotNull { it.senderAddress }.distinct().ifEmpty { listOf(newest.address) }
            val isGroup = participants.size > 1
            val primary = participants.firstOrNull()?.takeIf { it.isNotBlank() } ?: newest.address
            val displayName = if (isGroup) {
                participants.joinToString(", ") { findContactName(context, it) ?: it }
            } else {
                findContactName(context, primary)
            }
            SmsThread(
                threadId = threadId,
                address = primary,
                displayName = displayName,
                snippet = newest.body,
                timestampMillis = newest.timestampMillis,
                unreadCount = messages.count { !it.outgoing && !it.read },
                subscriptionId = newest.subscriptionId,
                isGroup = isGroup,
                participants = if (isGroup) participants else emptyList(),
            )
        }.sortedByDescending { it.timestampMillis }
    }

    /**
     * Clear the unread flags on a SIM thread's provider rows. SIM [SmsThread.unreadCount] is
     * recomputed from the provider on every load, so a row left at `read = 0` — typically imported
     * from a previously installed SMS app — keeps the badge forever unless something writes it
     * back (#562). Only the default SMS app may write these columns.
     */
    suspend fun markSimThreadRead(context: Context, threadId: Long): Boolean = withContext(Dispatchers.IO) {
        val values = android.content.ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        // SMS and MMS are separate tables sharing the thread id; an unread thread may be either.
        listOf(Telephony.Sms.CONTENT_URI, Telephony.Mms.CONTENT_URI).sumOf { uri ->
            runCatching {
                context.contentResolver.update(
                    uri, values,
                    "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
                    arrayOf(threadId.toString()),
                )
            }.getOrDefault(0)
        } > 0
    }

    /**
     * Map each provider thread id to its recipient addresses via `mms-sms/conversations` +
     * `mms-sms/canonical-addresses`. Threads with >1 recipient are group threads. Empty on any
     * provider error (falls back to per-message address inference).
     */
    private fun loadThreadRecipients(context: Context): Map<Long, List<String>> = runCatching {
        // recipient_ids is a space-separated list of ids into the canonical-addresses table.
        val canonical = HashMap<String, String>()
        context.contentResolver.query(
            Uri.parse("content://mms-sms/canonical-addresses"),
            arrayOf("_id", "address"),
            null, null, null,
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow("_id")
            val addrIdx = c.getColumnIndexOrThrow("address")
            while (c.moveToNext()) canonical[c.getString(idIdx)] = c.getString(addrIdx).orEmpty()
        }
        val result = HashMap<Long, List<String>>()
        context.contentResolver.query(
            Telephony.Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build(),
            arrayOf(Telephony.Threads._ID, Telephony.Threads.RECIPIENT_IDS),
            null, null, null,
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(Telephony.Threads._ID)
            val ridIdx = c.getColumnIndexOrThrow(Telephony.Threads.RECIPIENT_IDS)
            while (c.moveToNext()) {
                val tid = c.getLong(idIdx)
                val addrs = c.getString(ridIdx).orEmpty().split(" ")
                    .mapNotNull { rid -> canonical[rid]?.takeIf { it.isNotBlank() } }
                if (addrs.isNotEmpty()) result[tid] = addrs
            }
        }
        result
    }.getOrDefault(emptyMap())

    /**
     * Read MMS messages (text + image parts) from `Telephony.Mms`, with the per-message sender
     * (from `Telephony.Mms.Addr`, for group labeling) and image/text attachments. Returns them in
     * the shared [SmsMessage] model so they merge with SMS by threadId.
     */
    fun loadMmsMessages(context: Context, threadId: Long?): List<SmsMessage> {
        if (!context.hasPermission(Manifest.permission.READ_SMS)) return emptyList()
        val selection = threadId?.let { "${Telephony.Mms.THREAD_ID} = ?" }
        val args = threadId?.let { arrayOf(it.toString()) }
        return runCatching {
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(
                    Telephony.Mms._ID,
                    Telephony.Mms.THREAD_ID,
                    Telephony.Mms.DATE,
                    Telephony.Mms.MESSAGE_BOX,
                    Telephony.Mms.READ,
                    Telephony.Mms.SUBSCRIPTION_ID,
                ),
                selection, args,
                "${Telephony.Mms.DATE} ASC",
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(Telephony.Mms._ID)
                val threadIdx = c.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)
                val dateIdx = c.getColumnIndexOrThrow(Telephony.Mms.DATE)
                val boxIdx = c.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)
                val readIdx = c.getColumnIndexOrThrow(Telephony.Mms.READ)
                val subIdx = c.getColumnIndex(Telephony.Mms.SUBSCRIPTION_ID)
                buildList {
                    while (c.moveToNext()) {
                        val mmsId = c.getLong(idIdx)
                        val box = c.getInt(boxIdx)
                        val outgoing = box == Telephony.Mms.MESSAGE_BOX_SENT ||
                            box == Telephony.Mms.MESSAGE_BOX_OUTBOX
                        val (text, attachments) = loadMmsParts(context, mmsId)
                        val sender = if (!outgoing) loadMmsSender(context, mmsId) else null
                        add(
                            SmsMessage(
                                // Keep MMS ids clear of SMS ids (both are provider row ids).
                                id = mmsId or 0x2_0000_0000L,
                                threadId = c.getLong(threadIdx),
                                address = sender.orEmpty(),
                                body = text,
                                // MMS DATE is in seconds, unlike SMS (ms).
                                timestampMillis = c.getLong(dateIdx) * 1000L,
                                outgoing = outgoing,
                                read = c.getInt(readIdx) != 0,
                                attachments = attachments,
                                subscriptionId = subIdx.takeIf { it >= 0 }?.let { c.getInt(it) }?.takeIf { it >= 0 },
                                senderAddress = sender,
                                status = if (outgoing) MessageStatus.Sent else MessageStatus.None,
                            )
                        )
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    /** Extract the text body + image attachments from an MMS message's parts. */
    private fun loadMmsParts(context: Context, mmsId: Long): Pair<String, List<CommunicateAttachment>> {
        val text = StringBuilder()
        val attachments = ArrayList<CommunicateAttachment>()
        runCatching {
            context.contentResolver.query(
                Uri.parse("content://mms/part"),
                arrayOf("_id", "ct", "text", "_data"),
                "mid = ?", arrayOf(mmsId.toString()), null,
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow("_id")
                val ctIdx = c.getColumnIndexOrThrow("ct")
                val textIdx = c.getColumnIndexOrThrow("text")
                val dataIdx = c.getColumnIndexOrThrow("_data")
                while (c.moveToNext()) {
                    val ct = c.getString(ctIdx).orEmpty()
                    when {
                        ct == "text/plain" -> {
                            val hasData = c.getString(dataIdx) != null
                            if (hasData) {
                                // Body stored as a file part; read via the part content uri.
                                text.append(readMmsTextPart(context, c.getLong(idIdx)))
                            } else {
                                text.append(c.getString(textIdx).orEmpty())
                            }
                        }
                        ct.startsWith("image/") || ct.startsWith("video/") -> {
                            attachments.add(
                                CommunicateAttachment(
                                    contentUri = "content://mms/part/${c.getLong(idIdx)}",
                                    mimeType = ct,
                                ),
                            )
                        }
                        // application/smil and others are ignored.
                    }
                }
            }
        }
        return text.toString() to attachments
    }

    private fun readMmsTextPart(context: Context, partId: Long): String = runCatching {
        context.contentResolver.openInputStream(Uri.parse("content://mms/part/$partId"))?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }.orEmpty()
    }.getOrDefault("")

    /** The inbound sender address for an MMS (Addr rows with type=FROM=137). */
    private fun loadMmsSender(context: Context, mmsId: Long): String? = runCatching {
        context.contentResolver.query(
            Uri.parse("content://mms/$mmsId/addr"),
            arrayOf("address", "type"),
            "type = 137", null, null,
        )?.use { c ->
            val addrIdx = c.getColumnIndexOrThrow("address")
            if (c.moveToFirst()) c.getString(addrIdx)?.takeIf { it.isNotBlank() && it != "insert-address-token" } else null
        }
    }.getOrNull()

    fun loadSmsMessages(context: Context, threadId: Long?): List<SmsMessage> {
        if (!context.hasPermission(Manifest.permission.READ_SMS)) return emptyList()

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
            Telephony.Sms.STATUS,
            Telephony.Sms.SUBSCRIPTION_ID,
        )
        val selection = threadId?.let { "${Telephony.Sms.THREAD_ID} = ?" }
        val args = threadId?.let { arrayOf(it.toString()) }
        return runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                selection,
                args,
                "${Telephony.Sms.DATE} ASC",
            )?.use { cursor ->
                buildList {
                    val id = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                    val thread = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                    val address = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    val body = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    val date = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                    val type = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                    val read = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
                    val status = cursor.getColumnIndex(Telephony.Sms.STATUS)
                    val sub = cursor.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)

                    while (cursor.moveToNext()) {
                        val msgType = cursor.getInt(type)
                        val outgoing = msgType == Telephony.Sms.MESSAGE_TYPE_SENT ||
                            msgType == Telephony.Sms.MESSAGE_TYPE_OUTBOX
                        val statusVal = status.takeIf { it >= 0 }?.let { cursor.getInt(it) } ?: -1
                        add(
                            SmsMessage(
                                id = cursor.getLong(id),
                                threadId = cursor.getLong(thread),
                                address = cursor.getString(address).orEmpty(),
                                body = cursor.getString(body).orEmpty(),
                                timestampMillis = cursor.getLong(date),
                                outgoing = outgoing,
                                read = cursor.getInt(read) != 0,
                                subscriptionId = sub.takeIf { it >= 0 }?.let { cursor.getInt(it) }?.takeIf { it >= 0 },
                                status = smsStatus(msgType, outgoing, statusVal),
                            )
                        )
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    /** Map a SIM SMS/MMS provider TYPE + STATUS into the shared [MessageStatus] tick model. */
    private fun smsStatus(msgType: Int, outgoing: Boolean, status: Int): MessageStatus = when {
        msgType == Telephony.Sms.MESSAGE_TYPE_FAILED -> MessageStatus.Failed
        !outgoing -> MessageStatus.None
        status == Telephony.Sms.STATUS_COMPLETE -> MessageStatus.Delivered
        status == Telephony.Sms.STATUS_FAILED -> MessageStatus.Failed
        else -> MessageStatus.Sent // STATUS_PENDING / STATUS_NONE → single tick
    }

    fun findContactName(context: Context, number: String): String? {
        if (!context.hasPermission(Manifest.permission.READ_CONTACTS) || number.isBlank()) return null
        return runCatching {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number),
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME_PRIMARY),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull()
    }

    fun placeCall(context: Context, number: String) {
        placeCall(context, choice = null, number = number)
    }

    /**
     * Place a call from a chosen line. Google Voice routes through the self-managed account; a SIM
     * choice places via that SIM's [PhoneAccountHandle]; null uses the default outgoing account.
     */
    fun placeCall(context: Context, choice: LineChoice?, number: String) {
        if (number.isBlank()) return
        if (choice is LineChoice.GoogleVoice) {
            com.vayunmathur.communicate.telephony.GoogleVoiceTelecom.placeOutgoing(context, number)
            return
        }
        val uri = Uri.fromParts("tel", number, null)
        if (context.hasPermission(Manifest.permission.CALL_PHONE)) {
            try {
                val telecomManager = context.getSystemService(TelecomManager::class.java)
                val extras = Bundle()
                val handle = (choice as? LineChoice.Sim)?.let { phoneAccountHandleForSub(context, it.subscriptionId) }
                    ?: telecomManager.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL)
                handle?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
                telecomManager.placeCall(uri, extras)
                return
            } catch (_: Exception) {
                // Fall through to ACTION_DIAL below.
            }
        }
        ExternalIntents.launch(context, Intent(Intent.ACTION_DIAL, uri))
    }

    /** Map a SIM subscription id to its Telecom [PhoneAccountHandle] (handle id is the sub id). */
    private fun phoneAccountHandleForSub(context: Context, subscriptionId: Int): android.telecom.PhoneAccountHandle? {
        if (subscriptionId < 0) return null
        if (!context.hasPermission(Manifest.permission.READ_PHONE_STATE)) return null
        val tm = context.getSystemService(TelecomManager::class.java) ?: return null
        return runCatching {
            tm.callCapablePhoneAccounts.firstOrNull { it.id == subscriptionId.toString() }
        }.getOrNull()
    }

    fun openSmsComposer(context: Context, number: String? = null, body: String? = null) {
        val uri = if (number.isNullOrBlank()) "smsto:".toUri() else "smsto:$number".toUri()
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            if (!body.isNullOrBlank()) putExtra("sms_body", body)
        }
        ExternalIntents.launch(context, intent)
    }

    /** Resolve (or create) the SIM thread id for an address, so a new SIM conversation shows history. */
    fun getOrCreateSmsThreadId(context: Context, address: String): Long? = runCatching {
        Telephony.Threads.getOrCreateThreadId(context, address)
    }.getOrNull()

    /**
     * Get (or create) the provider thread id for a multi-recipient MMS group thread. All replies to
     * a group land in this single thread. Returns null if the set is empty or the provider rejects
     * it (e.g. MMS unavailable). Used by both group creation and group MMS send.
     */
    fun getOrCreateSmsGroupThreadId(context: Context, recipients: List<String>): Long? = runCatching {
        val set = recipients.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (set.isEmpty()) return null
        Telephony.Threads.getOrCreateThreadId(context, set)
    }.getOrNull()

    // ------------------------------------------------------------------
    // Google Voice merge (second line)
    //
    // These suspend variants return SIM data (tagged CommunicateLine.Sim) merged with Google
    // Voice data (tagged CommunicateLine.GoogleVoice) when a GV session is present. All GV
    // network work is done here off the main thread; callers already invoke us from
    // Dispatchers.IO in produceState. GV failures are swallowed so the SIM inbox still loads.
    // ------------------------------------------------------------------

    /** Merged thread list: SIM threads + Google Voice threads + WhatsApp + Signal, newest first. */
    suspend fun loadSmsThreadsMerged(context: Context): List<SmsThread> {
        val sim = loadSmsThreads(context)
        val gv = loadGoogleVoiceThreads(context)
        val wa = loadWhatsAppThreads(context)
        val signal = loadSignalThreads(context)
        return (sim + gv + wa + signal).sortedByDescending { it.timestampMillis }
    }

    /** Route by line: SIM threads read the provider; GV threads hit `api2thread/get`; WA/Signal read Room. */
    suspend fun loadSmsMessagesMerged(context: Context, thread: SmsThread): List<SmsMessage> =
        when (thread.line) {
            CommunicateLine.Sim -> (loadSmsMessages(context, thread.threadId) +
                loadMmsMessages(context, thread.threadId))
                .sortedBy { it.timestampMillis }
            CommunicateLine.GoogleVoice -> {
                val remoteId = thread.remoteId ?: return emptyList()
                runCatching {
                    GoogleVoiceClient.get(context).getThread(remoteId)
                        .map { it.toSmsMessage(thread.threadId, context) }
                }.getOrDefault(emptyList())
            }
            CommunicateLine.WhatsApp -> {
                // New conversations have no remoteId yet — derive the JID from the address so the
                // outgoing echo (cached under the same normalized JID) shows immediately.
                val jid = thread.remoteId?.takeIf { it.isNotBlank() } ?: toWhatsAppJid(context, thread.address)
                loadWhatsAppMessages(context, jid)
            }
            CommunicateLine.Signal -> {
                // New Signal conversations have no remoteId yet — derive recipient from address.
                val recipient = thread.remoteId?.takeIf { it.isNotBlank() } ?: toSignalRecipient(context, thread.address)
                loadSignalMessages(context, recipient)
            }
        }

    /** Merged call history: device call log + Google Voice calls, newest first. Signal calling out of scope. */
    suspend fun loadCallLogsMerged(context: Context): List<CommunicateCallLogEntry> {
        val device = loadCallLogs(context)
        val gv = loadGoogleVoiceCalls(context)
        return (device + gv).sortedByDescending { it.timestampMillis }
    }

    /**
     * Dispatch an outgoing message from a chosen line. A SIM choice sends via that SIM's
     * [android.telephony.SmsManager] and records it in the provider; Google Voice mints a token in
     * an offscreen WebView and posts `api2thread/sendsms`. Returns true on success.
     */
    suspend fun sendMessage(
        context: Context,
        choice: LineChoice,
        address: String,
        body: String,
        threadRemoteId: String? = null,
        attachments: List<CommunicateAttachment> = emptyList(),
        participants: List<String> = emptyList(),
    ): Boolean = when (choice) {
        is LineChoice.Sim -> withContext(Dispatchers.IO) {
            // Group (multi-recipient) or media messages go out as MMS so all replies land in one
            // thread; plain 1:1 text stays SMS.
            val recipients = participants.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(address) }
            val isGroup = recipients.size > 1
            if (attachments.isEmpty() && !isGroup) {
                sendSimSms(context, choice.subscriptionId, address, body)
            } else {
                sendSimMms(context, choice.subscriptionId, recipients, body, attachments)
            }
        }
        LineChoice.GoogleVoice -> runCatching {
            // The bot-defense token is minted invisibly in an offscreen WebView; the app then
            // builds and sends the sendsms API call itself using that token. For MMS, let the
            // real web composer upload media and build the media-bearing body, then replay it.
            val activity = context as? android.app.Activity ?: return@runCatching false
            val sendBody = if (attachments.isEmpty()) {
                val token = GoogleVoiceWebSender.mintToken(activity, address, body) ?: return@runCatching false
                com.vayunmathur.communicate.data.googlevoice.GoogleVoiceParser
                    .buildSendSmsBody(address, body, threadRemoteId, botToken = token)
            } else {
                GoogleVoiceWebSender.mintPreparedBody(activity, address, body, attachments) ?: return@runCatching false
            }
            GoogleVoiceClient.get(context).sendPreparedSms(sendBody)
            true
        }.getOrDefault(false)
        LineChoice.WhatsApp -> withContext(Dispatchers.IO) {
            runCatching {
                // For WhatsApp the conversation is addressed by JID: use the thread's remoteId when
                // replying to an existing chat, else derive a 1:1 JID from the phone number.
                val jid = threadRemoteId ?: toWhatsAppJid(context, address)
                val sentId = if (attachments.isEmpty()) {
                    WhatsAppClient.sendMessage(jid, body)
                } else {
                    // Media send goes through the dedicated path; here just send any caption text.
                    if (body.isNotBlank()) WhatsAppClient.sendMessage(jid, body) else ""
                }
                // Echo the outgoing message into the local cache so it shows in our own thread
                // (a primary-only line gets no server echo of its own sends). Cache under the real
                // WA message id so delivery/read receipts can advance its status ticks.
                if (sentId != null && body.isNotBlank()) {
                    cacheOutgoingWhatsApp(context, jid, body, sentId.ifBlank { "local-${java.util.UUID.randomUUID()}" })
                }
                sentId != null
            }.getOrDefault(false)
        }
        LineChoice.Signal -> withContext(Dispatchers.IO) {
            runCatching {
                val recipient = threadRemoteId ?: toSignalRecipient(context, address)
                // Media path delegates to sendMedia; text via sendMessage. Attachments handled via Signal delegates below.
                val sentId = if (attachments.isEmpty()) {
                    if (body.isBlank()) null else SignalClient.get(context).sendMessage(recipient, body)
                } else {
                    // For media messages, send caption text if any; actual media via sendSignalMedia.
                    if (body.isNotBlank()) SignalClient.get(context).sendMessage(recipient, body) else ""
                }
                if (sentId != null && body.isNotBlank()) {
                    cacheOutgoingSignal(context, recipient, body, sentId.ifBlank { "local-${java.util.UUID.randomUUID()}" })
                }
                sentId != null
            }.getOrDefault(false)
        }
    }

    /** Send an SMS from a specific SIM subscription and store it in the Sent box. */
    private fun sendSimSms(context: Context, subscriptionId: Int, address: String, body: String): Boolean {
        if (address.isBlank() || body.isBlank()) return false
        if (!context.hasPermission(Manifest.permission.SEND_SMS)) {
            openSmsComposer(context, address, body)
            return true
        }
        return runCatching {
            val base = context.getSystemService(android.telephony.SmsManager::class.java)
            val sms = if (subscriptionId >= 0) base.createForSubscriptionId(subscriptionId) else base
            val parts = sms.divideMessage(body)
            if (parts.size > 1) {
                sms.sendMultipartTextMessage(address, null, parts, null, null)
            } else {
                sms.sendTextMessage(address, null, body, null, null)
            }
            // Record in the provider Sent box so it shows in the thread (we're the default SMS app).
            runCatching {
                val values = android.content.ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, address)
                    put(Telephony.Sms.BODY, body)
                    put(Telephony.Sms.DATE, System.currentTimeMillis())
                    put(Telephony.Sms.READ, 1)
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                    if (subscriptionId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
                }
            context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
            }
            true
        }.getOrDefault(false)
    }

    private fun smsManagerFor(context: Context, subscriptionId: Int): android.telephony.SmsManager {
        val base = context.getSystemService(android.telephony.SmsManager::class.java)
        return if (subscriptionId >= 0) base.createForSubscriptionId(subscriptionId) else base
    }

    /**
     * Send a group / media message as MMS via [android.telephony.SmsManager.sendMultimediaMessage].
     * Composes an M-Send.req PDU ([MmsPdu]), hands it to the system MMS service through a
     * FileProvider uri, and records the message in the provider so it shows in the group thread.
     * Best-effort / carrier-dependent (see plan caveat).
     */
    private fun sendSimMms(
        context: Context,
        subscriptionId: Int,
        recipients: List<String>,
        body: String,
        attachments: List<CommunicateAttachment>,
    ): Boolean {
        if (recipients.isEmpty()) return false
        if (!context.hasPermission(Manifest.permission.SEND_SMS)) return false
        return runCatching {
            val txnId = "T${System.currentTimeMillis()}"
            val mediaParts = attachments.mapNotNull { att ->
                runCatching {
                    val bytes = context.contentResolver.openInputStream(att.contentUri.toUri())
                        ?.use { it.readBytes() } ?: return@mapNotNull null
                    com.vayunmathur.communicate.telephony.MmsPdu.Part(att.mimeType, bytes)
                }.getOrNull()
            }
            val pdu = com.vayunmathur.communicate.telephony.MmsPdu.composeSendReq(
                txnId, recipients, body.takeIf { it.isNotBlank() }, mediaParts,
            )
            val dir = java.io.File(context.cacheDir, "mms").apply { mkdirs() }
            val file = java.io.File(dir, "$txnId.pdu").apply { writeBytes(pdu) }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.mmsfileprovider", file,
            )
            smsManagerFor(context, subscriptionId).sendMultimediaMessage(context, uri, null, null, null)
            persistOutgoingMms(context, subscriptionId, recipients, body)
            true
        }.getOrDefault(false)
    }

    /** Record an outgoing MMS (Sent box) + its text part + recipient/sender addr rows. */
    private fun persistOutgoingMms(
        context: Context,
        subscriptionId: Int,
        recipients: List<String>,
        body: String,
    ) {
        runCatching {
            val threadId = getOrCreateSmsGroupThreadId(context, recipients)
            val values = android.content.ContentValues().apply {
                if (threadId != null) put(Telephony.Mms.THREAD_ID, threadId)
                put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000L)
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_SENT)
                put(Telephony.Mms.READ, 1)
                put(Telephony.Mms.SEEN, 1)
                put(Telephony.Mms.MESSAGE_TYPE, 128) // M-Send.req
                if (subscriptionId >= 0) put(Telephony.Mms.SUBSCRIPTION_ID, subscriptionId)
            }
            val mmsUri = context.contentResolver.insert(Telephony.Mms.CONTENT_URI, values) ?: return
            val mmsId = mmsUri.lastPathSegment ?: return
            // Text part.
            if (body.isNotBlank()) {
                val partValues = android.content.ContentValues().apply {
                    put("mid", mmsId)
                    put("ct", "text/plain")
                    put("text", body)
                }
                context.contentResolver.insert(Uri.parse("content://mms/$mmsId/part"), partValues)
            }
            // Address rows: TO (151) per recipient.
            for (r in recipients) {
                val addrValues = android.content.ContentValues().apply {
                    put("address", r)
                    put("type", 151)
                    put("charset", 106)
                }
                context.contentResolver.insert(Uri.parse("content://mms/$mmsId/addr"), addrValues)
            }
        }
    }

    /** Toggle a Google Voice thread attribute (read/archive/spam) via batchupdateattributes. */
    suspend fun updateGoogleVoiceThread(
        context: Context,
        remoteId: String,
        action: com.vayunmathur.communicate.data.googlevoice.GoogleVoiceParser.ThreadAction,
    ): Boolean = runCatching {
        GoogleVoiceClient.get(context).updateThreadAttributes(remoteId, action)
        true
    }.getOrDefault(false)

    private suspend fun loadGoogleVoiceThreads(context: Context): List<SmsThread> {
        val session = GoogleVoiceSession.get(context)
        if (!session.hasUsableCredentials()) return emptyList()
        return runCatching {
            GoogleVoiceClient.get(context).listThreads().map { it.toSmsThread(context) }
        }.getOrDefault(emptyList())
    }

    private suspend fun loadGoogleVoiceCalls(context: Context): List<CommunicateCallLogEntry> {
        val session = GoogleVoiceSession.get(context)
        if (!session.hasUsableCredentials()) return emptyList()
        return runCatching {
            GoogleVoiceClient.get(context).listCalls().map { it.toCallLogEntry(context) }
        }.getOrDefault(emptyList())
    }

    // ── Delete ( #542 ) ────────────────────────────────────────────────

    /** Delete an entire conversation/thread, routing by [CommunicateLine]. */
    suspend fun deleteConversation(context: Context, thread: SmsThread): Boolean = withContext(Dispatchers.IO) {
        when (thread.line) {
            CommunicateLine.Sim -> deleteSimThread(context, thread.threadId)
            CommunicateLine.GoogleVoice -> thread.remoteId?.let {
                runCatching {
                    GoogleVoiceClient.get(context).updateThreadAttributes(it, com.vayunmathur.communicate.data.googlevoice.GoogleVoiceParser.ThreadAction.Archive)
                    true
                }.getOrDefault(false)
            } ?: false
            CommunicateLine.WhatsApp -> thread.remoteId?.let { jid ->
                runCatching { WhatsAppClient.deleteChat(jid, leaveGroup = true) }.getOrDefault(false)
            } ?: false
            CommunicateLine.Signal -> thread.remoteId?.let { id ->
                runCatching {
                    val db = SignalDatabase.getDatabase(context)
                    db.conversationDao().delete(id)
                    db.cachedMessageDao().deleteConversation(id)
                    true
                }.getOrDefault(false)
            } ?: false
        }
    }

    private fun deleteSimThread(context: Context, threadId: Long): Boolean = runCatching {
        // Provider deletes the thread + its SMS/MMS rows when the conversation is removed.
        val deleted = context.contentResolver.delete(
            android.net.Uri.parse("content://mms-sms/conversations/$threadId"), null, null,
        )
        if (deleted > 0) return true
        // Fallback: delete SMS and MMS rows directly.
        context.contentResolver.delete(Telephony.Sms.CONTENT_URI, "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()))
        context.contentResolver.delete(Telephony.Mms.CONTENT_URI, "${Telephony.Mms.THREAD_ID} = ?", arrayOf(threadId.toString()))
        true
    }.getOrDefault(false)

    /** Delete a call-log entry, routing by [CommunicateLine]. */
    suspend fun deleteCallLog(context: Context, entry: CommunicateCallLogEntry): Boolean = withContext(Dispatchers.IO) {
        when (entry.line) {
            CommunicateLine.Sim -> runCatching {
                context.contentResolver.delete(
                    CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID} = ?", arrayOf(entry.id.toString()),
                ) >= 0
            }.getOrDefault(false)
            CommunicateLine.GoogleVoice -> entry.let {
                // GV calls are read-only from the GV API; treat as no-op success locally.
                true
            }
            else -> false
        }
    }

    /** Stable positive Long key for a GV remote id, kept clear of provider thread ids. */
    fun stableThreadId(remoteId: String): Long = (remoteId.hashCode().toLong() and 0xFFFFFFFFL) or 0x1_0000_0000L

    // ------------------------------------------------------------------
    // WhatsApp primary line: threads/messages read from the local Room cache
    // (populated by WhatsAppEventProcessor), rich actions delegate to WhatsAppClient.
    // ------------------------------------------------------------------

    /** Virtual (network-backed) lines that don't map to a physical SIM subscription. */
    val isVirtualLine = setOf(CommunicateLine.GoogleVoice, CommunicateLine.WhatsApp, CommunicateLine.Signal)

    private suspend fun loadWhatsAppThreads(context: Context): List<SmsThread> {
        if (!com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) return emptyList()
        if (!WhatsAppLineSession.get(context).isSignedIn()) return emptyList()
        return runCatching {
            val db = WhatsAppDatabase.getDatabase(context)
            db.cachedMessageDao().getLatestPerConversation().map { m ->
                val sd = WhatsAppServiceData.parse(m.serviceData)
                val jid = m.conversationJid
                val conv = db.conversationDao().getConversation(jid)
                val isGroup = conv?.isGroup ?: sd?.isGroup ?: jid.endsWith("@g.us")
                val unread = conv?.unreadCount ?: 0
                val participants = parseParticipantsCsv(conv?.participants)
                val groupTitle = conv?.name?.takeIf { it.isNotBlank() }
                SmsThread(
                    threadId = stableThreadId(jid),
                    address = jidToDisplayAddress(jid),
                    displayName = if (isGroup) (groupTitle ?: whatsAppDisplayName(context, jid, sd))
                        else whatsAppDisplayName(context, jid, sd),
                    snippet = if (m.isRevoked) "This message was deleted" else m.body,
                    timestampMillis = m.timestamp,
                    unreadCount = unread,
                    line = CommunicateLine.WhatsApp,
                    remoteId = jid,
                    isGroup = isGroup,
                    avatarUrl = null,
                    participants = participants,
                    groupTitle = groupTitle,
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Parse the conversation's stored participants column (JSON array of names) into a list. */
    private fun parseParticipantsCsv(stored: String?): List<String> {
        if (stored.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(stored)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrElse {
            // Legacy/plain CSV fallback.
            stored.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    private suspend fun loadWhatsAppMessages(context: Context, jid: String): List<SmsMessage> =
        runCatching {
            val db = WhatsAppDatabase.getDatabase(context)
            db.cachedMessageDao().getForConversation(jid).map { m ->
                SmsMessage(
                    id = (m.messageId.hashCode().toLong() and 0xFFFFFFFFL),
                    threadId = stableThreadId(jid),
                    address = jidToDisplayAddress(jid),
                    body = if (m.isRevoked) "" else m.body,
                    timestampMillis = m.timestamp,
                    outgoing = m.outgoing,
                    read = true,
                    line = CommunicateLine.WhatsApp,
                    remoteId = m.messageId,
                    serviceData = m.serviceData,
                    senderAddress = m.senderJid.takeIf { it.isNotBlank() && !m.outgoing },
                    status = m.status.let { s ->
                        com.vayunmathur.communicate.data.MessageStatus.entries.getOrElse(s) {
                            com.vayunmathur.communicate.data.MessageStatus.None
                        }
                    },
                )
            }
        }.getOrDefault(emptyList())

    // -- WhatsApp rich actions (delegate to the client) --

    suspend fun sendWhatsAppReaction(
        jid: String,
        messageId: String,
        emoji: String,
        targetFromMe: Boolean,
        targetSenderJid: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        WhatsAppClient.sendReaction(jid, messageId, emoji, targetFromMe, targetSenderJid)
    }

    suspend fun editWhatsAppMessage(jid: String, messageId: String, newBody: String): Boolean =
        withContext(Dispatchers.IO) { WhatsAppClient.sendEdit(jid, messageId, newBody) }

    suspend fun revokeWhatsAppMessage(jid: String, messageId: String, senderJid: String = ""): Boolean =
        withContext(Dispatchers.IO) { WhatsAppClient.sendRevoke(jid, messageId, senderJid) }

    suspend fun sendWhatsAppPollVote(
        jid: String,
        pollMessageId: String,
        pollCreatorJid: String,
        pollFromMe: Boolean,
        selectedOptionNames: List<String>,
    ): Boolean = withContext(Dispatchers.IO) {
        WhatsAppClient.sendPollVote(jid, pollMessageId, pollCreatorJid, pollFromMe, selectedOptionNames)
    }

    suspend fun createWhatsAppPoll(
        jid: String,
        question: String,
        options: List<String>,
        selectableCount: Int = 0,
    ): String? = withContext(Dispatchers.IO) {
        WhatsAppClient.sendPollCreation(jid, question, options, selectableCount)
    }

    suspend fun sendWhatsAppMedia(jid: String, bytes: ByteArray, mimeType: String, fileName: String?): Boolean =
        withContext(Dispatchers.IO) { WhatsAppClient.sendMedia(jid, bytes, mimeType, fileName) }

    /** True only when the WhatsApp primary client is logged in (needed for send/group ops). */
    fun isWhatsAppConnected(): Boolean = WhatsAppClient.isConnected()

    // -- WhatsApp MEX / GraphQL (dev-only scaffolding, gated on WhatsAppFeature.enabled) --
    //
    // Thin pass-throughs to the xwa2_* operation catalog so the new MEX capabilities are
    // callable/integrable without new UI. Every entry is gated behind WhatsAppFeature.enabled
    // (stripped from release by R8) and runs on Dispatchers.IO. Each returns a MexResult; when the
    // feature is off or an op's persisted doc_id isn't captured yet, callers get a typed transport
    // failure rather than a crash.

    private val mexDisabled: com.vayunmathur.communicate.data.whatsapp.mex.MexResult
        get() = com.vayunmathur.communicate.data.whatsapp.mex.MexResult.transport("disabled")

    /** MEX group metadata read (`xwa2_group_query_by_id`). */
    suspend fun whatsAppGroupInfo(
        context: Context,
        groupJid: String,
    ): com.vayunmathur.communicate.data.whatsapp.mex.MexResult = withContext(Dispatchers.IO) {
        if (!com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) return@withContext mexDisabled
        com.vayunmathur.communicate.data.whatsapp.mex.WhatsAppMexOps.groupQueryById(context, groupJid)
    }

    /** MEX contact discovery (`xwa2_contact_discovery`) for raw phone numbers. */
    suspend fun whatsAppContactDiscovery(
        context: Context,
        rawPhoneNumbers: List<String>,
        discoveryContext: String = "SEARCH",
    ): com.vayunmathur.communicate.data.whatsapp.mex.MexResult = withContext(Dispatchers.IO) {
        if (!com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) return@withContext mexDisabled
        com.vayunmathur.communicate.data.whatsapp.mex.WhatsAppMexOps.contactDiscovery(context, rawPhoneNumbers, discoveryContext)
    }

    /** MEX username read (`xwa2_username_get`). */
    suspend fun whatsAppUsernameGet(
        context: Context,
    ): com.vayunmathur.communicate.data.whatsapp.mex.MexResult = withContext(Dispatchers.IO) {
        if (!com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) return@withContext mexDisabled
        com.vayunmathur.communicate.data.whatsapp.mex.WhatsAppMexOps.usernameGet(context)
    }

    /** MEX username claim (`xwa2_username_set`). */
    suspend fun whatsAppUsernameSet(
        context: Context,
        username: String,
        pin: String? = null,
        sessionId: String? = null,
    ): com.vayunmathur.communicate.data.whatsapp.mex.MexResult = withContext(Dispatchers.IO) {
        if (!com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) return@withContext mexDisabled
        com.vayunmathur.communicate.data.whatsapp.mex.WhatsAppMexOps.usernameSet(context, username, pin, sessionId = sessionId)
    }

    /** MEX blocklist read (`xwa2_blocklist_get`). */
    suspend fun whatsAppBlocklistGet(
        context: Context,
        dhash: String? = null,
    ): com.vayunmathur.communicate.data.whatsapp.mex.MexResult = withContext(Dispatchers.IO) {
        if (!com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) return@withContext mexDisabled
        com.vayunmathur.communicate.data.whatsapp.mex.WhatsAppMexOps.blocklistGet(context, dhash)
    }

    /** MEX presence read (`xwa2_presence_data_platform_get_online_or_last_status`). */
    suspend fun whatsAppPresence(
        context: Context,
        lidJids: List<String>,
        lastActiveFilter: String? = null,
    ): com.vayunmathur.communicate.data.whatsapp.mex.MexResult = withContext(Dispatchers.IO) {
        if (!com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) return@withContext mexDisabled
        com.vayunmathur.communicate.data.whatsapp.mex.WhatsAppMexOps.getOnlineOrLastStatus(context, lidJids, lastActiveFilter)
    }

    /**
     * Refresh a peer's presence for an open thread and emit a [WhatsAppEvent.PresenceUpdate]
     * (Phase F 1e enrichment). Dev-gated + best-effort.
     */
    suspend fun whatsAppRefreshPresence(conversationId: String) = withContext(Dispatchers.IO) {
        WhatsAppClient.refreshPresence(conversationId)
    }

    /** MEX Signal-prekey publish (`xwa2_set_messaging_keys`); mints + persists one-time prekeys. */
    suspend fun whatsAppSetMessagingKeys(
        context: Context,
    ): com.vayunmathur.communicate.data.whatsapp.mex.MexResult = withContext(Dispatchers.IO) {
        if (!com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) return@withContext mexDisabled
        com.vayunmathur.communicate.data.whatsapp.mex.WhatsAppMexOps.setMessagingKeys(context)
    }

    /**
     * Sync the device address book to WhatsApp (contact discovery + primary full sync) and persist
     * the returned LID/phone mappings. Dev-gated; requires READ_CONTACTS. Returns the sync summary.
     */
    suspend fun whatsAppSyncContacts(
        context: Context,
    ): com.vayunmathur.communicate.data.whatsapp.WhatsAppContactSync.SyncResult = withContext(Dispatchers.IO) {
        com.vayunmathur.communicate.data.whatsapp.WhatsAppContactSync.sync(context)
    }

    // ---- WhatsApp calling (Phase D/E), dev-gated pass-throughs to the call manager ----

    /** Observable call state for the WhatsApp calling UI. */
    val whatsAppCallState: kotlinx.coroutines.flow.StateFlow<com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallState>
        get() = com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallManager.state

    /** Place a WhatsApp audio/video call to [conversationId] (a `wa:<jid>` id or bare JID). */
    fun whatsAppPlaceCall(conversationId: String, video: Boolean = false) {
        if (!com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) return
        WhatsAppClient.placeCall(conversationId, video)
    }

    fun whatsAppAnswerCall() {
        if (!com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) return
        com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallManager.answer()
    }

    fun whatsAppRejectCall() {
        if (!com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) return
        com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallManager.reject()
    }

    fun whatsAppHangupCall() {
        if (!com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) return
        com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallManager.hangup()
    }

    fun whatsAppSetCallMuted(muted: Boolean) =
        com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallManager.setMuted(muted)

    fun whatsAppSetCallSpeaker(on: Boolean) =
        com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallManager.setSpeaker(on)

    /**
     * Create a WhatsApp group with [subject] and the given [contacts] (phone numbers / addresses).
     * Each contact is normalized to a full WhatsApp user JID before the create IQ is sent. Returns
     * the new group's `@g.us` JID on success so the caller can open the thread, or null on failure.
     */
    suspend fun createWhatsAppGroup(
        context: Context,
        subject: String,
        contacts: List<String>,
    ): String? = withContext(Dispatchers.IO) {
        val jids = contacts
            .map { toWhatsAppJid(context, it) }
            .filter { it.endsWith("@s.whatsapp.net") }
            .distinct()
        if (jids.isEmpty()) return@withContext null
        WhatsAppClient.createGroup(subject, jids)
    }

    suspend fun sendWhatsAppReadReceipt(
        jid: String,
        lastMessageId: String?,
        lastTimestamp: Long,
        senderJid: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        WhatsAppClient.sendReadReceipt(jid, lastMessageId, lastTimestamp, senderJid)
    }

    /**
     * Mark a WhatsApp conversation read: clear the local unread badge and send a `read` receipt for
     * the latest inbound message (blue ticks on the sender's side). [alreadyReadId] is the last
     * message we already receipted, so the foreground poll doesn't re-send. Returns the id now
     * marked read (or [alreadyReadId] when there is nothing newer).
     */
    suspend fun markWhatsAppRead(
        context: Context,
        remoteId: String?,
        address: String,
        alreadyReadId: String?,
    ): String? = withContext(Dispatchers.IO) {
        val jid = remoteId?.takeIf { it.isNotBlank() } ?: toWhatsAppJid(context, address)
        val db = WhatsAppDatabase.getDatabase(context)
        runCatching {
            db.conversationDao().getConversation(jid)?.let {
                if (it.unreadCount != 0) db.conversationDao().upsert(it.copy(unreadCount = 0))
            }
        }
        val lastInbound = runCatching {
            db.cachedMessageDao().getForConversation(jid).lastOrNull { !it.outgoing }
        }.getOrNull() ?: return@withContext alreadyReadId
        if (lastInbound.messageId == alreadyReadId) return@withContext alreadyReadId
        // For groups the receipt needs the participant; 1:1 goes to the chat JID itself.
        val sender = if (jid.endsWith("@g.us")) lastInbound.senderJid.takeIf { it.isNotBlank() } else null
        runCatching {
            WhatsAppClient.sendReadReceipt(jid, lastInbound.messageId, lastInbound.timestamp / 1000, sender)
        }
        lastInbound.messageId
    }

    /** numeric local part of a JID (strips :device and .agent suffixes). */
    private fun jidLocalPart(jid: String): String =
        jid.substringBefore("@").substringBefore(":").substringBefore(".")

    private fun jidToDisplayAddress(jid: String): String {
        if (jid.endsWith("@g.us")) return jid
        val phone = jidLocalPart(jid)
        return if (phone.isNotEmpty() && phone.all { it.isDigit() }) "+$phone" else phone
    }

    private fun whatsAppDisplayName(context: Context, jid: String, sd: WhatsAppServiceData?): String? {
        if (jid.endsWith("@g.us")) return sd?.senderName // group display name not cached in v1
        val phone = jidLocalPart(jid)
        return findContactName(context, "+$phone") ?: sd?.senderName
    }

    /**
     * Build a 1:1 WhatsApp JID from a phone number / address. WhatsApp JIDs require the FULL
     * international number (country code, no '+'), so a nationally-typed number like "2134774209"
     * must be normalized to "12134774209" — otherwise usync returns 0 devices and the message goes
     * nowhere. Uses libphonenumber with the SIM/locale region to infer the country code.
     */
    private fun toWhatsAppJid(context: Context, address: String): String {
        if (address.contains("@")) return address
        val region = runCatching {
            val tm = context.getSystemService(TelephonyManager::class.java)
            (tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: tm?.networkCountryIso)?.uppercase()
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: context.resources.configuration.locales[0].country.ifEmpty { "US" }
        val e164 = runCatching {
            val util = com.google.i18n.phonenumbers.PhoneNumberUtil.getInstance()
            val parsed = util.parse(address, region)
            util.format(parsed, com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat.E164)
        }.getOrNull()
        val normalized = (e164 ?: address).filter { it.isDigit() }
        return "$normalized@s.whatsapp.net"
    }

    /** Insert an outgoing WhatsApp message into the local cache so it shows in our own thread. */
    private suspend fun cacheOutgoingWhatsApp(context: Context, jid: String, body: String, messageId: String) {
        runCatching {
            val db = WhatsAppDatabase.getDatabase(context)
            val now = System.currentTimeMillis()
            db.cachedMessageDao().upsert(
                WhatsAppCachedMessage(
                    messageId = messageId,
                    conversationJid = jid,
                    body = body,
                    timestamp = now,
                    outgoing = true,
                    // Sent (1) = single grey tick until a delivery/read receipt advances it.
                    status = 1,
                ),
            )
            val existing = db.conversationDao().getConversation(jid)
            db.conversationDao().upsert(
                (existing ?: WhatsAppConversation(chatJid = jid)).copy(lastMessageTimestamp = now),
            )
        }
    }

    // ------------------------------------------------------------------
    // Signal primary line: threads/messages from Room (daemon writes),
    // rich actions delegate to SignalClient — mirrors WhatsApp section.
    // ------------------------------------------------------------------

    private suspend fun loadSignalThreads(context: Context): List<SmsThread> {
        if (!SignalFeature.enabled) return emptyList()
        if (!SignalLineSession.get(context).isSignedIn()) return emptyList()
        return runCatching {
            val db = SignalDatabase.getDatabase(context)
            db.cachedMessageDao().getLatestPerConversation().map { m ->
                val sd = SignalServiceData.parse(m.serviceData)
                val cid = m.conversationId
                val conv = db.conversationDao().getConversation(cid)
                val isGroup = conv?.isGroup ?: sd?.isGroup ?: false
                val unread = conv?.unreadCount ?: 0
                val participants = parseParticipantsCsv(conv?.participants)
                val groupTitle = conv?.name?.takeIf { it.isNotBlank() }
                SmsThread(
                    threadId = stableThreadId(cid),
                    address = signalJidToDisplayAddress(cid),
                    displayName = if (isGroup) (groupTitle ?: signalDisplayName(context, cid, sd))
                        else signalDisplayName(context, cid, sd),
                    snippet = if (m.isRevoked) "This message was deleted" else m.body,
                    timestampMillis = m.timestamp,
                    unreadCount = unread,
                    line = CommunicateLine.Signal,
                    remoteId = cid,
                    isGroup = isGroup,
                    avatarUrl = null,
                    participants = participants,
                    groupTitle = groupTitle,
                )
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun loadSignalMessages(context: Context, conversationId: String): List<SmsMessage> =
        runCatching {
            val db = SignalDatabase.getDatabase(context)
            db.cachedMessageDao().getForConversation(conversationId).map { m ->
                SmsMessage(
                    id = (m.messageId.hashCode().toLong() and 0xFFFFFFFFL),
                    threadId = stableThreadId(conversationId),
                    address = signalJidToDisplayAddress(conversationId),
                    body = if (m.isRevoked) "" else m.body,
                    timestampMillis = m.timestamp,
                    outgoing = m.outgoing,
                    read = true,
                    line = CommunicateLine.Signal,
                    remoteId = m.messageId,
                    serviceData = m.serviceData,
                    senderAddress = m.senderId.takeIf { it.isNotBlank() && !m.outgoing },
                    status = m.status.let { s ->
                        MessageStatus.entries.getOrElse(s) { MessageStatus.None }
                    },
                )
            }
        }.getOrDefault(emptyList())

    // -- Signal rich actions (pass-through delegates) --

    suspend fun sendSignalReaction(
        conversationId: String,
        messageId: String,
        emoji: String,
    ): Boolean = withContext(Dispatchers.IO) {
        SignalClient.sendReaction(conversationId, messageId, emoji)
    }

    suspend fun editSignalMessage(
        conversationId: String,
        messageId: String,
        newBody: String,
    ): Boolean = withContext(Dispatchers.IO) { SignalClient.editMessage(conversationId, messageId, newBody) }

    suspend fun revokeSignalMessage(
        conversationId: String,
        messageId: String,
    ): Boolean = withContext(Dispatchers.IO) { SignalClient.revoke(conversationId, messageId) }

    suspend fun sendSignalPoll(
        conversationId: String,
        question: String,
        options: List<String>,
    ): String? = withContext(Dispatchers.IO) { SignalClient.poll(conversationId, question, options) }

    suspend fun sendSignalPollVote(
        conversationId: String,
        pollMessageId: String,
        selectedOptions: List<String>,
    ): Boolean = withContext(Dispatchers.IO) { SignalClient.sendPollVote(conversationId, pollMessageId, selectedOptions) }

    suspend fun sendSignalMedia(
        conversationId: String,
        bytes: ByteArray,
        mimeType: String,
    ): String? = withContext(Dispatchers.IO) { SignalClient.sendMedia(conversationId, bytes, mimeType) }

    suspend fun sendSignalReadReceipt(
        conversationId: String,
        lastMessageId: String?,
        lastTimestamp: Long,
    ): Boolean = withContext(Dispatchers.IO) { SignalClient.readReceipt(conversationId, lastMessageId, lastTimestamp) }

    suspend fun markSignalRead(
        context: Context,
        remoteId: String?,
        address: String,
        alreadyReadId: String?,
    ): String? = withContext(Dispatchers.IO) {
        val cid = remoteId?.takeIf { it.isNotBlank() } ?: toSignalRecipient(context, address)
        val db = SignalDatabase.getDatabase(context)
        runCatching {
            db.conversationDao().getConversation(cid)?.let {
                if (it.unreadCount != 0) db.conversationDao().upsert(it.copy(unreadCount = 0))
            }
        }
        val lastInbound = runCatching {
            db.cachedMessageDao().getForConversation(cid).lastOrNull { !it.outgoing }
        }.getOrNull() ?: return@withContext alreadyReadId
        if (lastInbound.messageId == alreadyReadId) return@withContext alreadyReadId
        runCatching { SignalClient.readReceipt(cid, lastInbound.messageId, lastInbound.timestamp) }
        lastInbound.messageId
    }

    /**
     * The pending identity-key change for a Signal conversation, as (safety number, key hex), or null
     * when there is nothing to verify. The hex is passed back to [acceptSignalIdentity] so acceptance
     * can only apply to the key whose number was shown.
     */
    suspend fun signalPendingIdentityChange(
        context: Context,
        remoteId: String?,
        address: String,
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        val aci = remoteId?.takeIf { it.isNotBlank() } ?: toSignalRecipient(context, address)
        val pending = SignalClient.pendingIdentityChange(aci) ?: return@withContext null
        val safetyNumber = SignalClient.safetyNumber(aci) ?: return@withContext null
        safetyNumber to pending.joinToString("") { "%02x".format(it) }
    }

    suspend fun acceptSignalIdentity(
        context: Context,
        remoteId: String?,
        address: String,
        keyHex: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val aci = remoteId?.takeIf { it.isNotBlank() } ?: toSignalRecipient(context, address)
        SignalClient.acceptIdentityChange(aci, keyHex)
    }

    /**
     * Place a voice call on [line] for a conversation.
     *
     * SIM and Google Voice go through Telecom; WhatsApp and Signal are in-app WebRTC calls addressed by
     * conversation id. Returns false when the line cannot place the call, so the caller can say so rather
     * than appear to succeed.
     */
    suspend fun placeCallForLine(
        context: Context,
        line: CommunicateLine,
        address: String,
        remoteId: String?,
        video: Boolean = false,
    ): Boolean = when (line) {
        CommunicateLine.Sim -> {
            placeCall(context, choice = null, number = address)
            true
        }
        CommunicateLine.GoogleVoice -> {
            placeCall(context, choice = LineChoice.GoogleVoice, number = address)
            true
        }
        CommunicateLine.WhatsApp -> {
            val target = remoteId ?: address
            if (target.isBlank()) false else { whatsAppPlaceCall(target, video); true }
        }
        CommunicateLine.Signal -> withContext(Dispatchers.IO) {
            val target = remoteId?.takeIf { it.isNotBlank() } ?: toSignalRecipient(context, address)
            if (target.isBlank()) {
                false
            } else {
                SignalClient.get(context).placeCall(target, video)
                true
            }
        }
    }

    /** Whether [line] can place a call at all, so the UI can hide the affordance instead of failing. */
    fun canPlaceCall(line: CommunicateLine): Boolean = when (line) {
        CommunicateLine.Sim -> true
        CommunicateLine.GoogleVoice -> true
        CommunicateLine.WhatsApp -> com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled
        CommunicateLine.Signal -> com.vayunmathur.communicate.data.signal.SignalFeature.enabled
    }

    /**
     * Whether [line] can place a **video** call. Only the in-app WebRTC lines can; SIM and Google Voice hand
     * the call to Telecom, which carries audio only.
     */
    fun canPlaceVideoCall(line: CommunicateLine): Boolean = when (line) {
        CommunicateLine.WhatsApp -> com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled
        CommunicateLine.Signal -> com.vayunmathur.communicate.data.signal.SignalFeature.enabled
        else -> false
    }

    suspend fun createSignalGroup(
        context: Context,
        subject: String,
        contacts: List<String>,
    ): String? = withContext(Dispatchers.IO) {
        val ids = contacts.map { toSignalRecipient(context, it) }.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return@withContext null
        SignalClient.createGroup(subject, ids)
    }

    fun isSignalConnected(): Boolean = SignalClient.isConnected()

    /**
     * Build a Signal recipient id from a phone number / address / ACI.
     * Already-qualified identifiers (contain @ or look like a UUID ACI/PNI) pass through.
     * Otherwise the phone number is normalized to E.164 via libphonenumber.
     */
    private fun toSignalRecipient(context: Context, address: String): String {
        if (address.isBlank()) return ""
        // Group or already-qualified identifier.
        if (address.contains("@")) return address
        // UUID-shaped ACI/PNI.
        if (address.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) return address
        val region = runCatching {
            val tm = context.getSystemService(TelephonyManager::class.java)
            (tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: tm?.networkCountryIso)?.uppercase()
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: context.resources.configuration.locales[0].country.ifEmpty { "US" }
        val e164 = runCatching {
            val util = com.google.i18n.phonenumbers.PhoneNumberUtil.getInstance()
            val parsed = util.parse(address, region)
            util.format(parsed, com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat.E164)
        }.getOrNull()
        return e164 ?: address
    }

    private fun signalJidToDisplayAddress(conversationId: String): String {
        // Group ids contain ':' or look like UUIDs — keep as-is for group rendering.
        if (conversationId.contains(":") || conversationId.contains("group")) return conversationId
        // ACI/PNI UUIDs are not phone numbers — keep as-is; UI will resolve via contacts.
        if (conversationId.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) return conversationId
        return conversationId
    }

    private fun signalDisplayName(context: Context, conversationId: String, sd: SignalServiceData?): String? {
        // Group name from conversation metadata already handled; use senderName or contact lookup.
        if (conversationId.matches(Regex("[0-9a-fA-F]{8}-.*"))) return sd?.senderName
        return findContactName(context, conversationId) ?: sd?.senderName ?: conversationId
    }

    /** Insert an outgoing Signal message into the local cache so it shows in our own thread. */
    private suspend fun cacheOutgoingSignal(context: Context, conversationId: String, body: String, messageId: String) {
        runCatching {
            val db = SignalDatabase.getDatabase(context)
            val now = System.currentTimeMillis()
            db.cachedMessageDao().upsert(
                SignalCachedMessage(
                    messageId = messageId,
                    conversationId = conversationId,
                    body = body,
                    timestamp = now,
                    outgoing = true,
                    status = 1,
                ),
            )
            val existing = db.conversationDao().getConversation(conversationId)
            db.conversationDao().upsert(
                (existing ?: SignalConversation(chatId = conversationId)).copy(lastMessageTimestamp = now),
            )
        }
    }

    private fun GvThread.toSmsThread(context: Context): SmsThread = SmsThread(
        threadId = stableThreadId(id),
        address = phoneNumber,
        displayName = displayName ?: findContactName(context, phoneNumber),
        snippet = snippet.ifBlank { if (messages.any { it.hasMedia }) context.getString(R.string.gv_media_message) else "" },
        timestampMillis = timestampMillis,
        unreadCount = unreadCount,
        line = CommunicateLine.GoogleVoice,
        remoteId = id,
    )

    private fun GvMessage.toSmsMessage(threadId: Long, context: Context): SmsMessage = SmsMessage(
        id = ("$threadId#$id").hashCode().toLong(),
        threadId = threadId,
        address = phoneNumber,
        body = text.ifBlank { if (hasMedia) context.getString(R.string.gv_media_message) else "" },
        timestampMillis = timestampMillis,
        outgoing = outgoing,
        read = read,
        line = CommunicateLine.GoogleVoice,
        remoteId = id,
        attachments = mediaUrls.map { CommunicateAttachment(it, "image/*") },
    )

    private fun GvCall.toCallLogEntry(context: Context): CommunicateCallLogEntry = CommunicateCallLogEntry(
        id = stableThreadId(id),
        displayName = displayName ?: findContactName(context, phoneNumber),
        phoneNumber = phoneNumber,
        type = type.toCommunicateCallType(),
        timestampMillis = timestampMillis,
        durationSeconds = durationSeconds,
        line = CommunicateLine.GoogleVoice,
    )

    private fun GvCallType.toCommunicateCallType(): CommunicateCallType = when (this) {
        GvCallType.Incoming -> CommunicateCallType.Incoming
        GvCallType.Outgoing -> CommunicateCallType.Outgoing
        GvCallType.Missed -> CommunicateCallType.Missed
        GvCallType.Voicemail -> CommunicateCallType.Voicemail
        GvCallType.Unknown -> CommunicateCallType.Unknown
    }
}

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Int.toCommunicateCallType(): CommunicateCallType = when (this) {
    CallLog.Calls.INCOMING_TYPE -> CommunicateCallType.Incoming
    CallLog.Calls.OUTGOING_TYPE -> CommunicateCallType.Outgoing
    CallLog.Calls.MISSED_TYPE -> CommunicateCallType.Missed
    CallLog.Calls.REJECTED_TYPE -> CommunicateCallType.Rejected
    CallLog.Calls.BLOCKED_TYPE -> CommunicateCallType.Blocked
    CallLog.Calls.VOICEMAIL_TYPE -> CommunicateCallType.Voicemail
    else -> CommunicateCallType.Unknown
}
