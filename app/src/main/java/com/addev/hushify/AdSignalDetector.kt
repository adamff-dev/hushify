package com.addev.hushify

import android.app.Notification
import android.os.Bundle
import android.media.MediaMetadata
import androidx.core.app.NotificationCompat
import java.text.Normalizer
import java.util.Locale

/**
 * Heuristic ad detection from notification payloads. Spotify does not expose a stable API.
 *
 * [RAW_AD_PHRASES] are the exact UI translations of Spotify resource `string/advertisement`
 * extracted from bundled Spotify Universal APK via `aapt dump --values resources`;
 * [buildMinimalPhraseSet] folds accents / removes substring-redundant entries for matching.
 */
internal object AdSignalDetector {

    private val MARK_SEGMENT_REGEX = Regex("\\p{M}+")

    /** Exact `string/advertisement` locales from Spotify universal APK (51 unique surfaces). */
    private val RAW_AD_PHRASES = listOf(
        "Advertensie",
        "Advertentie",
        "Advertisement",
        "Annonse",
        "Anunci",
        "Anuncio",
        "Anunț",
        "Anúncio",
        "Auglýsing",
        "Iklan",
        "Iragarkia",
        "Isikhangiso",
        "Mainos",
        "Oglas",
        "Propaganda",
        "Pubblicità",
        "Publicidad",
        "Publicidade",
        "Publicité",
        "Quảng cáo",
        "Reklaam",
        "Reklam",
        "Reklama",
        "Reklame",
        "Reklám",
        "Reklāma",
        "Tangazo",
        "Werbung",
        "Διαφήμιση",
        "Реклама",
        "פרסומת",
        "آگهی",
        "إشهار",
        "إعلان",
        "اشتہار",
        "जाहिरात",
        "विज्ञापन",
        "বিজ্ঞাপন",
        "ਵਿਗਿਆਪਨ",
        "જાહેરાત",
        "ବିଜ୍ଞାପନ",
        "விளம்பரம்",
        "ప్రకటన",
        "ಜಾಹೀರಾತು",
        "പരസ്യം",
        "โฆษณา",
        "ማስታወቂያ",
        "广告",
        "広告",
        "廣告",
        "광고",
    )

    private val AD_PHRASES: List<String> = buildMinimalPhraseSet(RAW_AD_PHRASES)

