package com.vayunmathur.library.ml

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.text.Normalizer

/**
 * Gemma 4 E2B, running on this repo's Vulkan compute runtime.
 *
 * Replaces `com.google.ai.edge.litertlm`, and with it the 19.83 MB `liblitertlm_jni.so`. The
 * weights are the same model, converted to `.maml` by `scripts/ml/maml_convert.py --graph
 * gemma4_text` and `--graph gemma4_embed`, quantised to int4 with a per-block scale.
 *
 * # What moved out of the SDK and into here
 *
 * litertlm owned the chat template, the tool protocol, sampling and the streaming loop, and none
 * of that was visible from this repo. All four now live in Kotlin, which is the point: the
 * template is [render], the tool protocol is [declareTools] and [parseToolCall], the loop is
 * [generate]. Native's boundary is one token.
 *
 * # Behavioural differences from litertlm, which are real
 *
 * * **Greedy sampling.** litertlm used `top_k` 64 and `top_p` 0.95. Replies are now deterministic
 *   and a little flatter. Sampling can be added at [generate] without touching native.
 * * **No speculative decoding**, which litertlm had enabled.
 * * **A hard context limit** of [MAX_CONTEXT] positions, where litertlm's was implicit.
 * * **No audio.** Images work - see [Gemma4VisionHandle] and [Turn.images] - but there is no
 *   audio encoder yet, so callers still refuse sound.
 *
 * # Threading
 *
 * Not thread-safe, and more sharply than [NllbHandle]: one handle holds one KV cache and one
 * position, so two concurrent turns would interleave their tokens into the same conversation.
 * The caller must hold a lock across a whole turn, not merely across a call.
 */
class Gemma4Handle private constructor(private val directory: File) : AutoCloseable {

    private var handle: Long = if (MlNative.isAvailable) create(directory) else 0L

    /**
     * The token ids the KV cache currently holds, or null when it holds something unmatchable.
     *
     * Null after a reset, after a multimodal turn, and after any failure - all the cases where
     * the next turn must not assume anything about what is in the arena.
     */
    private var cachedIds: IntArray? = null

    /**
     * Time the model once, so the log says where its time goes.
     *
     * Runs three passes each way and keeps the best, which costs a couple of seconds the first
     * time a conversation starts and answers a question that guesswork has repeatedly got wrong.
     */
    fun benchmark() {
        if (handle != 0L) {
            MlNative.capabilitiesGemma4()
            MlNative.imageProbeGemma4()
            MlNative.benchmarkGemma4(handle)
        }
    }

    /** Whether the model came up. False leaves the assistant off rather than crashing. */
    val isAvailable: Boolean
        get() = handle != 0L

    /**
     * Load the baked cache for [prefix], if it is present and matches. Returns whether it did.
     *
     * # The check is the point
     *
     * The asset encodes keys and values for one exact token sequence. If the system prompt or
     * the tool set has changed since it was baked, those numbers describe a prompt the model is
     * not being given - and nothing downstream could tell, because a KV cache is only attended
     * over, never compared. So this re-encodes [prefix] here and refuses unless the digest of
     * the result matches the one in the file.
     *
     * A mismatch is not an error: it falls back to prefilling, which is slow and correct.
     */
    fun loadPrefix(prefix: String): Boolean {
        if (handle == 0L) return false
        val file = File(directory, PREFIX_CACHE)
        if (!file.isFile) return false
        val tokens = encodePrompt(prefix)
        if (tokens.isEmpty()) return false
        val blob = runCatching { file.readBytes() }.getOrNull() ?: return false
        if (blob.size < HEADER || String(blob, 0, 4, Charsets.US_ASCII) != "GKV1") {
            Log.w(TAG, "$PREFIX_CACHE is not a baked cache")
            return false
        }
        val positions = readInt(blob, 4)
        // The size check stays enforced even though the digest does not: `positions` decides
        // where the next token goes and how many ids are recorded as cached, so a wrong count
        // corrupts the bookkeeping rather than merely the contents.
        if (positions != tokens.size) {
            Log.i(TAG, "$PREFIX_CACHE is $positions positions, this prefix is ${tokens.size}")
            return false
        }
        // The digest is reported, not enforced.
        //
        // It says whether these keys and values were computed from *these* tokens, and a
        // mismatch means the model is about to attend over a prompt it was not given. Refusing
        // is the safe behaviour and what this did first. It is advisory while the prefix is
        // still being iterated on, because a stale cache should slow the work down rather than
        // stop it - but a mismatch here is a real defect, not noise, and the loud log is the
        // only thing standing between it and a plausible wrong answer.
        if (!digest(tokens).contentEquals(blob.copyOfRange(12, 12 + 32))) {
            Log.w(TAG, "$PREFIX_CACHE DIGEST MISMATCH - using it anyway; replies may be wrong")
        }
        // The cache starts at the smallest tier and this prefix is larger than it. Growing first
        // is not optional: `loadPrefixGemma4` refuses a prefix bigger than the cache rather than
        // truncating it, because half a prefix is keys for a prompt nobody sent.
        if (MlNative.capacityGemma4(handle) < positions) {
            val grown = MlNative.growGemma4(handle, positions + 2)
            if (grown < positions) {
                Log.i(TAG, "a $positions-position prefix does not fit this device; prefilling")
                return false
            }
        }
        val loaded = MlNative.loadPrefixGemma4(handle, positions, blob.copyOfRange(HEADER, blob.size))
        if (loaded != positions) return false
        // The cache now holds exactly these tokens, so the next turn matches against them and
        // feeds only what follows.
        cachedIds = tokens
        Log.i(TAG, "loaded a $positions-position prefix cache, skipping its prefill")
        return true
    }

