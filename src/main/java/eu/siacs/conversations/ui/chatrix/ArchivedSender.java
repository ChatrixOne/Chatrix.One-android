package eu.siacs.conversations.ui.chatrix;

import androidx.annotation.Nullable;
import im.conversations.android.xmpp.model.muc.user.Item;
import im.conversations.android.xmpp.model.muc.user.MucUser;
import im.conversations.android.xmpp.model.stanza.Message;

/**
 * Chatrix.One (security review S7-2): which sender details of an archived group message (MAM:
 * history and the catch-up after being offline) can be trusted.
 *
 * <p>A member can put their own {@code <x xmlns='http://jabber.org/protocol/muc#user'><item
 * jid='...'/></x>} into a group message, naming any real address, also ours. ejabberd removes it
 * before archiving and adds the real one. Prosody keeps it, and in non-anonymous rooms appends the
 * real one after it, so a forged message carries two items (in anonymous rooms Prosody removes all
 * of them when serving the archive). Upstream trusts the first one. We trust an item only if the
 * message carries exactly one, in all its muc#user elements together; otherwise the sender is
 * looked up by occupant id as for a message without the element.
 */
public final class ArchivedSender {

    private ArchivedSender() {}

    /** The only muc#user item of the message, or null if it has none or more than one. */
    @Nullable
    public static Item onlyItem(final Message message) {
        Item only = null;
        for (final MucUser mucUser : message.getExtensions(MucUser.class)) {
            for (final Item item : mucUser.getExtensions(Item.class)) {
                if (only != null) {
                    return null;
                }
                only = item;
            }
        }
        return only;
    }
}
