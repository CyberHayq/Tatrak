package eu.siacs.conversations.entities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ReplyToTest {

    @Test
    public void stripsTheFallbackCountingCodePoints() {
        // "> 👍\n" is 4 code points but 5 UTF-16 chars
        final var reply = new ReplyTo("id1", "a@example.com", 0, 4);
        assertEquals("Thanks", reply.stripFallback("> 👍\nThanks"));
    }

    @Test
    public void leavesTheBodyWhenTheRangeDoesNotFit() {
        final var reply = new ReplyTo("id1", null, 0, 50);
        assertEquals("short", reply.stripFallback("short"));
        assertEquals("text", new ReplyTo("id1", null, -1, -1).stripFallback("text"));
    }

    @Test
    public void survivesTheDatabaseRoundTrip() {
        final var reply = ReplyTo.fromString(ReplyTo.toString(new ReplyTo("id1", "r@c/nick", 0, 7)));
        assertEquals("id1", reply.id);
        assertEquals("r@c/nick", reply.to);
        assertEquals(7, reply.fallbackEnd);
        assertNull(ReplyTo.fromString(null));
        assertNull(ReplyTo.fromString("not json"));
    }
}