    private fun buildMinimalPhraseSet(raw: List<String>): List<String> {
        val folded = raw.asSequence()
            .map { asciiFold(it.lowercase(Locale.ROOT)).trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedBy { it.length }
            .toList()
        val minimal = mutableListOf<String>()
        for (candidate in folded) {
            val impliedByShorter = minimal.any { keeper ->
                keeper.length <= candidate.length && candidate.contains(keeper)
            }
            if (impliedByShorter) continue
            minimal.removeAll { keeper ->
                keeper.length > candidate.length && keeper.contains(candidate)
            }
            minimal.add(candidate)
        }
        return minimal
    }

    fun isLikelyAd(notification: Notification): Boolean {
        return appliesAdPhraseHeuristic(collectText(notification))
    }

    /**
     * True when [rawText] matches the same heuristic phrases as notifications.
     * Used for Spotify [MediaMetadata] surfaced via MediaSession rather than extras.
     */
    fun appliesAdPhraseHeuristic(rawText: String): Boolean {
        if (rawText.isBlank()) return false
        val folded = asciiFold(rawText.lowercase(Locale.ROOT))
        val rawLower = rawText.lowercase(Locale.ROOT)
        return AD_PHRASES.any { phrase ->
            phrase.isNotBlank() && (folded.contains(phrase) || rawLower.contains(phrase))
        }
    }

    fun concatenatedMediaMetadata(metadata: MediaMetadata?): String {
        if (metadata == null) return ""
        val sink = StringBuilder()
        appendMediaMetadata(metadata, sink)
        return sink.toString()
    }

    private fun asciiFold(source: String): String {
        val normalized = Normalizer.normalize(source, Normalizer.Form.NFKD)
        return normalized.replace(MARK_SEGMENT_REGEX, "").lowercase(Locale.ROOT)
    }

    private fun collectText(notification: Notification): String {
        val extras = notification.extras
        val out = StringBuilder()
        if (extras != null) {
            appendPrimaryNotificationCharSequences(extras, out)
            appendInboxTextLinesCompat(extras, out)
            appendMessagingStyleTexts(notification, out)
            flattenBundleStrings(extras, out)
        }
        notification.tickerText?.let {
            out.append('\n').append(it)
        }
        notification.actions?.forEach { action ->
            action.title?.let { out.append('\n').append(it) }
        }
        return out.toString()
    }

    /** Common extras Spotify/OEM layouts use; may be absent from naive bundle walks. */
    private fun appendPrimaryNotificationCharSequences(extras: Bundle, sink: StringBuilder) {
        val keys = arrayOf(
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TITLE_BIG,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUMMARY_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_INFO_TEXT,
            NotificationCompat.EXTRA_SUB_TEXT,
            NotificationCompat.EXTRA_SELF_DISPLAY_NAME,
            "android.messagingStyleConversationTitleCompat",
            "android.hiddenConversationTitleCompat",
        )
        for (key in keys) {
            extras.getCharSequence(key)?.takeIf { it.isNotBlank() }
                ?.let { sink.append('\n').append(it) }
        }
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { line ->
            if (line.isNotBlank()) sink.append('\n').append(line)
        }
    }

    private fun appendInboxTextLinesCompat(extras: Bundle, sink: StringBuilder) {
        extras.getCharSequenceArray(NotificationCompat.EXTRA_TEXT_LINES)?.forEach {
            if (it.isNotBlank()) sink.append('\n').append(it)
        }
    }

    private fun appendMessagingStyleTexts(notification: Notification, sink: StringBuilder) {
        val extracted = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(notification) ?: return
        extracted.conversationTitle?.takeIf { it.isNotBlank() }
            ?.let { sink.append('\n').append(it) }
        for (msg in extracted.messages) {
            msg.text?.takeIf { it.isNotBlank() }?.let { sink.append('\n').append(it) }
            msg.person?.name?.takeIf { it.isNotBlank() }?.let { sink.append('\n').append(it) }
        }
    }

    private fun flattenBundleStrings(bundle: Bundle, sink: StringBuilder, depth: Int = 0) {
        if (depth > 8) return
        for (key in bundle.keySet()) {
            val value = bundle.get(key) ?: continue
            when (value) {
                is MediaMetadata -> appendMediaMetadata(value, sink)
                is CharSequence -> sink.append('\n').append(value)
                is String -> sink.append('\n').append(value)
                is Bundle -> flattenBundleStrings(value, sink, depth + 1)
                is Array<*> -> value.forEach { item ->
                    when (item) {
                        is MediaMetadata -> appendMediaMetadata(item, sink)
                        is CharSequence -> sink.append('\n').append(item)
                        is String -> sink.append('\n').append(item)
                        is Bundle -> flattenBundleStrings(item, sink, depth + 1)
                        else -> item?.let { appendIfJavaMessageParcelable(it, sink, depth + 1) }
                    }
                }
                is Iterable<*> -> value.forEach { item ->
                    when (item) {
                        is MediaMetadata -> appendMediaMetadata(item, sink)
                        is CharSequence -> sink.append('\n').append(item)
                        is String -> sink.append('\n').append(item)
                        is Bundle -> flattenBundleStrings(item, sink, depth + 1)
                        else -> item?.let { appendIfJavaMessageParcelable(it, sink, depth + 1) }
                    }
                }
                else -> appendIfJavaMessageParcelable(value, sink, depth + 1)
            }
        }
    }

    private fun appendIfJavaMessageParcelable(value: Any, sink: StringBuilder, depth: Int) {
        if (depth > 8) return
        val clsName = value.javaClass.name
        if (!looksLikeMessagingStyleMessage(clsName)) return
        runCatching {
            val textM = value.javaClass.getMethod("getText")
            textM.invoke(value)?.let {
                sink.append('\n').append(it)
            }
            val senderM = value.javaClass.methods.find { it.name == "getSender" && it.parameterCount == 0 }
            senderM?.invoke(value)?.takeIfSendable()?.let { sink.append('\n').append(it) }
        }
    }

    /** Framework / compat `MessagingStyle$Message` nested class naming varies by APK. */
    private fun looksLikeMessagingStyleMessage(className: String): Boolean {
        val simple = className.substringAfterLast('.').filter { it != '$' }
        return className.contains("MessagingStyle") && simple.contains("Message")
    }

    private fun Any?.takeIfSendable(): Any? =
        takeIf {
            val s = "$it".trim()
            s.isNotEmpty() && s != "null"
        }

    private fun appendMediaMetadata(md: MediaMetadata, sink: StringBuilder) {
        runCatching {
            val d = md.description
            d.title?.takeIf { it.isNotBlank() }?.let { sink.append('\n').append(it) }
            d.subtitle?.takeIf { it.isNotBlank() }?.let { sink.append('\n').append(it) }
            d.description?.takeIf { it.isNotBlank() }?.let { sink.append('\n').append(it) }
        }
        for (key in MEDIA_METADATA_TEXT_KEYS) {
            md.getString(key)?.takeIf(String::isNotBlank)?.let { sink.append('\n').append(it) }
            md.getText(key)?.takeIf(CharSequence::isNotBlank)?.let { sink.append('\n').append(it) }
        }
    }
}

private val MEDIA_METADATA_TEXT_KEYS = arrayOf(
    MediaMetadata.METADATA_KEY_TITLE,
    MediaMetadata.METADATA_KEY_ARTIST,
    MediaMetadata.METADATA_KEY_ALBUM,
    MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
    MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
    MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION,
)
