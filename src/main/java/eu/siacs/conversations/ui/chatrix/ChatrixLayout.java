package eu.siacs.conversations.ui.chatrix;

import android.view.View;

/** Chatrix.One: keep forms readable on tablets by limiting them to a centred column. */
public final class ChatrixLayout {

    private ChatrixLayout() {}

    /** Adds side padding so the content of {@code view} is at most {@code maxWidthDp} wide. */
    public static void limitWidth(final View view, final int maxWidthDp) {
        if (view == null) {
            return;
        }
        final int baseStart = view.getPaddingStart();
        final int baseEnd = view.getPaddingEnd();
        final int maxWidth = (int) (maxWidthDp * view.getResources().getDisplayMetrics().density);
        view.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    final int width = right - left;
                    final int extra = Math.max(0, (width - maxWidth) / 2);
                    final int start = baseStart + extra;
                    final int end = baseEnd + extra;
                    if (v.getPaddingStart() != start || v.getPaddingEnd() != end) {
                        v.post(
                                () ->
                                        v.setPaddingRelative(
                                                start, v.getPaddingTop(), end, v.getPaddingBottom()));
                    }
                });
    }
}
