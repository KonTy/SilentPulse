package com.silentpulse.messenger.feature.drivemode

import com.google.mlkit.nl.languageid.LanguageIdentification
import timber.log.Timber
import java.util.Locale

/**
 * Helpers for multilingual Drive Mode:
 *  - Detect message language via ML Kit (offline after first model download)
 *  - Multilingual command keyword matching
 *  - Localized command prompts ("Say dismiss, delete, or reply" in detected language)
 */
object DriveModeLangHelper {

    // BCP-47 → command keywords for each language
    // Each set contains all acceptable spoken forms for that command
    private val dismissKeywords = mapOf(
        "en" to setOf("dismiss", "ignore", "clear"),
        "es" to setOf("descartar", "ignorar", "cerrar"),
        "fr" to setOf("ignorer", "rejeter", "fermer"),
        "de" to setOf("ignorieren", "verwerfen", "schließen"),
        "pt" to setOf("descartar", "ignorar", "fechar"),
        "it" to setOf("ignora", "ignorare", "chiudi"),
        "nl" to setOf("negeren", "sluiten", "afwijzen"),
        "pl" to setOf("odrzuć", "ignoruj", "zamknij"),
        "ru" to setOf("игнорировать", "закрыть", "отклонить"),
        "zh" to setOf("忽略", "关闭", "取消"),
        "ja" to setOf("無視", "閉じる", "スキップ"),
        "ko" to setOf("무시", "닫기", "취소"),
        "ar" to setOf("تجاهل", "إغلاق", "رفض"),
        "hi" to setOf("अनदेखा", "बंद", "खारिज"),
        "tr" to setOf("yoksay", "kapat", "reddet"),
        "sv" to setOf("ignorera", "stäng", "avvisa"),
    )

    private val deleteKeywords = mapOf(
        "en" to setOf("delete", "remove", "erase"),
        "es" to setOf("eliminar", "borrar", "suprimir"),
        "fr" to setOf("supprimer", "effacer", "enlever"),
        "de" to setOf("löschen", "entfernen", "löschen"),
        "pt" to setOf("excluir", "apagar", "remover"),
        "it" to setOf("elimina", "eliminare", "cancella"),
        "nl" to setOf("verwijderen", "wissen", "schrappen"),
        "pl" to setOf("usuń", "skasuj", "wymaż"),
        "ru" to setOf("удалить", "стереть", "убрать"),
        "zh" to setOf("删除", "删去", "清除"),
        "ja" to setOf("削除", "消去", "消す"),
        "ko" to setOf("삭제", "지우기", "제거"),
        "ar" to setOf("احذف", "امسح", "ازل"),
        "hi" to setOf("हटाएं", "मिटाएं", "डिलीट"),
        "tr" to setOf("sil", "kaldır", "yok et"),
        "sv" to setOf("ta bort", "radera", "bort"),
    )

    private val replyKeywords = mapOf(
        "en" to setOf("reply", "respond", "answer"),
        "es" to setOf("responder", "contestar", "respuesta"),
        "fr" to setOf("répondre", "réponse", "repondre"),
        "de" to setOf("antworten", "beantworten", "antwort"),
        "pt" to setOf("responder", "resposta", "responde"),
        "it" to setOf("rispondi", "rispondere", "risposta"),
        "nl" to setOf("beantwoorden", "antwoorden", "reageren"),
        "pl" to setOf("odpowiedz", "odpowiedź", "odpowiadać"),
        "ru" to setOf("ответить", "ответ", "отвечать"),
        "zh" to setOf("回复", "回答", "答复"),
        "ja" to setOf("返信", "返事", "応答"),
        "ko" to setOf("답장", "답하기", "회신"),
        "ar" to setOf("رد", "أجب", "الرد"),
        "hi" to setOf("जवाब दें", "उत्तर दें", "रिप्लाई"),
        "tr" to setOf("yanıtla", "cevapla", "cevap ver"),
        "sv" to setOf("svara", "besvara", "svar"),
    )

    /** Spoken prompt asking for a voice command, in the detected language */
    fun commandPrompt(locale: Locale): String = when (locale.language) {
        "es" -> "Diga: ignorar, eliminar o responder."
        "fr" -> "Dites : ignorer, supprimer ou répondre."
        "de" -> "Sagen Sie: ignorieren, löschen oder antworten."
        "pt" -> "Diga: ignorar, excluir ou responder."
        "it" -> "Di': ignora, elimina o rispondi."
        "nl" -> "Zeg: negeren, verwijderen of beantwoorden."
        "pl" -> "Powiedz: ignoruj, usuń lub odpowiedz."
        "ru" -> "Скажите: игнорировать, удалить или ответить."
        "zh" -> "说：忽略、删除或回复。"
        "ja" -> "「無視」「削除」または「返信」と言ってください。"
        "ko" -> "\"무시\", \"삭제\" 또는 \"답장\"이라고 말하세요."
        "ar" -> "قل: تجاهل، احذف، أو رد."
        "hi" -> "कहें: अनदेखा, हटाएं, या जवाब दें।"
        "tr" -> "Söyleyin: yoksay, sil veya yanıtla."
        "sv" -> "Säg: ignorera, ta bort eller svara."
        else -> "Say dismiss, delete, or reply."
    }

