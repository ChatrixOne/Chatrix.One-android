package im.conversations.android.xmpp.model.file;

import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

@XmlElement
public class Date extends Extension {
    public Date() {
        super(Date.class);
    }
}
