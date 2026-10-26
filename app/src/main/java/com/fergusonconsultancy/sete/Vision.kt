package com.fergusonconsultancy.sete

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Identifying a plant from its photo with Claude's vision, through the Anthropic API.
 * The key arrives at build time from the ANTHROPIC_API_KEY GitHub secret; without it,
 * identification is simply off and plants are added by picking their type.
 * The photo is sent to Anthropic; nothing else about the garden is.
 */
object Vision {
    /** Accuracy matters more than speed here: one call per new plant. */
    private const val MODEL = "claude-sonnet-5-5"

    val enabled: Boolean get() = BuildConfig.ANTHROPIC_API_KEY.isNotBlank()

    data class Guess(val name: String, val latin: String)

    data class Result(
        val name: String,
        val latin: String,
        /** 0 to 1: how sure the identification is. */
        val confidence: Double,
        val alternatives: List<Guess>,
        /** One of Sete's kinds, or "other" with [care] written for this plant. */
        val kind: String,
        val care: Care,
        val voice: Character?,
        val summerDays: Int,
        val pot: Pot?,
        val condition: String,
    ) {
        val sureness: String get() = when { confidence >= 0.8 -> "very likely"; confidence >= 0.55 -> "fairly sure"; else -> "a best guess" }
    }

    class Failure(message: String) : Exception(message)

    private fun schema(): JSONObject {
        fun str(d: String) = JSONObject().put("type", "string").put("description", d)
        fun arr(d: String, n: Int) = JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", d).put("maxItems", n)
        val kinds = JSONArray(Kinds.all.map { it.key })
        return JSONObject().put("type", "object").put("properties", JSONObject()
            .put("is_plant", JSONObject().put("type", "boolean").put("description", "false if no plant is clearly visible"))
            .put("common_name", str("Everyday British English name, e.g. Peace lily"))
            .put("latin", str("Botanical name, genus and species if known, e.g. Spathiphyllum wallisii"))
            .put("confidence", JSONObject().put("type", "number").put("description", "0 to 1, honest"))
            .put("alternatives", JSONObject().put("type", "array").put("maxItems", 3).put("description", "Other plausible identities, most likely first")
                .put("items", JSONObject().put("type", "object").put("properties", JSONObject().put("common_name", str("")).put("latin", str("")))
                    .put("required", JSONArray(listOf("common_name", "latin")))))
            .put("kind", JSONObject().put("type", "string").put("enum", kinds).put("description", "The closest matching kind from this list, or other"))
            .put("summer_days", JSONObject().put("type", "integer").put("description", "Typical days between waterings indoors in a UK summer, 2 to 45"))
            .put("winter_factor", JSONObject().put("type", "number").put("description", "Extra stretch Nov to Feb for plants that rest (1.0 none, up to 1.6)"))
            .put("how_to_water", str("One or two short plain sentences"))
            .put("thirst_signal", str("One sentence: what the plant shows when thirsty, and when overwatered"))
            .put("toxic_to_pets", JSONObject().put("type", "boolean").put("description", "Toxic to cats or dogs per ASPCA-style listings"))
            .put("pets_note", str("One short sentence on pet safety"))
            .put("pot", JSONObject().put("type", "string").put("enum", JSONArray(listOf("PLASTIC", "GLAZED", "TERRACOTTA", "CONCRETE", "METAL", "UNKNOWN"))))
            .put("condition", str("One or two sentences on how this plant looks in the photo: leaf colour, droop, brown tips, pests, light"))
            .put("character_title", str("A playful three-to-five word character drawn from how this plant really behaves, e.g. The drama queen"))
            .put("thirsty_lines", arr("Two short warm, cheeky lines in its voice for when it is thirsty. No em dashes.", 2))
            .put("thanks_lines", arr("Two short lines thanking whoever watered it. No em dashes.", 2))
            .put("damp_lines", arr("One short line for when the soil was still damp. No em dashes.", 1))
        ).put("required", JSONArray(listOf("is_plant", "common_name", "latin", "confidence", "kind", "summer_days", "winter_factor", "how_to_water",
            "thirst_signal", "toxic_to_pets", "pets_note", "pot", "condition", "character_title", "thirsty_lines", "thanks_lines", "damp_lines")))
    }

