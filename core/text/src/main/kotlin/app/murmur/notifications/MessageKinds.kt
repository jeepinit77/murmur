package app.murmur.notifications

/** What a chat message holds. Everything but [TEXT] is something Murmur can't read itself. */
enum class MessageKind(val label: String, val plural: String) {
    TEXT("message", "messages"),
    VOICE("voice message", "voice messages"),
    PHOTO("photo", "photos"),
    VIDEO("video", "videos"),
    GIF("GIF", "GIFs"),
    STICKER("sticker", "stickers"),
    AUDIO("audio file", "audio files"),
    DOCUMENT("document", "documents"),
    LOCATION("location", "locations"),
    CONTACT("contact", "contacts"),
    POLL("poll", "polls"),
    /** "This message was deleted": never read. */
    DELETED("deleted message", "deleted messages"),
}

/**
 * Recognizes attachments in chat notifications. WhatsApp shows them as an emoji and a label, such
 * as "🎤 Voice message (0:12)" or "📷 Photo", with the emoji the same in every language. When the
 * sender added a caption, the caption replaces the label ("📷 At the beach"). Other apps may
 * attach the file itself with a MIME type instead.
 */
object MessageKinds {
    /** The kind of a message, plus its caption, file name, contact or poll question when it has one. */
    data class Classified(val kind: MessageKind, val detail: String? = null)

    private fun emoji(vararg codePoints: Int) = codePoints.joinToString("") { String(Character.toChars(it)) }

    /** Emoji prefixes, longest match first, with the labels that mean "no caption". */
    private class Prefix(val emoji: String, val kind: MessageKind, val labels: Set<String>)

    private val PREFIXES = listOf(
        Prefix(emoji(0x1F3A4), MessageKind.VOICE, emptySet()), // 🎤
        Prefix(emoji(0x1F399), MessageKind.VOICE, emptySet()), // 🎙
        Prefix(emoji(0x1F4F7), MessageKind.PHOTO, setOf("photo", "view once photo")), // 📷
        Prefix(emoji(0x1F4F8), MessageKind.PHOTO, setOf("photo", "view once photo")), // 📸
        Prefix(emoji(0x1F4F9), MessageKind.VIDEO, setOf("video", "view once video", "video note")), // 📹
        Prefix(emoji(0x1F3A5), MessageKind.VIDEO, setOf("video", "view once video", "video note")), // 🎥
        Prefix(emoji(0x1F47E), MessageKind.GIF, setOf("gif")), // 👾
        Prefix(emoji(0x1F49F), MessageKind.STICKER, setOf("sticker")), // 💟
        Prefix(emoji(0x1F3B5), MessageKind.AUDIO, setOf("audio")), // 🎵
        Prefix(emoji(0x1F3B6), MessageKind.AUDIO, setOf("audio")), // 🎶
        Prefix(emoji(0x1F4C4), MessageKind.DOCUMENT, setOf("document")), // 📄
        Prefix(emoji(0x1F4C3), MessageKind.DOCUMENT, setOf("document")), // 📃
        Prefix(emoji(0x1F4CE), MessageKind.DOCUMENT, setOf("document", "file")), // 📎
        Prefix(emoji(0x1F4CD), MessageKind.LOCATION, setOf("location", "live location")), // 📍
        Prefix(emoji(0x1F464), MessageKind.CONTACT, setOf("contact")), // 👤
        Prefix(emoji(0x1F4CA), MessageKind.POLL, setOf("poll")), // 📊
        Prefix(emoji(0x1F6AB), MessageKind.DELETED, emptySet()), // 🚫
    )

    /** Messages that are only a label, as some apps post them without an emoji. */
    private val PLAIN = mapOf(
        "photo" to MessageKind.PHOTO,
        "video" to MessageKind.VIDEO,
        "gif" to MessageKind.GIF,
        "sticker" to MessageKind.STICKER,
        "voice message" to MessageKind.VOICE,
        "this message was deleted" to MessageKind.DELETED,
        "you deleted this message" to MessageKind.DELETED,
        "waiting for this message. this may take a while." to MessageKind.DELETED,
    )

    /** Direction marks, variation selectors and spaces around the emoji. */
    private val PADDING = charArrayOf(' ', '‎', '‏', '‪', '‫', '‬', '⁦', '⁧', '⁨', '⁩', '️')
    private val DURATION = Regex("""\((?:(\d+):)?(\d{1,2}):(\d{2})\)""")
    private val POLL_PREFIX = Regex("""^poll:\s*""", RegexOption.IGNORE_CASE)

    fun classify(text: String, mimeType: String? = null): Classified {
        val t = text.trim(*PADDING)
        PREFIXES.firstOrNull { t.startsWith(it.emoji) }?.let { p ->
            val rest = t.removePrefix(p.emoji).trim(*PADDING)
            val detail = when (p.kind) {
                MessageKind.VOICE, MessageKind.DELETED -> null
                MessageKind.POLL -> rest.replace(POLL_PREFIX, "")
                else -> rest.takeUnless { it.lowercase() in p.labels }
            }
            return Classified(p.kind, detail?.takeIf { it.isNotBlank() })
        }
        PLAIN[t.lowercase()]?.let { return Classified(it) }
        val mime = mimeType?.lowercase()
        val kind = when {
            mime == null -> MessageKind.TEXT
            mime.startsWith("audio/") -> MessageKind.VOICE
            mime == "image/gif" -> MessageKind.GIF
            mime == "image/webp" -> MessageKind.STICKER
            mime.startsWith("image/") -> MessageKind.PHOTO
            mime.startsWith("video/") -> MessageKind.VIDEO
            else -> MessageKind.TEXT
        }
        // With a file attached, the text is its caption.
        return Classified(kind, if (kind == MessageKind.TEXT) null else t.takeIf { it.isNotBlank() })
    }

    /** What to say for a message within a conversation: "Photo: At the beach." or "Voice message, 12 seconds." */
    fun spoken(m: IncomingMessage): String {
        val name = m.kind.label.replaceFirstChar { it.uppercase() }
        return when {
            m.kind == MessageKind.TEXT -> m.text
            m.kind == MessageKind.VOICE -> durationSeconds(m.text)?.let { "$name, ${spokenDuration(it)}." } ?: "$name."
            m.detail != null -> "$name: ${m.detail}"
            else -> "$name."
        }
    }

    /** The length in seconds from "(0:12)" or "(1:02:03)", or null when the text has none. */
    fun durationSeconds(text: String): Int? {
        val m = DURATION.find(text) ?: return null
        val (h, min, sec) = m.destructured
        return (h.toIntOrNull() ?: 0) * 3600 + min.toInt() * 60 + sec.toInt()
    }

    private fun spokenDuration(total: Int): String {
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return listOfNotNull(
            count(h, "hour", "hours").takeIf { h > 0 },
            count(m, "minute", "minutes").takeIf { m > 0 },
            count(s, "second", "seconds").takeIf { s > 0 || total == 0 },
        ).joinToString(" ")
    }

    /** "a photo", "2 voice messages". */
    fun count(n: Int, kind: MessageKind): String =
        if (n == 1) (if (kind.label.first().lowercaseChar() in "aeiou") "an " else "a ") + kind.label else "$n ${kind.plural}"

    private fun count(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"
}