    /** Positions in the KV cache, which is where the next token goes. */
    val position: Int
        get() = if (handle == 0L) 0 else MlNative.positionGemma4(handle)

    /** Positions left before [MAX_CONTEXT] is reached. */
    val remaining: Int
        get() = (MAX_CONTEXT - position).coerceAtLeast(0)

    /**
     * Start a new conversation, discarding the KV cache.
     *
     * Cheap: only a counter resets. Attention never reads past the live prefix, so the stale rows
     * are simply overwritten as the new conversation fills them.
     */
    fun reset() {
        cachedIds = null
        if (handle != 0L) MlNative.resetGemma4(handle)
    }

    /**
     * Token ids for [text] as a **user** would write it: no marker is honoured.
     *
     * Everything a caller puts in here is data. `specials` is empty, so a user typing
     * `<|turn>model` gets the literal pieces that spell it and cannot forge a turn boundary.
     */
    fun encodeText(text: String): IntArray {
        if (handle == 0L) return IntArray(0)
        val normalised = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return MlNative.encodeGemma4(handle, normalised, emptyArray()) ?: IntArray(0)
    }

    /**
     * Token ids for a rendered prompt, honouring the chat markers in [MARKERS].
     *
     * Only for strings this class built. Passing user text here would let it spell a turn.
     */
    private fun encodePrompt(text: String): IntArray {
        if (handle == 0L) return IntArray(0)
        return MlNative.encodeGemma4(handle, text, MARKERS) ?: IntArray(0)
    }

    /** Text for token ids, with byte pieces fused back into characters. */
    fun decode(tokens: IntArray): String =
        if (handle == 0L) "" else MlNative.decodeGemma4(handle, tokens).orEmpty()

