package im.conversations.android.xmpp.model.file;

import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

@XmlElement
public class Width extends Extension {

    public Width() {
        super(Width.class);
    }

    public Width(final int width) {
        this();
        this.setContent(Integer.toString(width));
    }
}
