package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import logcat.logcat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class PerplexityTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    private val apiKey: String,
    private val modelName: String,
    private val maxOutputToken: Int,
    private val temp: Float,
) : TextTranslator {

    private val client = OkHttpClient()

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        try {
            val data = pages.mapValues { (_, v) -> v.blocks.map { it.text } }
            val json = JSONObject(data)

            val prompt = """
                ## System Prompt for Manhwa/Manga/Manhua Translation

                You are a highly skilled AI tasked with translating text from scanned comics while preserving the structure and removing any watermarks or site links.

                - Input: JSON where keys are image filenames and values are lists of text.
                - Translate everything to **${toLang.label}**.
                - Replace site links/watermarks with "RTMTH".
                - Keep output JSON structure the same.

                Input JSON:
                $json
            """.trimIndent()

            val requestJson = JSONObject()
                .put("model", modelName)
                .put("temperature", temp)
                .put("max_tokens", maxOutputToken)
                .put("messages", listOf(
                    mapOf("role" to "system", "content" to "You are a translator for comics."),
                    mapOf("role" to "user", "content" to prompt)
                ))

            val body = requestJson.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://api.perplexity.ai/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Perplexity API Error: ${response.code} ${response.message}")
                }

                val responseText = response.body?.string() ?: throw Exception("Empty response")
                val jsonResp = JSONObject(responseText)

                val content = jsonResp.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")

                val resJson = JSONObject(content)

                for ((k, v) in pages) {
                    v.blocks.forEachIndexed { i, b ->
                        val res = resJson.optJSONArray(k)?.optString(i, "NULL")
                        b.translation = if (res == null || res == "NULL") b.text else res
                    }
                    v.blocks = v.blocks.filterNot { it.translation.contains("RTMTH") }.toMutableList()
                }
            }

        } catch (e: Exception) {
            logcat { "Perplexity Translation Error: ${e.stackTraceToString()}" }
            throw e
        }
    }

    override fun close() {
        // nothing to close
    }
}
