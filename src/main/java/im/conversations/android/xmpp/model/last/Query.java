package im.conversations.android.xmpp.model.last;

import com.google.common.base.Strings;
import com.google.common.primitives.Longs;
import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

/** XEP-0012 Last Activity. */
@XmlElement
public class Query extends Extension {

    public Query() {
        super(Query.class);
    }

    /** Seconds since the contact was last online (or idle, if online); null if absent or bad. */
    public Long getSeconds() {
        final var seconds = Longs.tryParse(Strings.nullToEmpty(this.getAttribute("seconds")));
        return seconds == null || seconds < 0 ? null : seconds;
    }
}
