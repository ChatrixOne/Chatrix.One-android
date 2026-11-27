package eu.siacs.conversations.ui.chatrix;

import androidx.annotation.Nullable;
import java.util.function.IntFunction;

/**
 * Chatrix.One: a swipe acts on the message the finger touched, not on whatever sits at that list
 * index when the finger lifts (ultrareview 2026-11-27: older history loading during a swipe sent
 * the reaction to another message). Pure, so it is tested without Android.
 */
public final class SwipeTarget {

    /** Same value as AdapterView.INVALID_POSITION. */
    public static final int NONE = -1;

    private SwipeTarget() {}

    /**
     * Where {@code item} is now: {@code hint} if it is still there (the usual case), otherwise
     * the first index holding that very object (identity, not equals), or {@link #NONE}.
     */
    public static int find(
            final int count,
            final IntFunction<Object> itemAt,
            final int hint,
            @Nullable final Object item) {
        if (item == null || count <= 0) {
            return NONE;
        }
        if (hint >= 0 && hint < count && itemAt.apply(hint) == item) {
            return hint;
        }
        for (int i = 0; i < count; i++) {
            if (itemAt.apply(i) == item) {
                return i;
            }
        }
        return NONE;
    }
}
