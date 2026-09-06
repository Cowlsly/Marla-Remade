package com.vayunmathur.openassistant.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.vayunmathur.library.ml.Gemma4Handle
import com.vayunmathur.library.util.SecureResultReceiver
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import java.io.File
import kotlin.time.Clock
import com.vayunmathur.openassistant.data.AppDatabase
import com.vayunmathur.openassistant.data.Message
import com.vayunmathur.openassistant.data.OpenAssistantRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.vayunmathur.openassistant.R
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select

class InferenceService : Service() {

    companion object {
        var newTitle: String? = null
        var halt: Boolean = false

        /** DataStore key holding the user-editable chat system prompt. */

        /**
         * Role for a message recording a failure rather than something the assistant said.
         *
         * Rendered exactly like an assistant message - `ChatBubble` only special-cases "user" and
         * "tool" - so the user still sees what went wrong. Filtered out of the prompt by
         * [setupConversation], because feeding a failure back has the model treat its own error as
         * something it wrote, and because it lengthens the very prompt that overflowed: without
         * this, each failure makes the next one likelier and the conversation cannot recover.
         */
        const val ROLE_ERROR = "error"

        /**
         * Turns of history that eviction will not drop merely to seat a clip.
         *
         * Audio displaces old turns because a trimmed clip loses its TAIL, and the tail of a
         * spoken request is usually the request - "I was thinking about the trip and wondering
         * whether..." cut short is a different question, not a degraded one. A forgotten exchange
         * from six turns ago is not load-bearing in the same way.
         *
         * But a thirty-second clip is 750 positions and would erase a whole conversation to seat
         * itself, so the exchange the user is currently in the middle of stays. Past this floor
         * the clip yields instead, and `Gemma4Handle` trims it.
         *
         * This is a floor on displacing history FOR AUDIO only. It does not apply to fitting at
         * all: the second pass ignores it, because a turn that cannot be sent is worse than one
         * that has forgotten something.
         */
        const val HISTORY_FLOOR = 2

        /**
         * [history] with its oldest exchanges dropped until [fits] accepts what remains.
         *
         * Stops at [floor] turns even if [fits] still refuses, so a caller can bound what one
         * turn's attachment is allowed to cost the conversation.
         *
         * Separated from the measurement so the policy can be tested without a model: [fits] is
         * the only thing that needs one. Returns an empty list rather than looping forever when
         * even nothing fits, which leaves `generate` to refuse exactly as it does today.
         */
        internal fun evict(
            history: List<Gemma4Handle.Turn>,
            floor: Int = 0,
            fits: (List<Gemma4Handle.Turn>) -> Boolean,
        ): List<Gemma4Handle.Turn> {
            var kept = history
            while (kept.size > floor && !fits(kept)) kept = kept.drop(oldestExchange(kept))
            return kept
        }

        /**
         * Turns in the oldest exchange: a user/model pair, or one turn when it is not a pair.
         *
         * Pairs keep what survives a conversation rather than a reply whose question has gone.
         * The single-turn case is what a leading model turn needs - the remains of a pair whose
         * user half went in an earlier call.
         */
        private fun oldestExchange(history: List<Gemma4Handle.Turn>): Int =
            if (history.size > 1 &&
                history[0].role == Gemma4Handle.Role.USER &&
                history[1].role == Gemma4Handle.Role.MODEL
            ) 2 else 1

        /** The system prompt used when the user has not set a custom one. */
        val DEFAULT_SYSTEM_PROMPT = """
            You are a helpful Android assistant.
            On the first request the user sends to you, you MUST define a title for the conversation. You may optionally change the title if the topic of conversation changes sufficiently.

            TOOL USE GUIDELINES:
            - Only use a tool when the user's request clearly and directly relates to that tool's purpose. Do NOT guess or speculatively call tools on short or ambiguous prompts.
            - If the user asks a general knowledge question (e.g. "what is...", "how does...", "tell me about..."), answer from your own knowledge. Do not invoke app tools unless the user explicitly asks to interact with an app.
            - If a tool call fails because the required app is not installed, do NOT stop. Continue helping the user by answering from your own knowledge or suggesting alternatives.

            MEMORY:
            You have a memory feature. Use it aggressively to provide a personalized and consistent experience:
            - Whenever the user shares ANY information that might conceivably be useful in future conversations (e.g., their name, preferences, family details, interests, opinions, routines, or important facts), use 'add_to_memory' to store it immediately.
            - At the start of every conversation and whenever the user asks a question or makes a request where past context could even remotely be relevant, use 'get_memories' to retrieve stored information.
            - If a stored memory is no longer accurate or requested to be forgotten, use 'remove_memory'.
            - When in doubt about whether to use memory, USE IT.
            """.trimIndent()
    }

