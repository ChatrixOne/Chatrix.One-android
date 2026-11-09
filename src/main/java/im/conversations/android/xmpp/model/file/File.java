package im.conversations.android.xmpp.model.file;

import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

@XmlElement
public class File extends Extension {
    public File() {
        super(File.class);
    }

    public File setFileName(final String name) {
        this.addExtension(new Name(name));
        return this;
    }

    public File setFileSize(final Long size) {
        this.addExtension(new Size(size));
        return this;
    }

    public File setMediaType(final String mediaType) {
        if (mediaType != null) {
            this.addExtension(new MediaType(mediaType));
        }
        return this;
    }

    public void setDimensions(int width, int height) {
        this.addExtension(new Width(width));
        this.addExtension(new Height(height));
    }
}
