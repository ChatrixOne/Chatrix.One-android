package im.conversations.android.xmpp.model.sfs;

import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

@XmlElement
public class FileSharing extends Extension {

    public FileSharing() {
        super(FileSharing.class);
    }

    public void setDisposition(final Disposition disposition) {
        this.setAttribute("disposition", disposition);
    }

    public enum Disposition {
        ATTACHMENT,
        INLINE
    }
}
