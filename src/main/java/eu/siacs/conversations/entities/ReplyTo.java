package eu.siacs.conversations.entities;

import com.google.common.base.Strings;
import im.conversations.android.json.Services;

/**
 * XEP-0461 reference of a message to the one it answers: the referenced id (stanza-id in
 * channels, the message id otherwise), the author's address, and where the XEP-0428 quote
 * fallback sits in the body, in code points. The body keeps the fallback as sent so clients
 * without XEP-0461 (and OMEMO, which encrypts the body as is) still get the quote; it is only
 * hidden when displayed.
 */
public final class ReplyTo {

    public final String id;
    public final String to;
    public final int fallbackStart;
    public final int fallbackEnd;

    public ReplyTo(final String id, final String to, final int fallbackStart, final int fallbackEnd) {
        this.id = id;
        this.to = to;
        this.fallbackStart = fallbackStart;
        this.fallbackEnd = fallbackEnd;
    }

    public boolean hasFallback() {
        return fallbackStart >= 0 && fallbackEnd > fallbackStart;
    }

    /** The body without the quote fallback; the body unchanged when the range doesn't fit it. */
    public String stripFallback(final String body) {
        if (body == null || !hasFallback()) {
            return body;
        }
        final int count = body.codePointCount(0, body.length());
        if (fallbackEnd > count) {
            return body;
        }
        final int start = body.offsetByCodePoints(0, fallbackStart);
        final int end = body.offsetByCodePoints(0, fallbackEnd);
        return body.substring(0, start) + body.substring(end);
    }

    public static String toString(final ReplyTo replyTo) {
        return replyTo == null ? null : Services.GSON.toJson(replyTo);
    }

    public static ReplyTo fromString(final String value) {
        if (Strings.isNullOrEmpty(value)) {
            return null;
        }
        try {
            final var replyTo = Services.GSON.fromJson(value, ReplyTo.class);
            return replyTo == null || Strings.isNullOrEmpty(replyTo.id) ? null : replyTo;
        } catch (final RuntimeException e) {
            return null;
        }
    }
}
