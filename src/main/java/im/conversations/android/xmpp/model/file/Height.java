package im.conversations.android.xmpp.model.file;

import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

@XmlElement
public class Height extends Extension {

    public Height() {
        super(Height.class);
    }

    public Height(final int height) {
        this();
        this.setContent(Integer.toString(height));
    }
}
