package eu.kanade.translation.recognizer

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import eu.kanade.translation.translator.TextTranslator
import eu.kanade.translation.translator.TextTranslatorLanguage

class MLKitTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage
) : TextTranslator {


    private var translator = Translation.getClient(
        TranslatorOptions.Builder().setSourceLanguage(langFrom.code)
            .setTargetLanguage(TranslateLanguage.fromLanguageTag(langTo.language) ?: TranslateLanguage.ENGLISH)
            .build(),
    )
    private var conditions = DownloadConditions.Builder().build()
    override suspend fun translate(pages: HashMap<String, TextTranslations>) {
        try {

            translator.downloadModelIfNeeded(conditions).await()
//             translator.downloadModelIfNeeded(conditions).await()
            pages.mapValues { (_, v) ->
                v.translations.map { b ->
                    b.translated = translator.translate(b.text).await()
                }
            }

        } catch (e: Exception) {
            logcat { "Image Translation Error : ${e.message}" }
        }

    }

    override fun close() {
    }
}
