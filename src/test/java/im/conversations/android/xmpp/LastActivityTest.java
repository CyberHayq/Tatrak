package im.conversations.android.xmpp;

import im.conversations.android.xml.XmlElementReader;
import im.conversations.android.xmpp.model.last.Query;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class LastActivityTest {

    @Test
    public void readsSeconds() throws IOException {
        final var query =
                XmlElementReader.read(
                        "<query xmlns='jabber:iq:last' seconds='903'>Heading Home</query>",
                        Query.class);
        Assert.assertEquals(Long.valueOf(903), query.getSeconds());
    }

    @Test
    public void rejectsMissingOrBadSeconds() throws IOException {
        Assert.assertNull(
                XmlElementReader.read("<query xmlns='jabber:iq:last'/>", Query.class).getSeconds());
        Assert.assertNull(
                XmlElementReader.read("<query xmlns='jabber:iq:last' seconds='-5'/>", Query.class)
                        .getSeconds());
        Assert.assertNull(
                XmlElementReader.read("<query xmlns='jabber:iq:last' seconds='x'/>", Query.class)
                        .getSeconds());
    }
}