    private sealed class InferenceJob {
        data class Intent(
            val userText: String,
            val imagePaths: Array<String>,
            val schema: String,
            val receiver: ResultReceiver,
            val enqueuedTime: Long = System.currentTimeMillis()
        ) : InferenceJob()

        data class Standard(
            val conversationId: Long,
            val userText: String,
            val imagePaths: Array<String>,
            val audioPath: String?
        ) : InferenceJob()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val standardQueue = Channel<InferenceJob.Standard>(Channel.UNLIMITED)
    private val intentQueue = Channel<InferenceJob.Intent>(Channel.UNLIMITED)

    private var engine: Gemma4Engine? = null

    /**
     * The conversation whose history is loaded, -1 for none and -2 for an intent job.
     *
     * Still tracked even though [Gemma4Engine.ask] re-renders the whole prompt every turn:
     * switching conversations has to reload history and rebuild the tools, which capture the id.
     */
    private var currentConversationId: Long = -1L

    /** History for the conversation being served. */
    private var currentHistory: List<Gemma4Handle.Turn> = emptyList()

    /** The tool table, rebuilt per conversation because the tools capture its id. */
    private var currentTools: ToolRegistry? = null

    private val repository by lazy { OpenAssistantRepository.get(applicationContext) }
    private val conversationDao get() = repository.conversationDaoRef
    private val messageDao get() = repository.messageDaoRef
    private val memoryDao get() = repository.memoryDaoRef

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundTask()
        serviceScope.launch {
            try {
                ensureEngineInitialized()
            } catch (e: Exception) {
                Log.e("InferenceService", "Error pre-warming engine", e)
            }
        }
        serviceScope.launch {
            while (isActive) {
                try {
                    val standardJob = standardQueue.tryReceive().getOrNull()
                    if (standardJob != null) { executeStandardInference(standardJob); continue }

                    val intentJob = intentQueue.tryReceive().getOrNull()
                    if (intentJob != null) { processIntentJob(intentJob); continue }

                    select<Unit> {
                        standardQueue.onReceive { executeStandardInference(it) }
                        intentQueue.onReceive { processIntentJob(it) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("InferenceService", "Critical error in job processor loop", e)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("InferenceService", "onStartCommand received intent")
        if (intent == null) return START_STICKY
        intent.setExtrasClassLoader(SecureResultReceiver::class.java.classLoader)

        val conversationId = intent.getLongExtra("conversation_id", -1L)
        val userText = intent.getStringExtra("user_text") ?: ""
        val audioPath = intent.getStringExtra("audio_path")
        val schema = intent.getStringExtra("schema")
        val receiver = IntentCompat.getParcelableExtra(intent, "RECEIVER", ResultReceiver::class.java)

        val imageUris = IntentCompat.getParcelableArrayListExtra(intent, "image_uris", Uri::class.java)
        val staticImagePaths = intent.getStringArrayExtra("image_paths") ?: emptyArray()

        serviceScope.launch {
            val imagePathsFromUris = imageUris?.mapNotNull { uri ->
                copyUriToFile(this@InferenceService, uri)?.absolutePath
            }?.toTypedArray() ?: emptyArray()

            val imagePaths = staticImagePaths + imagePathsFromUris

            if (receiver != null && schema != null) {
                Log.i("InferenceService", "Queueing Intent Inference request")
                intentQueue.trySend(InferenceJob.Intent(userText, imagePaths, schema, receiver))
            } else if (conversationId != -1L) {
                Log.d("InferenceService", "Queueing standard inference for conversation: $conversationId")
                standardQueue.trySend(InferenceJob.Standard(conversationId, userText, imagePaths, audioPath))
            }
        }

        return START_STICKY
    }

    private fun startForegroundTask() {
        val channelId = "inference_service"
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(channelId, getString(R.string.inference_service), NotificationManager.IMPORTANCE_LOW)
        manager?.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.processing_ai_inference))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE only exists from API 34. Below that the platform has
        // no notion of the type, and an untyped foreground start is the equivalent.
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        androidx.core.app.ServiceCompat.startForeground(
            this,
            1,
            notification,
            serviceType,
        )
    }

    private suspend fun executeIntentInference(job: InferenceJob.Intent) {
        try {
            Log.d("InferenceService", "Executing Intent Inference")
            ensureEngineInitialized()
            engine?.reset()
            currentConversationId = -2L
            withTimeout(45000) {
                runIntentInferenceLoop(job.userText, job.imagePaths, job.schema, job.receiver)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e("InferenceService", "Intent inference timed out after 45 seconds")
            job.receiver.send(-1, Bundle().apply { putString("error", getString(R.string.error_inference_timeout)) })
        } catch (e: CancellationException) {
            if (e.message != "HALT") throw e
            Log.i("InferenceService", "Intent inference halted successfully via schema match.")
        } catch (e: Exception) {
            Log.e("InferenceService", "Error during intent inference", e)
            job.receiver.send(-1, Bundle().apply { putString("error", e.localizedMessage ?: getString(R.string.error_ai_engine_failed)) })
        } finally {
            // The next standard turn must reload its own history: this job left the cache holding
            // an extraction prompt, and -1 is what forces that.
            currentConversationId = -1L
            currentHistory = emptyList()
            currentTools = null
        }
    }

    private suspend fun processIntentJob(job: InferenceJob.Intent) {
        if (System.currentTimeMillis() - job.enqueuedTime > 45000) {
            Log.w("InferenceService", "Intent job expired in queue, discarding.")
            job.receiver.send(-1, Bundle().apply { putString("error", getString(R.string.error_request_expired)) })
        } else {
            executeIntentInference(job)
        }
    }

    private suspend fun resetConversation(conversationId: Long, userText: String) {
        val history = fetchHistoryFromDb(conversationId)
            .filter { it.text != userText || it.timestamp < Clock.System.now().toEpochMilliseconds() - 1000 }
        setupConversation(conversationId, history)
    }

    private suspend fun executeStandardInference(job: InferenceJob.Standard) {
        try {
            ensureEngineInitialized()
            if (currentConversationId != job.conversationId) {
                resetConversation(job.conversationId, job.userText)
            }
            runInferenceLoop(job.conversationId, job.userText, job.imagePaths, job.audioPath)
        } catch (e: CancellationException) {
            if (e.message != "HALT") throw e
            Log.i("InferenceService", "Standard inference halted successfully.")
        } catch (e: Exception) {
            Log.e("InferenceService", "Inference failed, resetting engine for retry", e)
            currentConversationId = -1L
            engine?.close()
            engine = null
            try {
                ensureEngineInitialized()
                resetConversation(job.conversationId, job.userText)
                runInferenceLoop(job.conversationId, job.userText, job.imagePaths, job.audioPath)
            } catch (retryError: Exception) {
                Log.e("InferenceService", "Retry also failed", retryError)
                upsertMessageToDb(Message(
                    conversationId = job.conversationId,
                    text = getString(R.string.error_prefix, retryError.localizedMessage ?: ""),
                    role = ROLE_ERROR,
                    timestamp = Clock.System.now().toEpochMilliseconds()
                ))
            }
        }
    }

    /**
     * The system prompt for a structured-extraction job.
     *
     * A plain string now rather than a `Conversation` with its own config: there is no engine
     * object to configure, so the only thing that made the intent path special was this prompt
     * and the absence of tools.
     */
    private fun intentSystemPrompt(schema: String): String = """
        You are a highly specialized data extraction engine.
        Your sole purpose is to analyze the provided text and output a SINGLE valid JSON object that adheres STRICTLY to the provided JSON schema.
        EXTREMELY IMPORTANT:
        1. DO NOT respond with any conversational text.
        2. DO NOT include any preamble, explanation, or postscript.
        3. Output ONLY the raw JSON object.
        4. Ensure all keys and values are properly quoted.
        SCHEMA:
        $schema
        Start extraction immediately.
    """.trimIndent()

    private suspend fun runIntentInferenceLoop(
        userText: String,
        imagePaths: Array<String>,
        schema: String,
        receiver: ResultReceiver
    ) {
        val live = engine ?: return
        val images = live.encodeImages(imagePaths.toList())
        val prompt = userText + attachmentNote(imagePaths.size - images.size, 0)
        val turns = listOf(Gemma4Handle.Turn(Gemma4Handle.Role.USER, prompt, images))

        // The early halt is the whole point of streaming here: the moment a complete object that
        // satisfies the schema has arrived, there is nothing to gain by letting the model write
        // its closing remarks. Preserved from the litertlm path unchanged.
        var sent = false
        val reply = withContext(Dispatchers.IO) {
            live.ask(turns, intentSystemPrompt(schema), tools = null) { partial ->
                val candidate = tryExtractLargestJson(partial)
                if (candidate != null &&
                    JsonSchemaValidator.validateJsonAgainstSchema(candidate, schema) == null
                ) {
                    Log.i("InferenceService", "Valid JSON extracted and verified. Halting.")
                    receiver.send(0, Bundle().apply { putString("json_result", candidate) })
                    sent = true
                    false
                } else {
                    true
                }
            }
        }
        if (sent) return

        val finalJson = reply?.let { tryExtractLargestJson(it) }
        val error = finalJson?.let { JsonSchemaValidator.validateJsonAgainstSchema(it, schema) }
        if (finalJson != null && error == null) {
            receiver.send(0, Bundle().apply { putString("json_result", finalJson) })
        } else {
            receiver.send(-1, Bundle().apply {
                putString("error", error ?: getString(R.string.error_ai_json_schema_mismatch))
            })
        }
    }

    private fun tryExtractLargestJson(text: String): String? {
        val start = text.indexOf('{')
        if (start == -1) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        val candidate = text.substring(start, i + 1)
                        return try {
                            Json.parseToJsonElement(candidate)
                            candidate
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
            }
        }
        return null
    }

    private suspend fun ensureEngineInitialized() {
        if (engine?.isReady == true) return

        val directory = applicationContext.getExternalFilesDir(null)
            ?: throw Exception("no external files directory")
        for (name in Gemma4Handle.FILES) {
            val file = File(directory, name)
            if (!file.isFile) throw Exception("$name is missing from $directory")
        }

        val started = Gemma4Engine(directory)
        val loaded = withContext(Dispatchers.IO) { started.ensureLoaded() }
        if (!loaded) {
            started.close()
            throw Exception("gemma4 could not start on this device")
        }
        engine?.close()
        engine = started
        dumpPrefix()
    }

    /** The system prompt, from settings or the default. */
    /**
     * Write the fixed prompt prefix to disk, for `bake_gemma4_prefix` to precompute.
     *
     * The tool declarations are built by reflection over [AssistantToolSet], so the desktop
     * baker cannot produce them - it has no Kotlin. Rather than transcribe them into the baker
     * and let the two drift silently, the app emits exactly the text it would send and the baker
     * consumes that. Drift then becomes impossible by construction.
     *
     * Costs one small file write per load. Nothing reads it on device; it is pulled off with
     * `adb` when the cache is rebuilt, which happens when the prompt or the tools change.
     */
    private fun dumpPrefix() {
        runCatching {
            val declarations =
                ToolRegistry(AssistantToolSet(applicationContext, memoryDao, messageDao, 0L))
                    .declarations
            // Exactly `Gemma4Handle.render`'s opening, with no conversation and no generation
            // prompt - the part that never changes.
            val prefix = "<bos><|turn>system\n" + DEFAULT_SYSTEM_PROMPT +
                Gemma4Handle.declareTools(declarations)
            val directory = applicationContext.getExternalFilesDir(null) ?: return
            File(directory, "prefix.txt").writeText(prefix)
            Log.i("InferenceService", "wrote prefix.txt, ${prefix.length} chars, " +
                "${declarations.size} tools")
        }.onFailure { Log.w("InferenceService", "cannot write prefix.txt: $it") }
    }

    /// The system prompt, which is no longer configurable.
    ///
    /// It is baked into a precomputed KV cache served alongside the weights, so the ~1,100
    /// positions of system block and tool declarations are never evaluated on device. That only
    /// works if the text is fixed: a prompt the user could edit would invalidate the cache, and
    /// silently - the model would attend over keys for a prompt it was not given.
    private fun systemPrompt(): String = DEFAULT_SYSTEM_PROMPT

    /**
     * A note appended to the user's text for attachments the model could not read.
     *
     * Both towers are optional downloads and both drop what they cannot encode, so this covers a
     * file that would not decode, an attachment the tower refused, and a device that never
     * fetched that tower - `Gemma4Engine.canSeeImages` and `canHearAudio` say which.
     *
     * The note exists rather than dropping the attachment silently because a model told nothing
     * answers as though it had seen the picture. Saying plainly that it did not gets "I can't see
     * that image" instead of a confident invention.
     */
    private fun attachmentNote(unreadImages: Int, unheardAudio: Int): String {
        val parts = mutableListOf<String>()
        if (unreadImages > 0) {
            parts += "[$unreadImages image(s) attached that could not be read]"
        }
        if (unheardAudio > 0) {
            parts += "[$unheardAudio audio clip(s) attached that could not be heard]"
        }
        return if (parts.isEmpty()) "" else parts.joinToString("\n", prefix = "\n")
    }

    private suspend fun setupConversation(id: Long, history: List<Message>) {
        currentHistory = history
            .filter { it.role != ROLE_ERROR }
            .map { msg ->
                val role =
                    if (msg.role == "assistant") Gemma4Handle.Role.MODEL else Gemma4Handle.Role.USER
                Gemma4Handle.Turn(role, msg.text)
            }
        currentTools =
            ToolRegistry(AssistantToolSet(applicationContext, memoryDao, messageDao, id))
        currentConversationId = id
        engine?.reset()
    }

    /**
     * A note appended to the user's text when the start of the conversation has been dropped.
     *
     * The same reasoning as [attachmentNote], for the same reason: a model told nothing answers
     * as though it still remembers, and then contradicts something it said with no cause the user
     * can see. Saying so plainly gets "I no longer have that earlier part of our conversation".
     */
    private fun historyNote(dropped: Int): String =
        "\n[$dropped earlier message(s) in this conversation are no longer available to you]"

    /**
     * [history] with its oldest exchanges dropped until [turn] can actually be sent.
     *
     * [Gemma4Handle.generate] refuses an over-long prompt rather than shortening it, and a
     * refusal here is not one bad turn but a dead conversation: the failure is written back as a
     * message, so without this the next prompt is longer still.
     *
     * # Two passes, and history yields to the clip before the clip yields to history
     *
     * The first pass measures the turn WITH its audio at full length, so old turns are dropped to
     * seat the clip whole - down to [HISTORY_FLOOR]. Trimming a clip costs its tail, which is
     * where a spoken request usually keeps its question; dropping a six-turn-old exchange costs
     * much less. Past the floor the priority inverts and `Gemma4Handle` trims the clip instead.
     *
     * The second pass measures TEXT ONLY with no floor. It is what guarantees the turn is
     * sendable, and it is unconditional: a turn that cannot be sent is worse than one that has
     * forgotten something. When the fixed prefix alone exceeds the ceiling this empties history
     * entirely, which is correct - keeping one exchange there would cost the reply far more than
     * it costs to have forgotten it.
     *
     * Dropped in user+model pairs, so what survives is still a conversation rather than a reply
     * whose question has gone. A leading model turn, which is what a split pair leaves behind,
     * goes on its own.
     */
    private fun evictToFit(
        live: Gemma4Engine,
        history: List<Gemma4Handle.Turn>,
        turn: Gemma4Handle.Turn,
        system: String?,
    ): List<Gemma4Handle.Turn> {
        val bare = turn.copy(audio = emptyList())
        val ceiling = live.promptCeiling()
        // `positionsFor` trims audio into whatever the text leaves, so asking it about the whole
        // turn would always answer "fits" and history would never yield. The clip is added at its
        // full length instead, which is the question actually being asked: does it fit UNTRIMMED?
        val clip = turn.audio.sumOf { it.size / Gemma4Handle.SOFT_TOKEN_WIDTH }
        fun text(rest: List<Gemma4Handle.Turn>) =
            live.positionsFor(rest + bare, system, currentTools)

        var kept = evict(history, HISTORY_FLOOR) { text(it) + clip <= ceiling }
        kept = evict(kept) { text(it) <= ceiling }
        if (kept.size < history.size) {
            Log.w(
                "InferenceService",
                "dropped ${history.size - kept.size} of ${history.size} turns to fit $ceiling",
            )
        }
        return kept
    }

    private suspend fun runInferenceLoop(
        conversationId: Long,
        userText: String,
        imagePaths: Array<String>,
        audioPath: String?
    ) {
        val live = engine ?: return
        val aiMsgId = upsertMessageToDb(Message(
            conversationId = conversationId,
            text = "...",
            role = "assistant",
            timestamp = Clock.System.now().toEpochMilliseconds()
        ))

        val images = live.encodeImages(imagePaths.toList())
        val unread = imagePaths.size - images.size
        // A path whose file is not there is still an attachment the user made, and it is counted
        // as unheard rather than discarded. Dropping it silently is how a recorder that never
        // wrote its file, a revoked permission or a cleared cache all present as "audio is simply
        // not supported" - the user attached something, the model was told nothing, and no error
        // exists anywhere to explain it.
        val attached = listOfNotNull(audioPath)
        val present = attached.filter { File(it).exists() }
        if (present.size < attached.size) {
            Log.w("InferenceService", "audio was attached but no file is on disk: $audioPath")
        }
        val audio = live.encodeAudio(present)
        val system = systemPrompt()
        val attachments = attachmentNote(unread, attached.size - audio.size)
        fun turnOf(note: String) = Gemma4Handle.Turn(
            Gemma4Handle.Role.USER, userText + attachments + note, images, audio,
        )

        // Fitted at most twice: the note telling the model it has forgotten something costs
        // positions of its own, so once anything has been dropped the prompt is measured again
        // with the note in place. The second pass may drop one more exchange; the count in the
        // note is taken from the final result, and a one-digit change does not alter its length.
        var kept = evictToFit(live, currentHistory, turnOf(""), system)
        if (kept.size < currentHistory.size) {
            kept = evictToFit(
                live, currentHistory, turnOf(historyNote(currentHistory.size - kept.size)), system,
            )
        }
        val dropped = currentHistory.size - kept.size
        val turns = kept + turnOf(if (dropped > 0) historyNote(dropped) else "")

        // Streaming is a callback rather than a Flow now, and Room is still the channel the UI
        // watches - so each partial is written straight through, exactly as before.
        var stopped = false
        val reply = withContext(Dispatchers.IO) {
            live.ask(turns, system, currentTools) { partial ->
                if (halt) {
                    halt = false
                    stopped = true
                    return@ask false
                }
                if (partial.isNotBlank()) {
                    runBlocking { updateMessageInDb(aiMsgId, partial) }
                }
                true
            }
        }

        if (stopped) {
            messageDao.deleteById(aiMsgId)
            throw CancellationException("HALT")
        }
        if (reply == null) {
            messageDao.deleteById(aiMsgId)
            // Native logs the reason under the `ModelRunner` tag before returning failure - a
            // timed-out fence, a full context, a bad token. This layer only sees the null, so it
            // says where the reason is rather than inventing one. "Produced nothing" on its own
            // sent a real `wait_for_fences TIMEOUT` to the user as a blank reply.
            throw Exception("the assistant failed; see the ModelRunner log for the reason")
        }

        // `set_conversation_title` writes to a static rather than returning, so it is drained
        // after the turn rather than inside the stream.
        newTitle?.let {
            updateTitleInDb(conversationId, it)
            newTitle = null
        }

        if (reply.isBlank()) {
            messageDao.deleteById(aiMsgId)
        } else {
            updateMessageInDb(aiMsgId, reply)
            currentHistory = turns + Gemma4Handle.Turn(Gemma4Handle.Role.MODEL, reply)
        }
    }

    private suspend fun fetchHistoryFromDb(id: Long): List<Message> = messageDao.getByConversation(id)
    private suspend fun upsertMessageToDb(msg: Message): Long = messageDao.upsert(msg)
    private suspend fun updateMessageInDb(id: Long, text: String) {
        val existing = messageDao.getById(id) ?: return
        upsertMessageToDb(existing.copy(text = text))
    }
    private suspend fun updateTitleInDb(id: Long, title: String) {
        val oldConversation = conversationDao.getById(id)
        if (oldConversation != null) {
            conversationDao.upsert(oldConversation.copy(title = title))
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        engine?.close()
        engine = null
        super.onDestroy()
    }
}
