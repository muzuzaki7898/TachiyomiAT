package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import logcat.logcat

class PerplexityTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    private val apiKey: String,
    private val modelName: String = "sonar-pro"
) : TextTranslator {

    private val client = OkHttpClient()

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        try {
            // ambil text dari OCR
            val data = pages.mapValues { (_, v) -> v.blocks.map { b -> b.text } }
            val json = JSONObject(data)

            // bikin prompt mirip Gemini
            val prompt = """
                Translate this comic text JSON into ${toLang.label}.
                Keep the structure identical.
                Replace watermarks/site links with "RTMTH".
                Input: $json
                Output must be valid JSON only.
            """.trimIndent()

            val bodyJson = JSONObject()
                .put("model", modelName)
                .put("messages", listOf(
                    JSONObject()
                        .put("role", "user")
                        .put("content", prompt)
                ))

            val requestBody = RequestBody.create(
                "application/json".toMediaTypeOrNull(),
                bodyJson.toString()
            )

            val request = Request.Builder()
                .url("https://api.perplexity.ai/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val responseStr = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { it.body?.string() }
            } ?: throw Exception("Empty response")

            val resJson = JSONObject(responseStr)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            val translated = JSONObject(resJson)

            // masukin hasil translate ke objek PageTranslation
            for ((k, v) in pages) {
                v.blocks.forEachIndexed { i, b ->
                    val res = translated.optJSONArray(k)?.optString(i, "NULL")
                    b.translation = if (res == null || res == "NULL") b.text else res
                }
                v.blocks = v.blocks.filterNot { it.translation.contains("RTMTH") }.toMutableList()
            }

        } catch (e: Exception) {
            logcat { "Perplexity Translation Error: ${e.stackTraceToString()}" }
            throw e
        }
    }

    override fun close() {
        // ga ada resource yang perlu ditutup
    }
}
