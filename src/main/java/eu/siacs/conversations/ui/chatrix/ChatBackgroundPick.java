package eu.siacs.conversations.ui.chatrix;

import android.os.Bundle;
import androidx.annotation.Nullable;
import java.util.regex.Pattern;

/**
 * Chatrix.One: which chat a picture from the photo picker is for ("More options → Chat
 * background → Own picture").
 *
 * <p>Android often ends our app while the picker is open (little memory) or rebuilds the screen
 * (rotation), so a plain field is empty when the picture comes back and the picture was dropped
 * without a word (ultrareview 2026-11-26). The chat is therefore kept as its uuid in the
 * fragment's saved state. After the picture is saved, the service may still be connecting: the
 * uuid then waits until it is there.
 *
 * <p>Not saved across an app end: the short wait for the service. A picture saved for a chat
 * that never gets it is removed by {@link ChatBackground}'s clean-up of orphaned files.
 */
public final class ChatBackgroundPick {

    static final String STATE_KEY = "chatrix_background_pick";

    /** Conversation uuids are UUID.randomUUID(); anything else never becomes a file name. */
    private static final Pattern UUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    @Nullable private String picking;
    @Nullable private String waitingForService;

    /** The picker is about to open for this chat. */
    public void picking(final String conversationUuid) {
        this.picking = valid(conversationUuid) ? conversationUuid : null;
    }

    /** The picker answered: the chat it was for, once, or null. */
    @Nullable
    public String takePicked() {
        final String uuid = this.picking;
        this.picking = null;
        return uuid;
    }

    /** The picture is saved, but the service is not connected yet. */
    public void waitForService(final String conversationUuid) {
        this.waitingForService = valid(conversationUuid) ? conversationUuid : null;
    }

    /** The service is connected: the chat whose picture is waiting, once, or null. */
    @Nullable
    public String takeWaiting() {
        final String uuid = this.waitingForService;
        this.waitingForService = null;
        return uuid;
    }

    public void save(final Bundle outState) {
        if (picking != null) {
            outState.putString(STATE_KEY, picking);
        }
    }

    public void restore(final Bundle savedInstanceState) {
        final String uuid = savedInstanceState.getString(STATE_KEY);
        if (uuid != null) {
            picking(uuid);
        }
    }

    static boolean valid(@Nullable final String uuid) {
        return uuid != null && UUID.matcher(uuid).matches();
    }
}
