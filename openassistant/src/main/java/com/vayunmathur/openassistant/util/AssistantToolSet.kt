package com.vayunmathur.openassistant.util
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.vayunmathur.library.intents.calendar.EventData
import com.vayunmathur.library.intents.clock.SetAlarmData
import com.vayunmathur.library.intents.clock.SetTimerData
import com.vayunmathur.library.intents.contacts.ContactData
import com.vayunmathur.library.intents.findfamily.FamilyMemberData
import com.vayunmathur.library.intents.music.MusicSearchResult
import com.vayunmathur.library.intents.music.PlayMusicData
import com.vayunmathur.library.intents.email.EmailData
import com.vayunmathur.library.intents.email.EmailSearchQuery
import com.vayunmathur.library.intents.weather.WeatherData
import com.vayunmathur.openassistant.MainActivity
import com.vayunmathur.library.intents.notes.NoteData
import com.vayunmathur.openassistant.data.Memory
import com.vayunmathur.openassistant.data.MemoryDao
import com.vayunmathur.openassistant.data.MessageDao
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

object JsonSchemaValidator {
    fun validateJsonAgainstSchema(jsonString: String, schemaString: String): String? {
        val json = try { Json.parseToJsonElement(jsonString) }
            catch (e: Exception) { return "Invalid JSON format: ${e.message}" }
        val schema = try { Json.parseToJsonElement(schemaString) }
            catch (e: Exception) { Log.e("JsonSchemaValidator", "Internal Error: Schema itself is invalid JSON", e); return null }
        return performValidation(json, schema)
    }

    fun trimJsonKeys(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.map { (k, v) -> k.trim() to trimJsonKeys(v) }.toMap())
        is JsonArray -> JsonArray(element.map { trimJsonKeys(it) })
        else -> element
    }

    private fun performValidation(data: JsonElement, schema: JsonElement, path: String = ""): String? {
        if (schema !is JsonObject) return null
        val pathPrefix = if (path.isEmpty()) "" else "at $path: "

        (schema["anyOf"] as? JsonArray)?.let { anyOf ->
            val errors = mutableListOf<String>()
            anyOf.forEachIndexed { i, s ->
                val error = performValidation(data, s, path) ?: return null
                errors.add("Option $i: $error")
            }
            return "Data does not match any of the allowed schemas in anyOf. Details: ${errors.joinToString("; ")}"
        }

        (schema["oneOf"] as? JsonArray)?.let { oneOf ->
            val matching = mutableListOf<Int>()
            val errors = mutableListOf<String>()
            oneOf.forEachIndexed { i, s ->
                val error = performValidation(data, s, path)
                if (error == null) matching.add(i) else errors.add("Option $i: $error")
            }
            if (matching.size == 1) return null
            return if (matching.isEmpty()) {
                "Data does not match any of the allowed options in 'oneOf'. Details: ${errors.joinToString("; ")}"
            } else "Data matches MULTIPLE options in 'oneOf': $matching."
        }

        schema["not"]?.let { notSchema ->
            if (performValidation(data, notSchema, path) == null) {
                return "Data matched the 'not' schema at $path, which is forbidden."
            }
        }

        val expectedType = schema["type"]?.jsonPrimitive?.content
        if (expectedType == "object" && data !is JsonObject) return "${pathPrefix}Expected an object but got ${data::class.simpleName}"
        if (expectedType == "array" && data !is JsonArray) return "${pathPrefix}Expected an array but got ${data::class.simpleName}"

        if (data is JsonObject) {
            val properties = schema["properties"] as? JsonObject
            data.keys.forEach { key ->
                if (properties == null || !properties.containsKey(key)) {
                    val fullPath = if (path.isEmpty()) key else "$path.$key"
                    return "Unexpected field found: '$fullPath'."
                }
            }
            (schema["required"] as? JsonArray)?.forEach { req ->
                val fieldName = req.jsonPrimitive.content
                if (!data.containsKey(fieldName)) {
                    val fullPath = if (path.isEmpty()) fieldName else "$path.$fieldName"
                    return "Missing required field: '$fullPath'"
                }
            }
            data.forEach { (key, value) ->
                val propSchema = properties?.get(key) ?: return@forEach
                val fullPath = if (path.isEmpty()) key else "$path.$key"
                if (propSchema is JsonObject) {
                    propSchema["const"]?.jsonPrimitive?.content?.let { constValue ->
                        if (value.jsonPrimitive.content != constValue)
                            return "Field '$fullPath' must be '$constValue' but got '${value.jsonPrimitive.content}'"
                    }
                    (propSchema["enum"] as? JsonArray)?.let { enumValues ->
                        val allowed = enumValues.map { it.jsonPrimitive.content }
                        if (value.jsonPrimitive.content !in allowed)
                            return "Field '$fullPath' has invalid value '${value.jsonPrimitive.content}'. Allowed values: $allowed"
                    }
                }
                performValidation(value, propSchema, fullPath)?.let { return it }
            }
        }

        if (data is JsonArray) {
            schema["items"]?.let { itemSchema ->
                data.forEachIndexed { index, element ->
                    performValidation(element, itemSchema, "$path[$index]")?.let { return it }
                }
            }
        }

        return null
    }
}

