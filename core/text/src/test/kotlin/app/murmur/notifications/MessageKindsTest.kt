package app.murmur.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageKindsTest {
    private fun e(cp: Int) = String(Character.toChars(cp))
    private val mic = e(0x1F3A4)
    private val camera = e(0x1F4F7)
    private val sticker = e(0x1F49F)
    private val page = e(0x1F4C4)
    private val noEntry = e(0x1F6AB)

    private fun kind(text: String, mime: String? = null) = MessageKinds.classify(text, mime)

    @Test fun recognizesWhatsAppAttachments() {
        assertEquals(MessageKinds.Classified(MessageKind.VOICE), kind("$mic Voice message (0:12)"))
        assertEquals(MessageKinds.Classified(MessageKind.VOICE), kind("‎$mic Mensaje de voz (0:12)"))
        assertEquals(MessageKinds.Classified(MessageKind.PHOTO), kind("$camera Photo"))
        assertEquals(MessageKinds.Classified(MessageKind.PHOTO, "At the beach"), kind("$camera At the beach"))
        assertEquals(MessageKinds.Classified(MessageKind.STICKER), kind("$sticker Sticker"))
        assertEquals(MessageKinds.Classified(MessageKind.STICKER), kind("Sticker"))
        assertEquals(MessageKinds.Classified(MessageKind.DOCUMENT, "Lease.pdf"), kind("$page Lease.pdf"))
        assertEquals(MessageKinds.Classified(MessageKind.POLL, "Pizza or tacos?"), kind("${e(0x1F4CA)} POLL: Pizza or tacos?"))
        assertEquals(MessageKind.DELETED, kind("$noEntry This message was deleted").kind)
        assertEquals(MessageKind.DELETED, kind("This message was deleted").kind)
    }

    @Test fun usesTheAttachedFileType() {
        assertEquals(MessageKinds.Classified(MessageKind.VOICE), kind("", "audio/ogg"))
        assertEquals(MessageKinds.Classified(MessageKind.PHOTO, "Look"), kind("Look", "image/jpeg"))
        assertEquals(MessageKind.STICKER, kind("", "image/webp").kind)
    }

    @Test fun ordinaryTextIsText() {
        assertEquals(MessageKinds.Classified(MessageKind.TEXT), kind("Can you send me a photo?"))
        assertEquals(MessageKinds.Classified(MessageKind.TEXT), kind("Photo of the year"))
    }

    @Test fun readsDurations() {
        assertEquals(12, MessageKinds.durationSeconds("$mic Voice message (0:12)"))
        assertEquals(3723, MessageKinds.durationSeconds("$mic Voice message (1:02:03)"))
        assertNull(MessageKinds.durationSeconds("$mic Voice message"))
    }

    private fun msg(sender: String, text: String, time: Long): IncomingMessage {
        val c = MessageKinds.classify(text)
        return IncomingMessage(sender, text, time, c.kind, c.detail)
    }

    @Test fun speaksAttachmentsWithinAConversation() {
        assertEquals("Voice message, 1 minute 5 seconds.", MessageKinds.spoken(msg("Tim", "$mic Voice message (1:05)", 1)))
        assertEquals("Photo: At the beach", MessageKinds.spoken(msg("Tim", "$camera At the beach", 1)))
        assertEquals("Sticker.", MessageKinds.spoken(msg("Tim", "$sticker Sticker", 1)))
    }

    private fun chat(vararg messages: IncomingMessage, group: Boolean = false, title: String? = null) = IncomingNotification(
        packageName = "com.whatsapp",
        appName = "WhatsApp",
        sourceKey = "0|com.whatsapp|1|chat|10",
        conversationKey = "com.whatsapp|chat",
        messages = messages.toList(),
        conversationTitle = title,
        isGroup = group,
        selfName = "You",
    )

    @Test fun lonelyVoiceMessageAnnouncesTheSender() {
        val batch = NotificationBatcher().offer(chat(msg("Tim Jones", "$mic Voice message (0:12)", 1)))!!
        assertTrue(batch.nothingToRead)
        assertEquals(listOf("WhatsApp. New voice message from Tim Jones."), batch.spokenParagraphs())
    }

    @Test fun severalPhotosInAGroup() {
        val b = NotificationBatcher()
        val p1 = msg("Tim", "$camera Photo", 1)
        b.offer(chat(p1, group = true, title = "Family"))
        val batch = b.offer(chat(p1, msg("Ana", "$camera Photo", 2), group = true, title = "Family"))!!
        assertEquals(listOf("WhatsApp. 2 new photos in Family."), batch.spokenParagraphs())
    }

    @Test fun mixedAttachmentsAreCounted() {
        val b = NotificationBatcher()
        val p1 = msg("Tim Jones", "$camera Photo", 1)
        val p2 = msg("Tim Jones", "$camera Photo", 2)
        b.offer(chat(p1))
        b.offer(chat(p1, p2))
        val batch = b.offer(chat(p1, p2, msg("Tim Jones", "$sticker Sticker", 3)))!!
        assertEquals(listOf("WhatsApp. New messages from Tim Jones: 2 photos and a sticker."), batch.spokenParagraphs())
    }

    @Test fun captionedPhotoIsReadable() {
        val batch = NotificationBatcher().offer(chat(msg("Tim Jones", "$camera At the beach", 1)))!!
        assertFalse(batch.nothingToRead)
        assertEquals(listOf("WhatsApp. Tim Jones.", "Photo: At the beach"), batch.spokenParagraphs())
    }

    @Test fun attachmentAmongTextIsNamed() {
        val b = NotificationBatcher()
        val v = msg("Tim Jones", "$mic Voice message (0:12)", 1)
        b.offer(chat(v))
        val batch = b.offer(chat(v, msg("Tim Jones", "Call me back.", 2)))!!
        assertEquals(
            listOf("WhatsApp. Tim Jones, 2 messages.", "Voice message, 12 seconds.", "Call me back."),
            batch.spokenParagraphs(),
        )
    }

    @Test fun deletedMessagesAreSkipped() {
        val b = NotificationBatcher()
        assertNull(b.offer(chat(msg("Tim Jones", "This message was deleted", 1))))
        val batch = b.offer(chat(msg("Tim Jones", "This message was deleted", 1), msg("Tim Jones", "Sorry, wrong chat", 2)))!!
        assertEquals(listOf("Sorry, wrong chat"), batch.messages.map { it.text })
    }
}