    /**
     * Identifies the plant in [jpegBase64] (no data: prefix). [hint] is the owner's own answer
     * when she corrects a guess: the care is then written for that plant. Call off the main thread.
     */
    fun identify(jpegBase64: String, hint: String? = null): Result {
        if (!enabled) throw Failure("Identification isn't switched on in this build.")
        val prompt = buildString {
            append("This photo was taken in a home in the UK to add a houseplant to a watering tracker. ")
            append("Identify the plant and answer only by calling record_plant, with no other text. Be honest about confidence. Write plain British English, no em dashes.")
            if (!hint.isNullOrBlank()) append(" The owner says this plant is: \"").append(hint.take(80)).append("\". Treat that as the identity and write its care.")
        }
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 1200)
            .put("tools", JSONArray().put(JSONObject().put("name", "record_plant").put("description", "Record the identified plant and its care").put("input_schema", schema())))
            // Sonnet 5.5 refuses a forced tool_choice, so the prompt asks for the call instead.
            .put("tool_choice", JSONObject().put("type", "auto"))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
                .put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", jpegBase64)))
                .put(JSONObject().put("type", "text").put("text", prompt)))))

        val conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000; readTimeout = 60_000
            doOutput = true
            setRequestProperty("x-api-key", BuildConfig.ANTHROPIC_API_KEY)
            setRequestProperty("anthropic-version", "2023-06-01")
            setRequestProperty("content-type", "application/json")
        }
        val text = try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val reply = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw Failure(when (code) {
                401, 403 -> "The API key was refused. Check the ANTHROPIC_API_KEY secret."
                429 -> "Too many requests just now. Try again in a minute."
                529, 503 -> "Claude is busy. Try again in a moment."
                else -> "The identifier answered with an error ($code)."
            })
            reply
        } catch (e: Failure) { throw e } catch (e: Exception) {
            throw Failure("Couldn't reach the identifier. Check the connection.")
        } finally { conn.disconnect() }

        val content = JSONObject(text).optJSONArray("content") ?: throw Failure("The identifier gave an empty answer.")
        val input = (0 until content.length()).map { content.getJSONObject(it) }.firstOrNull { it.optString("type") == "tool_use" }?.optJSONObject("input")
            ?: throw Failure("The identifier gave an answer Sete couldn't read.")
        if (!input.optBoolean("is_plant", true)) throw Failure("No plant found in that photo. Try again with the whole plant in frame.")
        fun lines(k: String) = input.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
        val name = input.optString("common_name").ifBlank { "Houseplant" }
        val latin = input.optString("latin")
        val kind = input.optString("kind").takeIf { k -> Kinds.all.any { it.key == k } } ?: "other"
        val voice = Character(input.optString("character_title").ifBlank { "The newcomer" }, lines("thirsty_lines"), lines("thanks_lines"), lines("damp_lines"))
        return Result(
            name = name, latin = latin,
            confidence = input.optDouble("confidence", 0.5).coerceIn(0.0, 1.0),
            alternatives = input.optJSONArray("alternatives")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Guess(it.optString("common_name"), it.optString("latin")) }.filter { it.name.isNotBlank() }
            }.orEmpty(),
            kind = kind,
            care = Care(name.replaceFirstChar { it.uppercase() }, latin, input.optDouble("winter_factor", 1.0).coerceIn(1.0, 1.6).toFloat(),
                if (input.isNull("toxic_to_pets")) null else input.optBoolean("toxic_to_pets"),
                input.optString("how_to_water"), input.optString("thirst_signal"), input.optString("pets_note")),
            voice = voice.takeIf { it.thirsty.isNotEmpty() && it.thanks.isNotEmpty() && it.damp.isNotEmpty() },
            summerDays = input.optInt("summer_days", Kinds[kind].base).coerceIn(2, 45),
            pot = input.optString("pot").let { p -> runCatching { Pot.valueOf(p) }.getOrNull() },
            condition = input.optString("condition"),
        )
    }
}
