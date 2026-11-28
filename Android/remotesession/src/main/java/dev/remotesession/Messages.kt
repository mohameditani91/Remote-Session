package dev.remotesession

import org.json.JSONArray
import org.json.JSONObject

data class Agent(
    val id: String,
    val name: String,
    /** Video codecs the agent's browser can decode, such as `h264`. */
    val video: List<String> = emptyList(),
)

enum class SessionMode(val wire: String) {
    VIEW("view"),
    CONTROL("control"),
}

enum class EndReason(val wire: String) {
    USER_ENDED("userEnded"),
    AGENT_ENDED("agentEnded"),
    DISCONNECTED("disconnected"),
}

enum class RemoteKey(val wire: String) {
    BACKSPACE("backspace"),
    RETURN("return"),
}

sealed interface RemoteInput {
    data class Tap(val x: Double, val y: Double) : RemoteInput
    data class Scroll(val x: Double, val y: Double, val dx: Double, val dy: Double) : RemoteInput
    data class Text(val text: String) : RemoteInput
    data class Key(val key: RemoteKey) : RemoteInput
    data object Back : RemoteInput
    data object HideKeyboard : RemoteInput
}

data class InputResult(val outcome: Outcome, val reason: String? = null) {

    enum class Outcome(val wire: String) {
        PERFORMED("performed"),
        NOTHING("nothing"),
        BLOCKED("blocked"),
    }

    companion object {
        val performed = InputResult(Outcome.PERFORMED)
        fun nothing(reason: String) = InputResult(Outcome.NOTHING, reason)
        fun blocked(reason: String) = InputResult(Outcome.BLOCKED, reason)
    }
}

/** One agent input, as reported to [RemoteSession.onInput]. */
data class InputEvent(
    val input: RemoteInput,
    val result: InputResult,
    /** What the input reached, such as `"Save" button`, `"Email" field` or `row 3`. Never typed text, row text or masked content. */
    val target: String?,
    /** The class name of the activity the user is looking at. */
    val screen: String?,
)

internal sealed interface IncomingMessage {
    data class Session(val code: String) : IncomingMessage
    data class AgentJoined(val agent: Agent) : IncomingMessage
    data object AgentLeft : IncomingMessage
    data object RequestKeyframe : IncomingMessage
    data class Input(val id: String, val input: RemoteInput) : IncomingMessage
    /** An input this version doesn't know, from a newer console. */
    data class UnsupportedInput(val id: String) : IncomingMessage
    data class End(val reason: EndReason) : IncomingMessage

    companion object {
        /** Returns null for anything malformed. */
        fun parse(json: String): IncomingMessage? = runCatching { decode(JSONObject(json)) }.getOrNull()

        private fun decode(o: JSONObject): IncomingMessage? = when (o.optString("type")) {
            "session" -> o.string("code")?.let(::Session)
            "agentJoined" -> {
                val id = o.string("agentId")
                val name = o.string("agentName")
                if (id == null || name == null) null
                else AgentJoined(Agent(id, name, o.optJSONArray("video")?.strings() ?: emptyList()))
            }
            "agentLeft" -> AgentLeft
            "requestKeyframe" -> RequestKeyframe
            "input" -> o.string("id")?.let { id ->
                when (val action = o.optString("action")) {
                    "tap", "scroll", "text", "key", "back", "hideKeyboard" -> input(action, o)?.let { Input(id, it) }
                    else -> UnsupportedInput(id)
                }
            }
            "end" -> End(EndReason.entries.firstOrNull { it.wire == o.optString("reason") } ?: EndReason.AGENT_ENDED)
            else -> null
        }

        private fun input(action: String, o: JSONObject): RemoteInput? = when (action) {
            "tap" -> if (o.has("x") && o.has("y")) RemoteInput.Tap(o.getDouble("x"), o.getDouble("y")) else null
            "scroll" -> if (listOf("x", "y", "dx", "dy").all(o::has)) RemoteInput.Scroll(o.getDouble("x"), o.getDouble("y"), o.getDouble("dx"), o.getDouble("dy")) else null
            "text" -> o.string("text")?.let(RemoteInput::Text)
            "key" -> RemoteKey.entries.firstOrNull { it.wire == o.optString("key") }?.let(RemoteInput::Key)
            "back" -> RemoteInput.Back
            "hideKeyboard" -> RemoteInput.HideKeyboard
            else -> null
        }

        private fun JSONObject.string(key: String): String? = if (has(key) && !isNull(key)) get(key) as? String else null

        private fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { opt(it) as? String }
    }
}

internal sealed interface OutgoingMessage {
    data class Consent(val mode: SessionMode) : OutgoingMessage
    data object Denied : OutgoingMessage
    data class Screen(val width: Double, val height: Double) : OutgoingMessage
    data class Result(val id: String, val result: InputResult) : OutgoingMessage
    data class Mode(val mode: SessionMode) : OutgoingMessage
    data class Approval(val action: String, val allowed: Boolean?) : OutgoingMessage
    data class End(val reason: EndReason) : OutgoingMessage

    /** Sorted keys and no nulls, like the iOS encoder: the console reads a missing `allowed` as "still asking". */
    val json: String
        get() = encode(
            when (this) {
                is Consent -> mapOf("type" to "consent", "mode" to mode.wire)
                Denied -> mapOf("type" to "denied")
                is Screen -> mapOf("type" to "screen", "width" to width, "height" to height)
                is Result -> mapOf("type" to "result", "id" to id, "outcome" to result.outcome.wire, "reason" to result.reason)
                is Mode -> mapOf("type" to "mode", "mode" to mode.wire)
                is Approval -> mapOf("type" to "approval", "action" to action, "allowed" to allowed)
                is End -> mapOf("type" to "end", "reason" to reason.wire)
            },
        )

    private fun encode(fields: Map<String, Any?>): String =
        fields.filterValues { it != null }.toSortedMap().entries.joinToString(",", "{", "}") { (key, value) ->
            JSONObject.quote(key) + ":" + when (value) {
                is String -> JSONObject.quote(value)
                is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
                else -> value.toString()
            }
        }
}