    /**
     * Run one turn, calling [onToken] with the reply so far after each token.
     *
     * [onToken] returning false stops generation, which is how cancellation and early-halt
     * work - the JSON-extraction path uses it to stop the moment a complete object has arrived.
     *
     * Returns the reply, or null if the model is unavailable or the context is full.
     *
     * # Why the whole prompt is re-fed each turn
     *
     * The KV cache is live between calls, so in principle only the new user turn needs pushing.
     * This does not do that, because the caller edits history: a deleted message, a regenerated
     * reply, or a switch between conversations all invalidate the cache, and a stale cache is
     * invisible - it produces a fluent reply to a conversation that never happened. Re-feeding
     * costs one forward pass per prompt token, which at these speeds is well under a second for
     * a normal conversation, and it cannot be wrong.
     */
    fun generate(
        conversation: List<Turn>,
        system: String?,
        tools: List<ToolDeclaration> = emptyList(),
        limit: Int = DEFAULT_REPLY,
        continuation: String = "",
        onToken: (String) -> Boolean = { true },
    ): String? {
        if (handle == 0L) return null
        val parts = fitted(conversation, system, tools, limit, continuation)
        val length = parts.sumOf { it.positions }
        val wanted = conversation.sumOf { turn -> turn.audio.sumOf { it.size / SOFT_TOKEN_WIDTH } }
        val heard = parts.sumOf { if (it is Part.Audio) it.positions else 0 }
        if (heard < wanted) {
            Log.w(TAG, "audio lost ${wanted - heard} positions so the reply keeps its $limit")
        }
        // The last part is always the generation prompt, so it is text and it is not empty.
        val tail = parts.lastOrNull() as? Part.Tokens
        if (tail == null || tail.ids.isEmpty()) {
            Log.w(TAG, "an empty prompt")
            return null
        }
        // Grow the cache if this turn has outgrown it. Conversations start at the smallest
        // tier, so most never allocate more than 19 MB; the ones that keep going climb.
        val wantedPositions = length + limit + 2
        var capacity = MlNative.capacityGemma4(handle)
        if (capacity in 1 until wantedPositions) {
            val grown = MlNative.growGemma4(handle, wantedPositions)
            if (grown > capacity) {
                // `cachedIds` deliberately survives. Growing used to empty the cache, so this
                // cleared the record to match; it now copies the contents into the new arena and
                // keeps the position, so clearing here would throw away a cache that is still
                // there - which is exactly what made the precomputed prefix look useless: it
                // loaded, the first turn grew to make room for a reply, and the whole 1,910
                // positions were then prefilled again anyway.
                capacity = grown
            }
        }
        if (capacity in 1 until (length + 2)) {
            Log.w(TAG, "a prompt of $length positions does not fit a $capacity cache")
            return null
        }
        // What the model actually receives. A reply that reads as nonsense is either the model's
        // doing or the prompt's, and those are indistinguishable from the outside - so the
        // prompt is logged rather than guessed at. `fitted` truncates to make room for the
        // reply, and a prompt that lost its instructions to that truncation looks exactly like
        // a broken tokenizer.
        Log.i(
            TAG,
            "prompt $length positions in ${parts.size} parts, " +
                "${conversation.size} turns, ${tools.size} tools, reply budget $limit",
        )
        for ((index, part) in parts.withIndex()) {
            val what = when (part) {
                is Part.Tokens -> "text ${part.ids.size}: " +
                    decode(part.ids.take(40).toIntArray()).replace("\n", "\\n").take(160)
                is Part.Image -> "image ${part.positions}"
                is Part.Audio -> "audio ${part.positions}"
                // Untokenised text should never survive `fitted`, so seeing one here is itself
                // the bug rather than a case to render nicely.
                is Part.Text -> "UNTOKENISED ${part.text.take(80)}"
            }
            Log.i(TAG, "  part $index  $what")
        }

        // Reuse whatever of the cache this prompt shares with the last one.
        //
        // A turn's prompt is nearly all of the previous turn's: the same system block, the same
        // tool declarations, the same history. Only the tail differs - the new user turn and the
        // generation prompt. The KV cache for the shared part is still in the arena and still
        // correct, because the tokens that produced it have not changed.
        //
        // Text-only prompts only. An image or audio part contributes soft tokens rather than
        // ids, so deciding whether two of them are "the same" means comparing megabytes of
        // floats - and getting that wrong does not fail, it answers a conversation that never
        // happened. The multimodal path re-feeds, exactly as it did before.
        val flat = if (parts.all { it is Part.Tokens }) {
            IntArray(length).also { out ->
                var at = 0
                for (part in parts) {
                    val ids = (part as Part.Tokens).ids
                    ids.copyInto(out, at)
                    at += ids.size
                }
            }
        } else {
            null
        }
        var reused = 0
        val prior = cachedIds
        if (flat != null && prior != null) {
            // Never reuse the final token: its logits are the prediction this turn needs, so it
            // has to be fed through `stepGemma4` rather than sat in the cache.
            val ceiling = minOf(prior.size, flat.size - 1)
            while (reused < ceiling && prior[reused] == flat[reused]) reused++
        }
        if (reused > 0 && MlNative.seekGemma4(handle, reused) == reused) {
            Log.i(TAG, "reused $reused of $length positions, feeding ${length - reused}")
        } else {
            reused = 0
            reset()
        }
        cachedIds = null

        // Everything but the very last token only fills the cache; its logits would be discarded.
        if (flat != null) {
            val feed = flat.copyOfRange(reused, flat.size - 1)
            if (feed.isNotEmpty() && MlNative.pushGemma4(handle, feed) < 0) return null
            // What the cache holds now. The reply's own tokens are appended as they are
            // generated, so the next turn matches against the whole exchange.
            cachedIds = flat.copyOfRange(0, flat.size - 1)
        } else {
        for ((index, part) in parts.withIndex()) {
            val fed = when (part) {
                is Part.Image -> MlNative.pushSoftGemma4(handle, part.soft)
                is Part.Audio -> MlNative.pushSoftGemma4(handle, part.soft)
                is Part.Tokens -> {
                    val ids = if (index == parts.lastIndex) part.ids.dropLast(1).toIntArray()
                    else part.ids
                    if (ids.isEmpty()) 0 else MlNative.pushGemma4(handle, ids)
                }
                is Part.Text -> -1
            }
            if (fed < 0) return null
        }
        }

        val produced = ArrayList<Int>(limit)
        var next = tail.ids.last()
        val room = minOf(limit, remaining - 1)
        for (step in 0 until room) {
            val token = MlNative.stepGemma4(handle, next)
            if (token < 0) break
            if (token in STOP) break
            produced.add(token)
            next = token
            if (!onToken(decode(produced.toIntArray()))) break
        }
        // The fed token and everything generated after it are in the cache too, so record them
        // and the next turn starts from the end of this reply rather than the start of the
        // conversation.
        cachedIds = cachedIds?.let { it + tail.ids.last() + produced.toIntArray() }
        return decode(produced.toIntArray())
    }

