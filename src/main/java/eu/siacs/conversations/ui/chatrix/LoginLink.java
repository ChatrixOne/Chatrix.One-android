package eu.siacs.conversations.ui.chatrix;

import androidx.annotation.Nullable;
import java.util.Locale;

/**
 * Chatrix.One: chatrixone://login?jid=name@chatrix.one, opened by the sign-up page on
 * chatrix.one. Any web page can open such a link, so only chatrix.one addresses are accepted: a
 * look-alike domain must never be filled in with the cursor already in the password field.
 */
public final class LoginLink {

    public static final String DOMAIN = "chatrix.one";

    private LoginLink() {}

    /** The address to fill in, or null if the link carries anything but a chatrix.one address. */
    @Nullable
    public static String jid(@Nullable final String parameter) {
        if (parameter == null || parameter.length() > 256) {
            return null;
        }
        final int at = parameter.indexOf('@');
        if (at < 1 || at != parameter.lastIndexOf('@')) {
            return null;
        }
        final String local = parameter.substring(0, at);
        final String domain = parameter.substring(at + 1);
        // exact ASCII compare: "chatrix.оne" with a Cyrillic о is a different domain
        if (!domain.toLowerCase(Locale.ROOT).equals(DOMAIN) || !local.matches("[^\\s@/]+")) {
            return null;
        }
        return local + '@' + DOMAIN;
    }
}
