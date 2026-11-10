package im.conversations.android.xmpp.model.url;

import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

@XmlElement
public class UrlData extends Extension {
    public UrlData() {
        super(UrlData.class);
    }

    public UrlData(final String target) {
        this();
        this.setAttribute("target", target);
    }
}
