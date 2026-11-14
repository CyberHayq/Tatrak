package im.conversations.android.xmpp.model.reply;

import com.google.common.base.Strings;
import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

/** XEP-0461: the message this one answers. */
@XmlElement
public class Reply extends Extension {

    public Reply() {
        super(Reply.class);
    }

    public Reply(final String to, final String id) {
        this();
        if (to != null) {
            this.setAttribute("to", to);
        }
        this.setAttribute("id", id);
    }

    public String getId() {
        return Strings.emptyToNull(this.getAttribute("id"));
    }

    public String getTo() {
        return Strings.emptyToNull(this.getAttribute("to"));
    }
}
