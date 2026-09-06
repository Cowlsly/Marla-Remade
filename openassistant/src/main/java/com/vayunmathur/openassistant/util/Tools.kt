package com.vayunmathur.openassistant.util

import com.vayunmathur.library.ml.Gemma4Handle
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.isAccessible

/**
 * Tool declaration and dispatch, replacing `com.google.ai.edge.litertlm`'s.
 *
 * litertlm supplied `@Tool` / `@ToolParam` / `ToolSet` and did the reflection, the prompt
 * serialisation and the call parsing behind `automaticToolCalling = true`. None of that was
 * visible from this repo, so removing the SDK meant writing all three. The annotations keep the
 * same names and shape so `AssistantToolSet` needed no restructuring - only its import changed.
 *
 * # Everything is a Double, still
 *
 * litertlm passed every JSON number as a `Double`, and `AssistantToolSet` has `.toLong()` /
 * `.toInt()` at every call site to match. That convention is preserved rather than fixed: the
 * tool bodies are long and the conversion sites are load-bearing, so changing it would be a
 * large diff for no behavioural gain. [invoke] coerces to whatever the parameter actually is.
 */
annotation class Tool(val description: String)

/** One argument of a [Tool]. */
annotation class ToolParam(val description: String)

/** A class whose [Tool]-annotated methods the model may call. */
interface ToolSet

/**
 * The declarations and dispatch table for a [ToolSet], built once by reflection.
 *
 * Reflection happens at construction rather than per turn: `AssistantToolSet` has 24 tools, and
 * resolving them on every message would put Kotlin reflection on the latency path of a chat.
 */
class ToolRegistry(private val target: ToolSet) {

    private val byName: Map<String, KFunction<*>> =
        target::class.declaredMemberFunctions
            .filter { it.findAnnotation<Tool>() != null }
            .associateBy { it.name }

    /**
     * What the model is told it may call. Every tool, every turn, in a fixed order.
     *
     * # Fixed on purpose
     *
     * These were ranked by relevance to the user's last message and trimmed to a token budget,
     * which spends less context. It also made the prompt prefix **different on every turn**, and
     * that prefix is the expensive thing: ~780 of the ~1,100 positions before the conversation
     * even starts. A prefix that changes cannot be reused between turns and cannot be
     * precomputed at all, so the model paid to rebuild it whenever the topic moved - about seven
     * seconds on a Tensor G4.
     *
     * Declaring the same set every time costs context and buys two things back: the KV cache for
     * the whole prefix survives from turn to turn, and it can be baked once by
     * `bake_gemma4_prefix` and shipped, so no device ever computes it.
     *
     * The order must be stable too, not merely the membership - two orderings of the same tools
     * are two different token sequences and share only their common head.
     */
    val declarations: List<Gemma4Handle.ToolDeclaration> = byName.map { (name, function) ->
        Gemma4Handle.ToolDeclaration(
            name = name,
            description = function.findAnnotation<Tool>()?.description.orEmpty(),
            parameters = function.parameters
                .filter { it.kind == KParameter.Kind.VALUE }
                .map { parameter ->
                    Gemma4Handle.ToolDeclaration.Parameter(
                        name = parameter.name.orEmpty(),
                        description = parameter.findAnnotation<ToolParam>()?.description.orEmpty(),
                        type = gemmaType(parameter),
                        required = !parameter.isOptional && !parameter.type.isMarkedNullable,
                    )
                },
        )
    }


    /**
     * Run a call the model asked for, and return what to tell it.
     *
     * Never throws: a tool that fails, a name that does not exist, or an argument that will not
     * coerce all become a string the model reads back. The alternative is aborting the turn,
     * which loses the reply the user was waiting for over a tool that was optional anyway.
     */
    fun invoke(call: Gemma4Handle.ToolCall): String {
        val function = byName[call.name] ?: return "error: no tool named ${call.name}"
        return runCatching {
            val arguments = HashMap<KParameter, Any?>()
            arguments[function.parameters.first()] = target
            for (parameter in function.parameters) {
                if (parameter.kind != KParameter.Kind.VALUE) continue
                val raw = call.arguments[parameter.name]
                if (raw == null) {
                    // Absent and optional: let the default apply rather than passing null into a
                    // non-nullable parameter, which would throw inside `callBy`.
                    if (parameter.isOptional) continue
                    if (parameter.type.isMarkedNullable) {
                        arguments[parameter] = null
                        continue
                    }
                    return "error: ${call.name} needs ${parameter.name}"
                }
                arguments[parameter] = coerce(raw, parameter)
            }
            function.isAccessible = true
            function.callBy(arguments)?.toString().orEmpty()
        }.getOrElse { "error: ${it.message ?: it::class.simpleName}" }
    }

