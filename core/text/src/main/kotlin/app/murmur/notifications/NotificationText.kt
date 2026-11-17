package app.murmur.notifications

import app.murmur.text.Chapter
import app.murmur.text.Document

/** One message from a conversation-style (MessagingStyle) notification. */
data class IncomingMessage(
    /** Who sent it; null when the phone's owner sent it (a reply shown in the conversation). */
    val sender: String?,
    val text: String,
    val time: Long,
    /** A photo, voice message, sticker and so on, recognized by [MessageKinds]. */
    val kind: MessageKind = MessageKind.TEXT,
    /** The caption, file name, contact name or poll question of an attachment, when it has one. */
    val detail: String? = null,
) {
    val id: String get() = "$sender|$time|${text.hashCode()}"

    /** Has words to read: text, or an attachment with a caption. */
    val readable: Boolean get() = kind == MessageKind.TEXT || detail != null
}

/** The readable parts of a notification posted by another app, taken from its extras. */
data class IncomingNotification(
    val packageName: String,
    val appName: String,
    /** The system's key for the source notification, used to notice when it's removed. */
    val sourceKey: String,
    /** Groups notifications that belong to the same chat. */
    val conversationKey: String,
    /** Messages for conversation-style notifications; empty for everything else. */
    val messages: List<IncomingMessage> = emptyList(),
    /** The chat's name for group chats ("Family"). */
    val conversationTitle: String? = null,
    val isGroup: Boolean = false,
    /** The display name of the phone's owner in this chat, to skip their own replies. */
    val selfName: String? = null,
    val title: String? = null,
    /** The fullest text available: expanded text, inbox lines or the regular text. */
    val body: String? = null,
) {
    val isConversation: Boolean get() = messages.isNotEmpty()
}

/** How much of a notification Murmur reads aloud, chosen for each app. */
enum class SpokenParts {
    /** Only the message, or a notification's text. */
    MESSAGE,
    /** Who sent it (or a notification's title), then the message. */
    SENDER_AND_MESSAGE,
    /** The app's name, who sent it, then the message. */
    APP_SENDER_AND_MESSAGE,
}

/** Unheard notifications from one chat, or one notification from a non-messaging app. */
data class NotificationBatch(
    /** Stable for the life of the process; used for Murmur's notification id. */
    val id: Int,
    val key: String,
    val packageName: String,
    val appName: String,
    val sourceKeys: Set<String>,
    val messages: List<IncomingMessage>,
    val conversationTitle: String?,
    val isGroup: Boolean,
    val title: String?,
    val body: String?,
) {
    val isConversation: Boolean get() = messages.isNotEmpty()

    /** Every new message is an attachment without a caption, so there's nothing to read but who sent them. */
    val nothingToRead: Boolean get() = isConversation && messages.none { it.readable }

    /** "Mom" or "Family" for chats; the notification's title otherwise. */
    val heading: String
        get() = when {
            isConversation && isGroup -> conversationTitle ?: title ?: appName
            isConversation -> messages.lastOrNull { it.sender != null }?.sender ?: conversationTitle ?: title ?: appName
            else -> title ?: appName
        }

    /** "3 messages", the only message's text, or the notification's text. */
    val summary: String
        get() = when {
            isConversation && messages.size > 1 -> "${messages.size} messages"
            isConversation -> messages.single().let { m -> if (isGroup && m.sender != null) "${m.sender}: ${m.text}" else m.text }
            else -> body.orEmpty()
        }

    /** The text Murmur reads aloud, as a one-chapter document. */
    fun toDocument(parts: SpokenParts = SpokenParts.APP_SENDER_AND_MESSAGE): Document = Document(
        title = if (isConversation && messages.size > 1) "$heading · ${messages.size} messages" else heading,
        chapters = listOf(Chapter(title = null, paragraphs = spokenParagraphs(parts))),
    )

    fun spokenParagraphs(parts: SpokenParts = SpokenParts.APP_SENDER_AND_MESSAGE): List<String> {
        val withApp = parts == SpokenParts.APP_SENDER_AND_MESSAGE
        val withSender = parts != SpokenParts.MESSAGE
        val out = mutableListOf<String>()
        if (nothingToRead) {
            out += attachmentAnnouncement(withApp, withSender)
        } else if (isConversation) {
            val count = if (messages.size > 1) ", ${messages.size} messages" else ""
            when {
                withApp -> out += sentence("$appName. $heading$count")
                withSender -> out += sentence("$heading$count")
            }
            var lastSender: String? = null
            messages.forEach { m ->
                // In group chats, say who is speaking whenever it changes.
                val text = MessageKinds.spoken(m)
                out += if (withSender && isGroup && m.sender != null && m.sender != lastSender) "${m.sender}: $text" else text
                lastSender = m.sender
            }
        } else {
            if (withApp) out += sentence(appName)
            val t = title?.trim()
            val b = body?.trim()
            // With the message only, the title is read only when there's no text.
            if (!t.isNullOrEmpty() && (b == null || !b.startsWith(t)) && (withSender || b.isNullOrEmpty())) out += sentence(t)
            if (!b.isNullOrEmpty()) out += b.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        }
        return out
    }

    /**
     * "WhatsApp. New voice message from Tim Jones.", "WhatsApp. 3 new photos in Family." or
     * "WhatsApp. New messages from Tim Jones: 2 photos and a sticker."
     */
    private fun attachmentAnnouncement(withApp: Boolean, withSender: Boolean): String {
        val app = if (withApp) "$appName. " else ""
        val from = if (!withSender) "" else messages.mapNotNull { it.sender }.distinct().singleOrNull()?.let { " from $it" }.orEmpty()
        val where = if (withSender && isGroup) (conversationTitle ?: title)?.let { " in $it" }.orEmpty() else ""
        val kinds = messages.groupingBy { it.kind }.eachCount()
        val what = when {
            kinds.size > 1 -> {
                val parts = kinds.map { (kind, n) -> MessageKinds.count(n, kind) }
                return "${app}New messages$from$where: ${parts.dropLast(1).joinToString(", ")} and ${parts.last()}."
            }
            messages.size == 1 -> "New ${messages.single().kind.label}"
            else -> "${messages.size} new ${messages.first().kind.plural}"
        }
        return "$app$what$from$where."
    }

    private fun sentence(s: String) = if (s.endsWith('.') || s.endsWith('!') || s.endsWith('?')) s else "$s."
}