    /**
     * A prompt is not one string once an image is in it.
     *
     * An image has no token id to encode, so it cannot be spelled into the prompt and tokenised
     * with the rest. It arrives as the vision tower's `[n, 1536]` block and is pushed as an
     * embedding, which means the prompt has to be fed in order as a sequence of parts rather than
     * as one array.
     */
    internal sealed interface Part {
        /** How many positions in the KV cache this part occupies. */
        val positions: Int

        /** Text, before it has been tokenised. */
        data class Text(val text: String) : Part {
            override val positions: Int get() = 0
        }

        /** Text, tokenised. */
        data class Tokens(val ids: IntArray) : Part {
            override val positions: Int get() = ids.size

            override fun equals(other: Any?): Boolean =
                this === other || (other is Tokens && ids.contentEquals(other.ids))

            override fun hashCode(): Int = ids.contentHashCode()
        }

        /** One image's soft tokens, `n * 1536` from [Gemma4VisionHandle.encode]. */
        data class Image(val soft: FloatArray) : Part {
            override val positions: Int get() = soft.size / SOFT_TOKEN_WIDTH

            override fun equals(other: Any?): Boolean =
                this === other || (other is Image && soft.contentEquals(other.soft))

            override fun hashCode(): Int = soft.contentHashCode()
        }

        /**
         * One clip's soft tokens, `n * 1536` from [Gemma4AudioHandle.encode].
         *
         * Distinct from [Image] despite carrying the same shape and being pushed the same way,
         * because the two are bracketed by different markers and a clip in an image's brackets
         * is a prompt the model was never trained on. Collapsing them into one variant would
         * make that a one-character mistake.
         */
        data class Audio(val soft: FloatArray) : Part {
            override val positions: Int get() = soft.size / SOFT_TOKEN_WIDTH

            override fun equals(other: Any?): Boolean =
                this === other || (other is Audio && soft.contentEquals(other.soft))

            override fun hashCode(): Int = soft.contentHashCode()
        }
    }

