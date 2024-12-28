package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.Closeable

interface TextTranslator : Closeable {
    val fromLang: TextRecognizerLanguage
    val toLang: TextTranslatorLanguage
    suspend fun translate(pages: MutableMap<String, PageTranslation>)
}

enum class TextTranslators(val label: String) {
    MLKIT("MlKit (On Device)");
//    GOOGLE("Google Translate"),
//    GEMINI("Gemini AI [API KEY]"),
//    OPENROUTER("OpenRouter [API KEY] [MODEL]");

    fun build(pref : TranslationPreferences= Injekt.get(), fromLang: TextRecognizerLanguage = TextRecognizerLanguage.fromPref(pref.translateFromLanguage()), toLang: TextTranslatorLanguage = TextTranslatorLanguage.fromPref(pref.translateToLanguage())): TextTranslator{
        return when(this){
            MLKIT -> MLKitTranslator(fromLang, toLang)
        }
    }

    companion object {
        fun fromPref(pref: Preference<Int>): TextTranslators {
            var translator = entries.getOrNull(pref.get())
            if (translator == null) {
                pref.set(0)
                return MLKIT
            }
            return translator
        }
    }
}