    /**
     * A string argument as the parameter's own type.
     *
     * Gemma's tool syntax has no types - every value arrives inside `<|"|>` markers - so the
     * parameter list is the only place the intended type is written down.
     */
    private fun coerce(raw: String, parameter: KParameter): Any? =
        when (parameter.type.classifier) {
            Double::class -> raw.toDoubleOrNull() ?: 0.0
            Float::class -> raw.toFloatOrNull() ?: 0f
            Long::class -> raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong() ?: 0L
            Int::class -> raw.toIntOrNull() ?: raw.toDoubleOrNull()?.toInt() ?: 0
            Boolean::class -> raw.equals("true", ignoreCase = true) || raw == "1"
            else -> raw
        }

    companion object {
        /**
         * Tokens of tool declarations a prompt may carry.
         *
         * Sized from the measured cost of the alternatives. All twenty-four declarations are 1531
         * tokens, and with a 319-token system prompt and 21 of scaffolding that is a 1871-token
         * prompt in a 2048 window - 174 positions for an entire conversation. At this budget a
         * prompt averages 1021 and peaks at 1053 across a fifteen-phrasing corpus, leaving about
         * 990 positions for history, the reply and any audio - enough that
         * `Gemma4Handle.generate`'s `minOf(limit, remaining - 1)` is bounded by `limit` rather
         * than by what is left, which is the regime where a reply reserve can work at all.
         *
         * 800 rather than more because 900 scored identically on that corpus and cost 65 tokens a
         * turn for it. 800 rather than less because the budget is filled, not merely capped, and a
         * smaller one drops tools a request did not happen to name: 700 and 600 both lose
         * `send_message` on "text Sarah that I'm running late", where "text" and "message" share
         * no prefix. A user who is told the assistant cannot send a message is a worse outcome
         * than a turn that is a few percent slower, and it fails silently - no error, just a
         * capability that was not there.
         *
         * The corpus is fifteen phrasings written by hand, so the SHAPE of that trade is measured
         * and its precision is not. Treat 800 as "around where the misses stop" rather than a
         * cliff edge.
         *
         * Read this rather than assuming a tool count: per-tool cost ranges from 30 tokens
         * (`get_notes`) to 137 (`create_calendar_event`), so a fixed count of tools does not
         * imply a fixed number of tokens.
         */
        const val DECLARATION_TOKEN_BUDGET = 800

        /**
         * Tools declared on every turn, outside the budget.
         *
         * The memory three because the system prompt instructs the model to use memory
         * aggressively and on every conversation, so a request that does not mention memory is
         * exactly when they are wanted. The clock because the calendar tools' own descriptions
         * tell the model to get the current time from it, so declaring those without it offers a
         * tool whose instructions cannot be followed.
         */
        val ALWAYS = setOf(
            "get_memories",
            "add_to_memory",
            "remove_memory",
            "get_local_current_date_time",
        )

        /**
         * Tokens a rendered declaration costs, estimated high.
         *
         * Four characters a token against a measured 4.5 across the real declarations, so this
         * over-counts by about a tenth. That direction is deliberate: over-counting declares
         * fewer tools and stays inside the budget, while under-counting would overrun a window
         * that has no room to absorb it.
         */
        private fun estimateTokens(rendered: String): Int = (rendered.length + 3) / 4



        private val STOPWORDS = setOf(
            "the", "and", "for", "you", "your", "can", "get", "please", "with", "from", "that",
            "this", "what", "how", "does", "did", "are", "was", "would", "could", "should",
            "have", "has", "had", "into", "out", "about", "any", "all", "new", "use",
        )

        /** Gemma's type names, which are upper-case and fewer than JSON schema's. */
        fun gemmaType(parameter: KParameter): String = when (parameter.type.classifier) {
            Double::class, Float::class, Long::class, Int::class -> "NUMBER"
            Boolean::class -> "BOOLEAN"
            else -> "STRING"
        }
    }
}
