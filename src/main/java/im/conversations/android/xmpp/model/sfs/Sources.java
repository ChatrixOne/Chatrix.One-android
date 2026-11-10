package im.conversations.android.xmpp.model.sfs;

import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

@XmlElement
public class Sources extends Extension {
    public Sources() {
        super(Sources.class);
    }
}