/**
 * Turns notifications into batches of unheard messages. Messaging apps such as WhatsApp post the
 * whole conversation again with each new message, so messages already seen are skipped.
 *
 * Not thread-safe: use it from one thread (the main thread in the app).
 */
class NotificationBatcher(private val maxSeen: Int = 1000) {
    private val seen = LinkedHashSet<String>()
    private val batches = LinkedHashMap<String, NotificationBatch>()
    private val ids = HashMap<String, Int>()
    /** The messages in each conversation's latest notification, as (sender, text). */
    private val lastShown = HashMap<String, List<Pair<String?, String>>>()
    private var nextId = 0

    /** Adds what's new in [n]. Returns the updated batch, or null when nothing in it is new. */
    fun offer(n: IncomingNotification): NotificationBatch? =
        if (n.isConversation) offerConversation(n) else offerOther(n)

    private fun offerConversation(n: IncomingNotification): NotificationBatch? {
        // Chat apps re-post the conversation with new messages added at the end. Messages that
        // overlap the end of the previous post are old, even if the app gave them new times.
        val shown = n.messages.map { it.sender to it.text }
        val previous = lastShown[n.conversationKey].orEmpty()
        lastShown[n.conversationKey] = shown
        val overlap = (minOf(shown.size, previous.size) downTo 1).firstOrNull { k ->
            shown.subList(0, k) == previous.subList(previous.size - k, previous.size)
        } ?: 0
        val fresh = n.messages.drop(overlap)
            .filter { it.text.isNotBlank() && it.sender != null && (n.selfName == null || it.sender != n.selfName) }
            .filter { it.kind != MessageKind.DELETED }
            .filter { markSeen(it.id) }
        n.messages.take(overlap).forEach { markSeen(it.id) }
        if (fresh.isEmpty()) return null
        val existing = batches[n.conversationKey]
        val batch = NotificationBatch(
            id = idFor(n.conversationKey),
            key = n.conversationKey,
            packageName = n.packageName,
            appName = n.appName,
            sourceKeys = (existing?.sourceKeys ?: emptySet()) + n.sourceKey,
            messages = (existing?.messages ?: emptyList()) + fresh,
            conversationTitle = n.conversationTitle ?: existing?.conversationTitle,
            isGroup = n.isGroup,
            title = n.title ?: existing?.title,
            body = null,
        )
        batches[n.conversationKey] = batch
        return batch
    }

    private fun offerOther(n: IncomingNotification): NotificationBatch? {
        if (n.title.isNullOrBlank() && n.body.isNullOrBlank()) return null
        // An app updating a notification with the same text (a progress tick, a re-post) isn't new.
        if (!markSeen("${n.sourceKey}|${n.title}|${n.body}".hashCode().toString())) return null
        val batch = NotificationBatch(
            id = idFor(n.conversationKey),
            key = n.conversationKey,
            packageName = n.packageName,
            appName = n.appName,
            sourceKeys = setOf(n.sourceKey),
            messages = emptyList(),
            conversationTitle = null,
            isGroup = false,
            title = n.title,
            body = n.body,
        )
        batches[n.conversationKey] = batch
        return batch
    }

    fun get(id: Int): NotificationBatch? = batches.values.firstOrNull { it.id == id }

    fun all(): List<NotificationBatch> = batches.values.toList()

    /** Removes a batch once it's been read or dismissed. Its messages stay marked as seen. */
    fun remove(id: Int): NotificationBatch? {
        val b = get(id) ?: return null
        batches.remove(b.key)
        return b
    }

    /** The source notification was removed (for example, the chat was read in its app). */
    fun removeSource(sourceKey: String): NotificationBatch? {
        val b = batches.values.firstOrNull { sourceKey in it.sourceKeys } ?: return null
        batches.remove(b.key)
        return b
    }

    fun clear() = batches.clear()

    private fun idFor(key: String) = ids.getOrPut(key) { nextId++ }

    private fun markSeen(id: String): Boolean {
        if (!seen.add(id)) return false
        if (seen.size > maxSeen) seen.iterator().let { it.next(); it.remove() }
        return true
    }
}