    /** "What would you like to say?" in the detected language */
    fun replyPrompt(locale: Locale): String = when (locale.language) {
        "es" -> "¿Qué quieres responder?"
        "fr" -> "Que voulez-vous répondre ?"
        "de" -> "Was möchten Sie antworten?"
        "pt" -> "O que você quer responder?"
        "it" -> "Cosa vuoi rispondere?"
        "nl" -> "Wat wil je antwoorden?"
        "pl" -> "Co chcesz odpowiedzieć?"
        "ru" -> "Что вы хотите ответить?"
        "zh" -> "你想回复什么？"
        "ja" -> "何と返信しますか？"
        "ko" -> "무엇을 답장하시겠습니까?"
        "ar" -> "ماذا تريد أن ترد؟"
        "hi" -> "आप क्या जवाब देना चाहते हैं?"
        "tr" -> "Ne cevap vermek istiyorsunuz?"
        "sv" -> "Vad vill du svara?"
        else -> "What would you like to say?"
    }

    /** "Reply sent." in the detected language */
    fun replySentAck(locale: Locale): String = when (locale.language) {
        "es" -> "Respuesta enviada."
        "fr" -> "Réponse envoyée."
        "de" -> "Antwort gesendet."
        "pt" -> "Resposta enviada."
        "it" -> "Risposta inviata."
        "nl" -> "Antwoord verzonden."
        "pl" -> "Odpowiedź wysłana."
        "ru" -> "Ответ отправлен."
        "zh" -> "回复已发送。"
        "ja" -> "返信しました。"
        "ko" -> "답장을 보냈습니다."
        "ar" -> "تم إرسال الرد."
        "hi" -> "जवाब भेज दिया गया।"
        "tr" -> "Yanıt gönderildi."
        "sv" -> "Svar skickat."
        else -> "Reply sent."
    }

    /** "Message dismissed." in the detected language */
    fun dismissAck(locale: Locale): String = when (locale.language) {
        "es" -> "Mensaje descartado."
        "fr" -> "Message ignoré."
        "de" -> "Nachricht ignoriert."
        "pt" -> "Mensagem descartada."
        "it" -> "Messaggio ignorato."
        "nl" -> "Bericht genegeerd."
        "pl" -> "Wiadomość odrzucona."
        "ru" -> "Сообщение закрыто."
        "zh" -> "消息已忽略。"
        "ja" -> "メッセージを無視しました。"
        "ko" -> "메시지를 무시했습니다."
        "ar" -> "تم تجاهل الرسالة."
        "hi" -> "संदेश अनदेखा किया गया।"
        "tr" -> "Mesaj yoksayıldı."
        "sv" -> "Meddelande ignorerat."
        else -> "Message dismissed."
    }

    /** "Message deleted." in the detected language */
    fun deleteAck(locale: Locale): String = when (locale.language) {
        "es" -> "Mensaje eliminado."
        "fr" -> "Message supprimé."
        "de" -> "Nachricht gelöscht."
        "pt" -> "Mensagem excluída."
        "it" -> "Messaggio eliminato."
        "nl" -> "Bericht verwijderd."
        "pl" -> "Wiadomość usunięta."
        "ru" -> "Сообщение удалено."
        "zh" -> "消息已删除。"
        "ja" -> "メッセージを削除しました。"
        "ko" -> "메시지가 삭제되었습니다."
        "ar" -> "تم حذف الرسالة."
        "hi" -> "संदेश हटा दिया गया।"
        "tr" -> "Mesaj silindi."
        "sv" -> "Meddelande raderat."
        else -> "Message deleted."
    }

    enum class VoiceCommand { DISMISS, DELETE, REPLY, UNKNOWN }

    /**
     * Classify a recognized STT result into a VoiceCommand.
     * Checks both English keywords (always) and keywords for [locale].
     */
    fun classifyCommand(text: String, locale: Locale): VoiceCommand {
        val lower = text.lowercase().trim()
        val lang = locale.language

        // Always check English + detected language keywords
        val langs = setOf("en", lang)

        val dismissSet = langs.flatMap { dismissKeywords[it] ?: emptySet() }.toSet()
        val deleteSet  = langs.flatMap { deleteKeywords[it] ?: emptySet() }.toSet()
        val replySet   = langs.flatMap { replyKeywords[it] ?: emptySet() }.toSet()

        return when {
            dismissSet.any { lower.contains(it) } -> VoiceCommand.DISMISS
            deleteSet.any  { lower.contains(it) } -> VoiceCommand.DELETE
            replySet.any   { lower.contains(it) } -> VoiceCommand.REPLY
            else -> VoiceCommand.UNKNOWN
        }
    }

    /**
     * Detect the language of [text] using ML Kit Language Identification.
     * Calls [onResult] with the detected [Locale], or [Locale.getDefault()] on failure/undetermined.
     */
    fun detectLanguage(text: String, onResult: (Locale) -> Unit) {
        val identifier = LanguageIdentification.getClient(
            com.google.mlkit.nl.languageid.LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(0.5f)
                .build()
        )
        identifier.identifyLanguage(text)
            .addOnSuccessListener { langCode ->
                val locale = if (langCode == "und" || langCode.isBlank()) {
                    Timber.d("DriveModeLang: language undetermined, using device default")
                    Locale.getDefault()
                } else {
                    Timber.d("DriveModeLang: detected language = $langCode")
                    Locale.forLanguageTag(langCode)
                }
                identifier.close()
                onResult(locale)
            }
            .addOnFailureListener { e ->
                Timber.w(e, "DriveModeLang: language detection failed, using device default")
                identifier.close()
                onResult(Locale.getDefault())
            }
    }
}
