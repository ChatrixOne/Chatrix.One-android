package im.conversations.android.xmpp.model.file;

import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

@XmlElement
public class MediaType extends Extension {
    public MediaType() {
        super(MediaType.class);
    }

    public MediaType(final String mediaType) {
        this();
        this.setContent(mediaType);
    }
}
