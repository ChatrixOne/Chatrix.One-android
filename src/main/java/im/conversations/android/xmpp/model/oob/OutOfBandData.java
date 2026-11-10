package im.conversations.android.xmpp.model.oob;

import com.google.common.base.Strings;
import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

@XmlElement(name = "x")
public class OutOfBandData extends Extension {

    public OutOfBandData() {
        super(OutOfBandData.class);
    }

    public OutOfBandData(final String url) {
        this();
        this.setURL(url);
    }

    public String getURL() {
        final URL url = this.getExtension(URL.class);
        return url == null ? null : Strings.emptyToNull(url.getContent());
    }

    public void setURL(final String url) {
        this.addExtension(new URL(url));
    }
}
