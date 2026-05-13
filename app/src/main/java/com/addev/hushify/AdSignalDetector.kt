package com.addev.hushify

import android.app.Notification
import android.os.Bundle
import android.media.MediaMetadata
import java.text.Normalizer
import java.util.Locale

/**
 * Heuristic ad detection from notification payloads. Spotify does not expose a stable API;
 * when regional builds include words like "Advertisement" or "Publicidad" in the notification
 * extras, they are matched against [AD_PHRASES]. The set is built from [RAW_AD_PHRASES] by
 * folding accents and dropping any phrase that is already implied by substring [contains] of a
 * shorter kept phrase (e.g. "anuncios" implies "anuncio").
 */
internal object AdSignalDetector {

    private val MARK_SEGMENT_REGEX = Regex("\\p{M}+")

    private val RAW_AD_PHRASES = listOf(
        // English
        "advertisement",
        "advertizing",
        "advertising",
        "sponsor",
        "sponsored",
        "sponsor message",
        "promotional",
        "music promo",
        "audio ad",
        "video ad",
        "commercial",
        "ad break",
        // Spanish / Iberoamerican
        "anuncio",
        "anuncios",
        "publicidad",
        "promocional",
        "publicitaria",
        "promoción",
        "publicitario",
        "propaganda",
        "patrocinado",
        // Portuguese (BR/PT + shared roots)
        "anúncio",
        "comercialização",
        "publicidade sonora",
        // French
        "publicité",
        "publicite",
        "publicitaire",
        "message publicitaire",
        "messagerie publicitaire",
        "coup de pub",
        "bloc pub",
        "spot publicitaire",
        "promotion payante",
        // German
        "werbung",
        "werbespot",
        "werbepause",
        // Italian
        "pubblicità",
        "pubblicita",
        "messaggio pubblicitario",
        "promozione",
        // Dutch
        "advertentie",
        "reclamespot",
        // Nordic / Baltic
        "reklama",
        "reklame",
        "reklám",
        "reklām",
        "annonse",
        "annonsering",
        "mainos",
        "mainosten",
        // Turkish / Indonesian / Malay hints
        "reklam",
        "iklan",
        // Romanian / similar Latin
        "publicitate",
        "reclamă",
        // Polish / Czech hints
        "reklamy",
        "kampania reklamowa",
        "kampania reklam",
        // Hebrew (Occasionally in global builds)
        "פרסומת",
        // Asian scripts
        "广告",
        "廣告",
        "広告",
        "광고",
        "ประกาศ",
        // Arabic (common wording fragments)
        "إعلان",
        "اعلان",
        "اشهار",
        // Cyrillic (Russian etc.)
        "реклама",
        "коммерческое",
        "рекламный блок",
        // Hindi / romanized cues some OEMs expose
        "विज्ञापन",
        // Greek fragments
        "διαφημιση",
        "διαφήμιση",
        "χορηγ",
        // Action / UI copy Spotify and OEMs sometimes expose (not in body text)
        "skip ad",
        "skip ads",
        "saltar anuncio",
        "saltar publicidad",
        "omitir anuncio",
        "omitir publicidad",
        "pular anúncio",
        "pular anuncio",
        "überspringen",
        "annonce überspringen",
        "passer la pub",
        "passer la publicité",
        "salta annuncio",
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
        val raw = collectText(notification)
        if (raw.isBlank()) return false
        val folded = asciiFold(raw.lowercase(Locale.ROOT))
        val rawLower = raw.lowercase(Locale.ROOT)
        return AD_PHRASES.any { phrase ->
            phrase.isNotBlank() && (folded.contains(phrase) || rawLower.contains(phrase))
        }
    }

    private fun asciiFold(source: String): String {
        val normalized = Normalizer.normalize(source, Normalizer.Form.NFKD)
        return normalized.replace(MARK_SEGMENT_REGEX, "").lowercase(Locale.ROOT)
    }

    private fun collectText(notification: Notification): String {
        val extras = notification.extras
        val out = StringBuilder()
        if (extras != null) {
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

    private fun flattenBundleStrings(bundle: Bundle, sink: StringBuilder, depth: Int = 0) {
        if (depth > 4) return
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
                        else -> Unit
                    }
                }
                is Iterable<*> -> value.forEach { item ->
                    when (item) {
                        is MediaMetadata -> appendMediaMetadata(item, sink)
                        is CharSequence -> sink.append('\n').append(item)
                        is String -> sink.append('\n').append(item)
                        is Bundle -> flattenBundleStrings(item, sink, depth + 1)
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun appendMediaMetadata(md: MediaMetadata, sink: StringBuilder) {
        for (key in MEDIA_METADATA_TEXT_KEYS) {
            md.getString(key)?.let { sink.append('\n').append(it) }
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
