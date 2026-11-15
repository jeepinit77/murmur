package app.murmur.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationBatcherTest {
    private fun chat(vararg messages: IncomingMessage, group: Boolean = false, title: String? = null) = IncomingNotification(
        packageName = "com.whatsapp",
        appName = "WhatsApp",
        sourceKey = "0|com.whatsapp|1|mom|10",
        conversationKey = "com.whatsapp|mom",
        messages = messages.toList(),
        conversationTitle = title,
        isGroup = group,
        selfName = "You",
    )

    private val m1 = IncomingMessage("Mom", "Are you coming for dinner?", 1)
    private val m2 = IncomingMessage("Mom", "We're having lasagna.", 2)
    private val m3 = IncomingMessage("Mom", "Bring the dog.", 3)

    @Test fun repostedConversationOnlyAddsNewMessages() {
        val b = NotificationBatcher()
        assertEquals(1, b.offer(chat(m1))!!.messages.size)
        assertEquals(2, b.offer(chat(m1, m2))!!.messages.size)
        val batch = b.offer(chat(m1, m2, m3))!!
        assertEquals(listOf(m1, m2, m3), batch.messages)
        assertEquals("3 messages", batch.summary)
        assertEquals("Mom", batch.heading)
        assertNull(b.offer(chat(m1, m2, m3)))
    }

    @Test fun repostsWithNewTimesAreStillRecognized() {
        val b = NotificationBatcher()
        b.offer(chat(m1, m2))
        val batch = b.offer(chat(m1.copy(time = 10), m2.copy(time = 11), m3.copy(time = 12)))!!
        assertEquals(listOf(m1, m2, m3.copy(time = 12)), batch.messages)
    }

    @Test fun repeatedTextFromANewPostIsKept() {
        val b = NotificationBatcher()
        val ok1 = IncomingMessage("Mom", "OK", 1)
        b.offer(chat(ok1))
        val batch = b.offer(chat(ok1, IncomingMessage("Mom", "OK", 2)))!!
        assertEquals(2, batch.messages.size)
    }

    @Test fun heardMessagesAreNotReadAgain() {
        val b = NotificationBatcher()
        val first = b.offer(chat(m1, m2))!!
        b.remove(first.id)
        val next = b.offer(chat(m1, m2, m3))!!
        assertEquals(listOf(m3), next.messages)
        assertEquals(first.id, next.id)
    }

    @Test fun ownRepliesAreSkipped() {
        val b = NotificationBatcher()
        val batch = b.offer(chat(m1, IncomingMessage("You", "Yes", 2), IncomingMessage(null, "On my way", 3)))!!
        assertEquals(listOf(m1), batch.messages)
    }

    @Test fun groupChatsNameEachSenderWhenItChanges() {
        val b = NotificationBatcher()
        val batch = b.offer(
            chat(
                IncomingMessage("Mom", "Dinner at 6", 1),
                IncomingMessage("Mom", "Don't be late", 2),
                IncomingMessage("Dad", "I'll cook", 3),
                group = true, title = "Family",
            ),
        )!!
        assertEquals(
            listOf("WhatsApp. Family, 3 messages.", "Mom: Dinner at 6", "Don't be late", "Dad: I'll cook"),
            batch.spokenParagraphs(),
        )
        assertEquals("Family · 3 messages", batch.toDocument().title)
    }

    @Test fun singleMessageReadsWithoutCount() {
        val b = NotificationBatcher()
        val batch = b.offer(chat(m1))!!
        assertEquals(listOf("WhatsApp. Mom.", "Are you coming for dinner?"), batch.spokenParagraphs())
        assertEquals(m1.text, batch.summary)
    }

    @Test fun otherNotificationsReadTitleAndFullText() {
        val b = NotificationBatcher()
        val n = IncomingNotification(
            packageName = "com.example.mail", appName = "Mail", sourceKey = "k1", conversationKey = "k1",
            title = "Invoice ready", body = "Your invoice for October is ready.\n\nIt's due on the 30th.",
        )
        val batch = b.offer(n)!!
        assertEquals(
            listOf("Mail.", "Invoice ready.", "Your invoice for October is ready.", "It's due on the 30th."),
            batch.spokenParagraphs(),
        )
        assertNull(b.offer(n))
        assertEquals("New text", b.offer(n.copy(body = "New text"))!!.body)
    }

    @Test fun removingTheSourceDropsTheBatch() {
        val b = NotificationBatcher()
        val batch = b.offer(chat(m1))!!
        assertEquals(batch.id, b.removeSource("0|com.whatsapp|1|mom|10")!!.id)
        assertEquals(0, b.all().size)
    }

    @Test fun conversationsGetTheirOwnIds() {
        val b = NotificationBatcher()
        val a = b.offer(chat(m1))!!
        val other = b.offer(chat(IncomingMessage("Sam", "Hi", 5)).copy(conversationKey = "com.whatsapp|sam", sourceKey = "s"))!!
        assertNotEquals(a.id, other.id)
        assertEquals(2, b.all().size)
    }
}