class AssistantToolSet(
    private val context: Context,
    private val memoryDao: MemoryDao? = null,
    private val messageDao: MessageDao? = null,
    private val conversationId: Long = -1L
) : ToolSet {

    companion object {
        private val APP_NAMES = mapOf(
            "com.vayunmathur.notes" to "Notes",
            "com.vayunmathur.contacts" to "Contacts",
            "com.vayunmathur.calendar" to "Calendar",
            "com.vayunmathur.findfamily" to "FindFamily",
            "com.vayunmathur.music" to "Music",
            "com.vayunmathur.email" to "Email",
            "com.vayunmathur.weather" to "Weather",
            "com.vayunmathur.clock" to "Clock",
        )

        fun getMissingAppMessage(packageName: String): String {
            val appName = APP_NAMES[packageName] ?: packageName
            return "The $appName app is required but not installed. [Download from GitHub](https://github.com/vayun-mathur/Modern-Apps)."
        }
    }

    private fun handleMissingApp(packageName: String): String {
        Log.d("AssistantToolSet", "Handling missing app: $packageName")
        return getMissingAppMessage(packageName) + " Try to help the user with your own knowledge instead."
    }

    private fun runTool(block: suspend () -> String): String = runBlocking {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: MissingAppException) { handleMissingApp(e.packageName) }
        catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Get a list of all notes")
    fun get_notes(): String = runTool {
        launchIntent<Unit, List<NoteData>>(context, "com.vayunmathur.notes", "com.vayunmathur.notes.intents.GetIntent", Unit).toString()
    }

    @Tool(description = "Create a new note in the notes app. Should only be used with EXPLICIT request by user")
    fun create_note(title: String, content: String): String = runTool {
        launchIntentU(context, "com.vayunmathur.notes", "com.vayunmathur.notes.intents.InsertIntent", NoteData(title, content))
        "Success: Created note '$title'"
    }

    @Tool(description = "Get a list of all contacts")
    fun get_contacts(): String = runTool {
        launchIntent<Unit, List<ContactData>>(context, "com.vayunmathur.contacts", "com.vayunmathur.contacts.intents.GetIntent", Unit).toString()
    }

    @Tool(description = "Create a new contact")
    fun create_contact(name: String, phoneNumber: String): String = runTool {
        launchIntentU(context, "com.vayunmathur.contacts", "com.vayunmathur.contacts.intents.InsertIntent", ContactData(name, phoneNumber))
        "Success: Created contact '$name'"
    }

    @Tool(description = "Get a list of calendar events. Each event's start and end times are epoch milliseconds.")
    fun get_calendar_events(): String = runTool {
        launchIntent<Unit, List<EventData>>(context, "com.vayunmathur.calendar", "com.vayunmathur.calendar.intents.GetIntent", Unit).toString()
    }

    @Tool(description = "Create a new calendar event. Start and end times must be epoch milliseconds; get the current time from get_local_current_date_time and add offsets to compute them.")
    fun create_calendar_event(
        title: String,
        @ToolParam(description = "start time as epoch milliseconds") start: Double,
        @ToolParam(description = "end time as epoch milliseconds") end: Double,
        location: String = ""
    ): String = runTool {
        launchIntentU(context, "com.vayunmathur.calendar", "com.vayunmathur.calendar.intents.InsertIntent", EventData(title, start.toLong(), end.toLong(), location))
        "Success: Created event '$title'"
    }

    @Tool(description = "Get a list of family members and their current locations")
    fun get_family_locations(): String = runTool {
        launchIntent<Unit, List<FamilyMemberData>>(context, "com.vayunmathur.findfamily", "com.vayunmathur.findfamily.intents.GetIntent", Unit).toString()
    }

    @Tool(description = "Create a temporary FindFamily link that shares the user's live location with someone until it expires. Returns a shareable URL. Only use on explicit user request.")
    fun create_location_share_link(
        @ToolParam(description = "name or label for who the link is for") name: String,
        @ToolParam(description = "how long the link stays active, in milliseconds from now") durationMillis: Double
    ): String = runTool {
        launchIntent<CreateLinkRequest, String>(context, "com.vayunmathur.findfamily", "com.vayunmathur.findfamily.intents.CreateLinkIntent", CreateLinkRequest(name, durationMillis.toLong()))
    }

    @Tool(description = "Search for music (songs, albums, artists, or playlists)")
    fun search_music(query: String): String = runTool {
        launchIntent<String, List<MusicSearchResult>>(context, "com.vayunmathur.music", "com.vayunmathur.music.intents.SearchIntent", query).toString()
    }

    @Tool(description = "Play music given its id and type (song, album, artist, or playlist)")
    fun play_music(id: Double, type: String): String = runTool {
        launchIntentU(context, "com.vayunmathur.music", "com.vayunmathur.music.intents.PlayIntent", PlayMusicData(id.toLong(), type))
        "Success: Playing music"
    }

    @Tool(description = "Search emails by keyword")
    fun search_emails(query: String): String = runTool {
        launchIntent<EmailSearchQuery, List<EmailData>>(context, "com.vayunmathur.email", "com.vayunmathur.email.intents.SearchIntent", EmailSearchQuery(query)).toString()
    }

    @Tool(description = "Get recent emails from the inbox")
    fun get_recent_emails(): String = runTool {
        launchIntent<Unit, List<EmailData>>(context, "com.vayunmathur.email", "com.vayunmathur.email.intents.GetRecentIntent", Unit).toString()
    }

    @Tool(description = "Get the current date and time in the local timezone, including the current epoch time in milliseconds. Use the epochMillis value when creating calendar events or computing alarm/timer times.")
    fun get_local_current_date_time(): String {
        val now = Clock.System.now()
        val local = now.toLocalDateTime(TimeZone.currentSystemDefault())
        return "${TimeZone.currentSystemDefault().id}: $local (epochMillis=${now.toEpochMilliseconds()})"
    }

    @SuppressLint("QueryPermissionsNeeded")
    @Tool(description = "Get a list of installed apps on the device")
    fun get_app_list(): String {
        val pm = context.packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA).map { it.loadLabel(pm).toString() }.toString()
    }

    @Tool(description = "Open an app given its package id")
    fun open_app(@ToolParam(description = "package id") packageId: String): String {
        val intent = context.packageManager.getLaunchIntentForPackage(packageId)
            ?: return handleMissingApp(packageId)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Success: Opened $packageId"
    }

    @Tool(description = "Send a message")
    fun send_message(recipient: String, message: String): String = try {
        context.startActivity(Intent(Intent.ACTION_SENDTO).apply {
            data = "smsto:$recipient".toUri()
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        "Opened messaging app."
    } catch (e: Exception) { "Error: ${e.message}" }

    @Tool(description = "Make a phone call")
    fun make_phone_call(recipient: String): String = try {
        context.startActivity(Intent(Intent.ACTION_DIAL).apply {
            data = "tel:$recipient".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        "Opened dialer."
    } catch (e: Exception) { "Error: ${e.message}" }

    @Tool(description = "Set an alarm in the Clock app at a specific hour and minute (24-hour time). Creates the alarm immediately, no confirmation needed.")
    fun set_alarm(
        @ToolParam(description = "hour in 24-hour format, 0-23") hour: Double,
        @ToolParam(description = "minute, 0-59") minute: Double,
        @ToolParam(description = "optional label for the alarm") label: String = ""
    ): String = runTool {
        launchIntentU(context, "com.vayunmathur.clock", "com.vayunmathur.clock.intents.SetAlarmIntent", SetAlarmData(hour.toInt(), minute.toInt(), label))
        "Success: Alarm set for %02d:%02d".format(hour.toInt(), minute.toInt())
    }

    @Tool(description = "Start a countdown timer in the Clock app for a number of seconds. Starts immediately, no confirmation needed.")
    fun set_timer(
        @ToolParam(description = "timer length in seconds") seconds: Double,
        @ToolParam(description = "optional label for the timer") label: String = ""
    ): String = runTool {
        launchIntentU(context, "com.vayunmathur.clock", "com.vayunmathur.clock.intents.SetTimerIntent", SetTimerData(seconds.toInt(), label))
        "Success: Timer started for ${seconds.toInt()}s"
    }

    @Tool("Set title of current conversation. Mandatory for first response")
    fun set_conversation_title(newTitle: String): String {
        InferenceService.newTitle = newTitle
        return "Conversation title set successfully"
    }

    @Tool(description = "Get the current weather conditions at a specific latitude/longitude. Returns temperature, feels-like, condition, hi/lo, humidity, wind, UV, sunrise and sunset.")
    fun get_weather(latitude: Double, longitude: Double): String = runTool {
        val result: WeatherData = launchIntent(context, "com.vayunmathur.weather", "com.vayunmathur.weather.intents.GetWeatherIntent", WeatherLatLonRequest(latitude, longitude))
        result.error ?: result.toString()
    }

    @Tool(description = "Get the current weather conditions for a named place (city, address, landmark). Prefer this over get_weather when the user says a place name instead of coordinates.")
    fun get_weather_by_name(@ToolParam(description = "city or place name") location: String): String = runTool {
        val result: WeatherData = launchIntent(context, "com.vayunmathur.weather", "com.vayunmathur.weather.intents.GetWeatherByNameIntent", WeatherNameRequest(location))
        result.error ?: result.toString()
    }

    @Tool(description = "Get a list of all memories")
    fun get_memories(): String = runTool {
        memoryDao?.getAll()?.joinToString("\n") { "[${it.id}] ${it.content}" } ?: "Error: MemoryDao is null"
    }

    @Tool(description = "Remove a memory by its id")
    fun remove_memory(id: Double): String = runTool {
        if (memoryDao == null) return@runTool "Error: MemoryDao is null"
        memoryDao.deleteById(id.toLong())
        "Success: Removed memory with id ${id.toLong()}"
    }

    @Tool(description = "Add a new memory to the list of memories")
    fun add_to_memory(content: String): String = runTool {
        if (memoryDao == null) return@runTool "Error: MemoryDao is null"
        memoryDao.upsert(Memory(content))
        "Success: Added memory"
    }
}

class MissingAppException(val packageName: String) : Exception("App $packageName is not installed.")
class StopInferenceException : Exception("STOP")

@kotlinx.serialization.Serializable
data class WeatherLatLonRequest(val latitude: Double, val longitude: Double)

@kotlinx.serialization.Serializable
data class WeatherNameRequest(val name: String)

@kotlinx.serialization.Serializable
data class CreateLinkRequest(val name: String, val expiryMillis: Long)

suspend inline fun <reified Input : Any, reified Output : Any> launchIntent(
    context: Context, packageName: String, className: String, input: Input
): Output {
    val stringOutput = MainActivity.intentLauncher.launch(context, packageName, className, serializer<Input>(), input)
    if (stringOutput == "package $packageName doesn't exist") throw MissingAppException(packageName)
    return Json.decodeFromString(serializer<Output>(), stringOutput)
}

suspend inline fun <reified Input : Any> launchIntentU(
    context: Context, packageName: String, className: String, input: Input
) {
    val stringOutput = MainActivity.intentLauncher.launch(context, packageName, className, serializer<Input>(), input)
    if (stringOutput == "package $packageName doesn't exist") throw MissingAppException(packageName)
}