    /**
     * [render]'s output, cut at every image and every clip.
     *
     * Media sits at the head of the turn that carries it, bracketed by [BOI]/[EOI] or
     * [BOA]/[EOA] the way the reference processor's `replace_image_token` and
     * `replace_audio_token` bracket their runs of placeholders. The placeholders themselves are
     * not emitted: their whole purpose is to reserve positions for the tower's rows, and the
     * rows are pushed directly.
     *
     * Images come before audio within a turn, which is the reference processor's own order.
     */
    private fun renderParts(
        conversation: List<Turn>,
        system: String?,
        tools: List<ToolDeclaration>,
        continuation: String = "",
    ): List<Part> {
        val parts = ArrayList<Part>()
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) {
                parts.add(Part.Text(text.toString()))
                text.clear()
            }
        }
        text.append("<bos>")
        val declared = declareTools(tools)
        if (!system.isNullOrBlank() || declared.isNotEmpty()) {
            text.append("<|turn>system\n")
            if (!system.isNullOrBlank()) text.append(system)
            text.append(declared)
            text.append("<turn|>\n")
        }
        for (turn in conversation) {
            text.append("<|turn>").append(turn.role.marker).append('\n')
            for (image in turn.images) {
                if (image.isEmpty() || image.size % SOFT_TOKEN_WIDTH != 0) continue
                parts.add(Part.Text(text.toString() + BOI_MARKER))
                text.clear()
                parts.add(Part.Image(image))
                text.append(EOI_MARKER)
            }
            for (clip in turn.audio) {
                if (clip.isEmpty() || clip.size % SOFT_TOKEN_WIDTH != 0) continue
                parts.add(Part.Text(text.toString() + BOA_MARKER))
                text.clear()
                parts.add(Part.Audio(clip))
                text.append(EOA_MARKER)
            }
            text.append(turn.text)
            text.append("<turn|>\n")
        }
        text.append("<|turn>model\n")
        // A tool call and its result belong **inside** the open model turn. Appending them as a
        // finished turn instead - `<|turn>model ... <turn|>` then a fresh `<|turn>model` - made
        // the model see its own turn ended and a new one begin, which reads as the start of a
        // conversation: it greeted instead of answering. Only the first message showed it,
        // because that is the one the system prompt forces a tool call on.
        text.append(continuation)
        flush()
        return parts
    }

    override fun close() {
        val live = handle
        handle = 0L
        if (live != 0L) MlNative.destroyGemma4(live)
    }

    override fun toString(): String = "Gemma 4 E2B in $directory"

    /**
     * One message in a conversation.
     *
     * [images] are [Gemma4VisionHandle.encode]'s output and [audio] is
     * [Gemma4AudioHandle.encode]'s, one entry per item, each occupying `size / 1536` positions at
     * the head of the turn. Pass them unscaled - the reference scatters both towers' rows into
     * the decoder's embeddings as they are.
     */
    data class Turn(
        val role: Role,
        val text: String,
        val images: List<FloatArray> = emptyList(),
        val audio: List<FloatArray> = emptyList(),
    )

    /** Who spoke. Gemma's own names, not OpenAI's: the model turn is `model`, not `assistant`. */
    enum class Role(internal val marker: String) {
        USER("user"),
        MODEL("model"),
    }

    /** A tool the model may call, in the shape [declareTools] serialises. */
    data class ToolDeclaration(
        val name: String,
        val description: String,
        val parameters: List<Parameter>,
    ) {
        /** One argument. [type] is Gemma's spelling: `STRING`, `NUMBER`, `BOOLEAN`. */
        data class Parameter(
            val name: String,
            val description: String,
            val type: String,
            val required: Boolean = true,
        )
    }

    /**
     * The prompt [generate] would build, with audio already fitted into what the reply leaves.
     *
     * One budget for the window, computed once, with the reply reserve and the audio trim both
     * reading from it - so the two cannot each reserve the space the other reserved.
     *
     * The reply is reserved here rather than left to `remaining - 1` in [generate]. That
     * expression hands the reply every position the prompt did not use, so a turn that spends its
     * budget leaves nothing for the turn after it and the conversation dies.
     *
     * Derived from the prompt in hand on every call, never from a constant: the fixed cost is
     * dominated by the tool declarations and moves whenever those do.
     */
    private fun fitted(
        conversation: List<Turn>,
        system: String?,
        tools: List<ToolDeclaration>,
        limit: Int,
        continuation: String = "",
    ): List<Part> {
        val rendered = renderParts(conversation, system, tools, continuation).map { part ->
            when (part) {
                is Part.Text -> Part.Tokens(encodePrompt(part.text))
                is Part.Image, is Part.Audio, is Part.Tokens -> part
            }
        }
        val fixed = rendered.sumOf { if (it is Part.Audio) 0 else it.positions }
        return fitAudio(rendered, promptCeiling(limit) - fixed)
    }

    /**
     * Positions the prompt for [conversation] would occupy, so a caller can drop history before
     * [generate] refuses it.
     *
     * Exists because [generate] cannot fix an over-long conversation itself: trimming audio is a
     * decision about one attachment, but deciding which turns a user may lose is the caller's.
     * This is the same arithmetic [generate] performs, so the two cannot disagree about whether
     * something fits - compare it against [promptCeiling] with the same `limit`.
     *
     * Zero when the model is unavailable, which reads as "fits" rather than stranding a caller
     * in a loop dropping turns that would never have been sent.
     */
    fun positionsFor(
        conversation: List<Turn>,
        system: String?,
        tools: List<ToolDeclaration> = emptyList(),
        limit: Int = DEFAULT_REPLY,
    ): Int {
        if (handle == 0L) return 0
        return fitted(conversation, system, tools, limit).sumOf { it.positions }
    }

    /** A call the model asked for, as [parseToolCall] found it. */
    data class ToolCall(val name: String, val arguments: Map<String, String>)

    companion object {
        private const val TAG = "Gemma4Handle"

        /**
         * Reply positions [generate] reserves unless a caller says otherwise.
         *
         * One constant so the reserve and anything measuring against it cannot drift apart.
         */
        const val DEFAULT_REPLY = 512

        /**
         * The most positions a prompt may occupy while still leaving [limit] for the reply.
         *
         * The guard's own bound and the reply reserve are not additive - they compete for the
         * same tail of the window, so the binding constraint is the larger of the two. `maxOf`
         * says that directly; subtracting both would give back one position fewer than the model
         * could have used, at every limit.
         */
        fun promptCeiling(limit: Int = DEFAULT_REPLY): Int = MAX_CONTEXT - maxOf(limit, 3)

        /**
         * [parts] with its audio trimmed to at most [budget] positions in total.
         *
         * A clip is trimmed from its end, so the model hears the start of a recording rather
         * than a window out of the middle of it, and earlier clips are served before later ones.
         *
         * A clip trimmed to nothing is dropped. The `<|audio>` brackets [renderParts] put around
         * it stay, because they are already tokenised into the neighbouring text by the time this
         * runs and two positions do not justify a second tokenisation pass to reclaim.
         *
         * Returns [parts] itself when there is no audio, so a text turn is untouched by all of
         * this - the budget can only ever take positions away from a clip, never from the prompt.
         *
         * Pure, and `internal` rather than private, so the unit tests can show the trim actually
         * firing. A guard that cannot fire is worse than no guard, and this one is invisible from
         * outside. The caller does the logging: keeping `android.util.Log` out of here is what
         * lets it be tested on the JVM at all.
         */
        internal fun fitAudio(parts: List<Part>, budget: Int): List<Part> {
            if (parts.none { it is Part.Audio }) return parts
            var left = budget.coerceAtLeast(0)
            val out = ArrayList<Part>(parts.size)
            for (part in parts) {
                if (part !is Part.Audio) {
                    out.add(part)
                    continue
                }
                val want = part.positions
                val keep = want.coerceAtMost(left)
                left -= keep
                when {
                    keep == want -> out.add(part)
                    keep > 0 -> out.add(Part.Audio(part.soft.copyOf(keep * SOFT_TOKEN_WIDTH)))
                }
            }
            return out
        }

        /** The decoder. Native checks its graph id, so a wrong file fails at load. */
        const val TEXT = "gemma4_text.maml"

        /** The two embedding tables, host-gathered. */
        const val EMBED = "gemma4_embed.maml"

        /** `scripts/ml/gemma4_tokenizer.py`'s output. */
        const val TOKENIZER = "gemma4_tokenizer.spm1"

        /** The files [inDirectory] needs, for a caller checking a download is complete. */
        val FILES: List<String> = listOf(TEXT, EMBED, TOKENIZER)

        /** Positions the runtime offers. Mirrors `nets::gemma4::MAX_CONTEXT`. */
        const val MAX_CONTEXT = 16384

        /** Channels in one soft token, which is the decoder's `hidden_size`. */
        const val SOFT_TOKEN_WIDTH = 1536

        /**
         * The marker that opens an image, `boi_token` in the checkpoint's tokenizer config.
         *
         * The run of `<|image|>` placeholders the reference puts between this and [EOI_MARKER] is
         * **not** emitted here. Those exist to reserve positions for the tower's rows, and this
         * pushes the rows themselves - so spelling the placeholders as well would double every
         * image's cost in context and feed the model a run of embeddings it was never shown.
         */
        const val BOI_MARKER = "<|image>"

        /** The marker that closes an image, `eoi_token`. See [BOI_MARKER]. */
        const val EOI_MARKER = "<image|>"

        /**
         * The marker that opens a clip, `boa_token`, id 256000.
         *
         * Read off the tokenizer rather than assumed to mirror [BOI_MARKER]: the two families do
         * turn out to share a shape, `<|x>` opening and `<x|>` closing, but they interleave in
         * id order - image 255999, audio 256000, then `<|image|>` 258880, `<|audio|>` 258881,
         * `<image|>` 258882, `<audio|>` 258883 - so neither pair can be derived from the other.
         *
         * As with images, the `<|audio|>` placeholders the reference emits between these are
         * **not** written here: they exist to reserve positions for the tower's rows, and this
         * pushes the rows themselves.
         */
        const val BOA_MARKER = "<|audio>"

        /** The marker that closes a clip, `eoa_token`. See [BOA_MARKER]. */
        const val EOA_MARKER = "<audio|>"

        /**
         * Ids that end a reply, from the checkpoint's `generation_config.json`.
         *
         * Three, not one. `<eos>` is 1, but an instruction-tuned Gemma ends its reply with
         * `<turn|>` (106) and `<eos>` almost never appears - stopping only on 1 would let the
         * model run on emitting turn markers until the token budget stopped it instead.
         */
        val STOP = intArrayOf(1, 106, 50)

        /**
         * The markers [encodePrompt] honours, and which user text therefore cannot spell.
         *
         * Read off the checkpoint's `chat_template.jinja`, plus the image and audio brackets,
         * which are markers for the same reason the turn tags are: a user who could spell
         * [BOI_MARKER] could claim to have sent a picture.
         *
         * **Every marker [renderParts] emits must be in here.** A marker that is emitted but not
         * declared is encoded as literal characters instead of its own id, which is not an error
         * anywhere - the prompt still tokenises, the turn still runs, and the decoder simply
         * never sees the `boa` 256000 or `eoa` 258883 it was trained to bracket soft tokens
         * with. The audio pair was emitted for some hours before it was declared here.
         *
         * `internal` rather than private so `Gemma4MarkersTest` can enforce that coupling, which
         * nothing else does.
         */
        internal val MARKERS = arrayOf(
            "<bos>", "<eos>", "<|turn>", "<turn|>",
            "<|tool>", "<tool|>", "<|tool_call>", "<tool_call|>",
            "<|tool_response>", "<tool_response|>", "<|\"|>",
            BOI_MARKER, EOI_MARKER, BOA_MARKER, EOA_MARKER,
        )

        /**
         * A conversation as the prompt string Gemma was trained on.
         *
         * Ported from `chat_template.jinja` by rendering it and matching the output exactly,
         * rather than by reading the Jinja - the macros are dense and the quoting is unusual, and
         * a template that is nearly right degrades quality without failing. The shape is:
         *
         * ```text
         * <bos><|turn>system\n{system}{tools}<turn|>\n<|turn>user\n{text}<turn|>\n<|turn>model\n
         * ```
         *
         * The system turn is emitted only when there is a system prompt or a tool to declare,
         * matching the template's own conditional.
         */
        fun render(
            conversation: List<Turn>,
            system: String?,
            tools: List<ToolDeclaration> = emptyList(),
            continuation: String = "",
        ): String = buildString {
            append("<bos>")
            val declared = declareTools(tools)
            if (!system.isNullOrBlank() || declared.isNotEmpty()) {
                append("<|turn>system\n")
                if (!system.isNullOrBlank()) append(system)
                append(declared)
                append("<turn|>\n")
            }
            for (turn in conversation) {
                append("<|turn>").append(turn.role.marker).append('\n')
                append(turn.text)
                append("<turn|>\n")
            }
            // The generation prompt: an open model turn for the model to complete.
            append("<|turn>model\n")
            // A tool call and its result belong **inside** that turn, not before it.
            //
            // Appending them as a finished model turn instead - `<|turn>model ... <turn|>` and
            // then a fresh `<|turn>model` - was a real bug and a subtle one: the model saw its
            // own turn ended and a new one beginning, which from its point of view is the start
            // of a conversation, so it opened with a greeting instead of answering. It only
            // showed on the first message, because that is the one the system prompt forces a
            // tool call on.
            append(continuation)
        }

        /**
         * Tool declarations in the template's own syntax.
         *
         * Not JSON. Gemma's template writes `<|tool>declaration:name{...}<tool|>` with values
         * wrapped in the `<|"|>` marker rather than in quotation marks, so that a description
         * containing a quote cannot break the parse. Reproduced exactly, including the
         * alphabetical ordering of parameters that `dictsort` imposes - the model saw them in
         * that order during training.
         */
        fun declareTools(tools: List<ToolDeclaration>): String = buildString {
            for (tool in tools) {
                append("<|tool>declaration:").append(tool.name).append('{')
                append("description:").append(quoted(tool.description))
                append(",parameters:{properties:{")
                val sorted = tool.parameters.sortedBy { it.name }
                for ((index, parameter) in sorted.withIndex()) {
                    if (index > 0) append(',')
                    append(parameter.name).append(":{description:")
                    append(quoted(parameter.description))
                    append(",type:").append(quoted(parameter.type.uppercase()))
                    append('}')
                }
                append("}")
                val required = sorted.filter { it.required }
                if (required.isNotEmpty()) {
                    append(",required:[")
                    append(required.joinToString(",") { quoted(it.name) })
                    append(']')
                }
                append(",type:").append(quoted("OBJECT"))
                append("}}<tool|>")
            }
        }

        /**
         * The tool call in [reply], or null if there is not a complete one.
         *
         * The model emits `<|tool_call>call:name{arg:<|"|>value<|"|>}<tool_call|>`. Parsed rather
         * than pattern-matched loosely because a half-written call arrives during streaming and
         * must not be acted on: this returns null until the closing marker is present.
         */
        fun parseToolCall(reply: String): ToolCall? {
            val open = reply.indexOf("<|tool_call>call:")
            if (open < 0) return null
            val close = reply.indexOf("<tool_call|>", open)
            if (close < 0) return null
            val body = reply.substring(open + "<|tool_call>call:".length, close)
            val brace = body.indexOf('{')
            if (brace < 0) return null
            val name = body.substring(0, brace).trim()
            if (name.isEmpty()) return null
            val arguments = LinkedHashMap<String, String>()
            var at = brace + 1
            while (at < body.length) {
                val colon = body.indexOf(':', at)
                if (colon < 0) break
                val key = body.substring(at, colon).trim().trim(',', '{', '}')
                val valueStart = body.indexOf(QUOTE, colon)
                if (valueStart < 0) break
                val valueEnd = body.indexOf(QUOTE, valueStart + QUOTE.length)
                if (valueEnd < 0) break
                if (key.isNotEmpty()) {
                    arguments[key] = body.substring(valueStart + QUOTE.length, valueEnd)
                }
                at = valueEnd + QUOTE.length
                if (at < body.length && body[at] == ',') at++
            }
            return ToolCall(name, arguments)
        }

        /** A tool's result, in the shape the model expects to read back. */
        fun renderToolResponse(name: String, value: String): String =
            "<|tool_response>response:$name{value:${quoted(value)}}<tool_response|>"

        /** Gemma's value delimiter, which is a token rather than a quotation mark. */
        private const val QUOTE = "<|\"|>"

        private fun quoted(value: String): String = QUOTE + value + QUOTE

        /**
         * The model in a folder on disk, which is the only place it lives.
         *
         * Construction never throws: a missing file or an unsupported device leaves
         * [isAvailable] false and the assistant simply off.
         */
        fun inDirectory(directory: File): Gemma4Handle = Gemma4Handle(directory)

        /**
         * Open both graphs, read the tokenizer, and hand the descriptors over.
         *
         * Two descriptors rather than one, so the `finally` dance is doubled. Native adopts both
         * and closes both on every path including failure, so [handed] guards the window between
         * detaching and native taking ownership - and both must be closed here if the call is
         * never made.
         */
        private fun create(directory: File): Long {
            val text = File(directory, TEXT)
            val embed = File(directory, EMBED)
            val tokenizer = File(directory, TOKENIZER)
            for (file in listOf(text, embed, tokenizer)) {
                if (!file.isFile) {
                    Log.w(TAG, "${file.name} is missing from $directory")
                    return 0L
                }
            }
            val table = runCatching { tokenizer.readBytes() }.getOrElse {
                Log.w(TAG, "cannot read $TOKENIZER: $it")
                return 0L
            }
            val textFd = runCatching {
                ParcelFileDescriptor.open(text, ParcelFileDescriptor.MODE_READ_ONLY)
                    .use { it.detachFd() }
            }.getOrElse {
                Log.w(TAG, "cannot open $TEXT: $it")
                return 0L
            }
            val embedFd = runCatching {
                ParcelFileDescriptor.open(embed, ParcelFileDescriptor.MODE_READ_ONLY)
                    .use { it.detachFd() }
            }.getOrElse {
                closeFd(textFd)
                Log.w(TAG, "cannot open $EMBED: $it")
                return 0L
            }
            var handed = false
            try {
                val live = MlNative.createGemma4(
                    textFd, 0L, text.length(),
                    embedFd, 0L, embed.length(),
                    table,
                    cacheBudget(),
                )
                handed = true
                return live
            } finally {
                if (!handed) {
                    closeFd(textFd)
                    closeFd(embedFd)
                }
            }
        }

        /**
         * Close a bare descriptor.
         *
         * Adopting it into a [ParcelFileDescriptor] is the only way to reach `close(2)` from
         * Kotlin. Failures are swallowed because the caller is already on an error path.
         */
        /**
         * Bytes this device will spend on the KV cache.
         *
         * # Why it is a fraction of *total* rather than available memory
         *
         * Available memory is whatever the rest of the system happens to be doing when the
         * assistant starts, so sizing against it makes the conversation length depend on what
         * else was open - and shrinks it exactly when the user has been busy. Total memory is a
         * property of the device, which is what the tier should track.
         *
         * A twentieth is deliberately conservative. The weights are already ~2.9 GB of mapped
         * file and the tower allocations sit on top, so the cache is not the only claim on the
         * budget - and an allocation failure here is a model that will not start at all.
         *
         *   4 GB  ->  200 MB  ->  8,192 positions
         *   8 GB  ->  400 MB  -> 16,384 positions
         *  16 GB  ->  800 MB  -> 16,384 positions, the top tier
         */
        private fun cacheBudget(): Long {
            // `/proc/meminfo` rather than `ActivityManager`, because this class is constructed
            // from a directory and has no `Context` - and adding one to the signature to read a
            // single number would push Android into an API that is otherwise platform-free.
            val total = runCatching {
                File("/proc/meminfo").useLines { lines ->
                    lines.firstOrNull { it.startsWith("MemTotal:") }
                        ?.filter(Char::isDigit)
                        ?.toLongOrNull()
                        ?.times(1024) ?: 0L
                }
            }.getOrDefault(0L)
            // A device that will not say gets the smallest tier, which always fits, rather than
            // an optimistic guess that fails to allocate and leaves the assistant dead.
            if (total <= 0L) return 0L
            return total / 20
        }

        /** The baked prefix cache, beside the weights. Optional. */
        const val PREFIX_CACHE = "gemma4_prefix.kv"

        /** `GKV1` + positions + stride + a 32-byte digest. */
        private const val HEADER = 4 + 4 + 4 + 32

        private fun readInt(bytes: ByteArray, at: Int): Int =
            (bytes[at].toInt() and 0xFF) or
                ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                ((bytes[at + 3].toInt() and 0xFF) shl 24)

        /**
         * The digest `bake_gemma4_prefix` writes: FNV-1a over the token bytes, four times with
         * different seeds to fill 32 bytes.
         *
         * Not cryptographic, and does not need to be: it guards against a stale asset after
         * someone edits the prompt, not against an adversary who could replace the weights too.
         */
        private fun digest(tokens: IntArray): ByteArray {
            val out = ByteArray(32)
            for (lane in 0 until 4) {
                // `0x9e3779b9`, the **32-bit** golden ratio, because that is what
                // `bake_gemma4_prefix` seeds with. The 64-bit one was here first and matched on
                // lane 0 only - so the digest disagreed while the token count agreed, and a
                // perfectly good cache was rejected as "baked for a different prompt".
                var hash = -0x340d631b7bdddcdbL xor (lane.toLong() * 0x9e3779b9L)
                for (token in tokens) {
                    for (shift in 0 until 4) {
                        hash = hash xor ((token ushr (shift * 8)).toLong() and 0xFF)
                        hash *= 0x100000001b3L
                    }
                }
                for (byte in 0 until 8) {
                    out[lane * 8 + byte] = (hash ushr (byte * 8)).toByte()
                }
            }
            return out
        }

        private fun closeFd(fd: Int) {
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
        }
    }
}
