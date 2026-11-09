package im.conversations.android.xmpp.model.file;

import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

@XmlElement
public class Size extends Extension {

    public Size() {
        super(Size.class);
    }

    public Size(final Long size) {
        this();
        this.setContent(Long.toString(size));
    }
}
