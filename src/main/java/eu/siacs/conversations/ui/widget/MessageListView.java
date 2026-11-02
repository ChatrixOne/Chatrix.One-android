package eu.siacs.conversations.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ListView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Chatrix.One: ListView for the chat transcript that adds quick gestures on message rows.
 *
 * <ul>
 *   <li>horizontal swipe left / right on a row (an icon is revealed, haptic tick at threshold)
 *   <li>double-tap on a row
 *   <li>long-press always opens the context menu as a dialog (so it can carry an emoji header)
 * </ul>
 *
 * <p>What the gestures actually do is decided by the {@link GestureListener}.
 */
public class MessageListView extends ListView {

    public enum Direction {
        LEFT,
        RIGHT
    }

    public interface GestureListener {
        /** Return the icon to reveal for this swipe, or null if the row can not be swiped. */
        @Nullable
        Drawable getSwipeIcon(int position, Direction direction);

        void onSwipe(int position, Direction direction);

        /** Return true if the double tap was consumed. */
        boolean onDoubleTap(int position);
    }

    private static final float THRESHOLD_DP = 64f;
    private static final float MAX_TRANSLATION_DP = 96f;
    private static final float EDGE_EXCLUSION_DP = 24f;
    private static final float ICON_SIZE_DP = 28f;
    private static final float ICON_MARGIN_DP = 20f;

    private final int touchSlop;
    private final float density;
    private final GestureDetector gestureDetector;

    private GestureListener gestureListener;

    private float downX;
    private float downY;
    private int downPosition = INVALID_POSITION;
    private boolean candidate;
    private boolean swiping;
    private boolean thresholdReached;
    private Direction direction;
    private View swipedView;
    private Drawable swipeIcon;

    public MessageListView(Context context) {
        this(context, null);
    }

    public MessageListView(Context context, AttributeSet attrs) {
        this(context, attrs, android.R.attr.listViewStyle);
    }

    public MessageListView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        this.density = context.getResources().getDisplayMetrics().density;
        this.gestureDetector =
                new GestureDetector(
                        context,
                        new GestureDetector.SimpleOnGestureListener() {
                            @Override
                            public boolean onDoubleTap(@NonNull MotionEvent e) {
                                final var listener = gestureListener;
                                if (listener == null) {
                                    return false;
                                }
                                final int position = pointToPosition((int) e.getX(), (int) e.getY());
                                if (position == INVALID_POSITION) {
                                    return false;
                                }
                                if (listener.onDoubleTap(position)) {
                                    performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                                    return true;
                                }
                                return false;
                            }
                        });
        // we only use the detector for double taps; never let it delay anything
        this.gestureDetector.setIsLongpressEnabled(false);
    }

    public void setGestureListener(final GestureListener listener) {
        this.gestureListener = listener;
    }

    @Override
    public boolean showContextMenuForChild(View originalView, float x, float y) {
        // Without coordinates Android shows the classic context menu dialog, which (unlike the
        // anchored popup) displays our header with quick reactions.
        return super.showContextMenuForChild(originalView);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (gestureListener == null) {
            return super.dispatchTouchEvent(ev);
        }
        if (!swiping) {
            gestureDetector.onTouchEvent(ev);
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                resetSwipe(false);
                downX = ev.getX();
                downY = ev.getY();
                downPosition = pointToPosition((int) downX, (int) downY);
                final float edge = EDGE_EXCLUSION_DP * density;
                candidate =
                        downPosition != INVALID_POSITION
                                && downX > edge
                                && downX < getWidth() - edge;
            }
            case MotionEvent.ACTION_MOVE -> {
                final float dx = ev.getX() - downX;
                final float dy = ev.getY() - downY;
                if (!swiping && candidate) {
                    if (Math.abs(dy) > touchSlop && Math.abs(dy) >= Math.abs(dx)) {
                        candidate = false; // vertical scroll, leave it alone
                    } else if (Math.abs(dx) > touchSlop * 1.5f
                            && Math.abs(dx) > Math.abs(dy) * 2f) {
                        startSwipe(dx > 0 ? Direction.RIGHT : Direction.LEFT, ev);
                    }
                }
                if (swiping) {
                    updateSwipe(dx);
                    return true;
                }
            }
            case MotionEvent.ACTION_UP -> {
                if (swiping) {
                    final boolean trigger = thresholdReached;
                    final Direction dir = direction;
                    final int position = downPosition;
                    resetSwipe(true);
                    if (trigger) {
                        gestureListener.onSwipe(position, dir);
                    }
                    return true;
                }
            }
            case MotionEvent.ACTION_CANCEL -> {
                if (swiping) {
                    resetSwipe(true);
                    return true;
                }
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private void startSwipe(final Direction direction, final MotionEvent ev) {
        final Drawable icon = gestureListener.getSwipeIcon(downPosition, direction);
        final View view = getChildAt(downPosition - getFirstVisiblePosition());
        if (icon == null || view == null) {
            candidate = false;
            return;
        }
        this.swiping = true;
        this.direction = direction;
        this.swipedView = view;
        this.swipeIcon = icon.mutate();
        this.thresholdReached = false;
        getParent().requestDisallowInterceptTouchEvent(true);
        // cancel pending long press, link clicks and scrolling in children / list
        final MotionEvent cancel = MotionEvent.obtain(ev);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
    }

    private void updateSwipe(final float dx) {
        float distance = direction == Direction.RIGHT ? Math.max(0, dx) : Math.min(0, dx);
        final float max = MAX_TRANSLATION_DP * density;
        // rubber band: linear until max, then resist
        if (Math.abs(distance) > max) {
            final float extra = Math.abs(distance) - max;
            distance = Math.signum(distance) * (max + extra * 0.15f);
        }
        swipedView.setTranslationX(distance);
        final boolean reached = Math.abs(distance) >= THRESHOLD_DP * density;
        if (reached != thresholdReached) {
            thresholdReached = reached;
            if (reached) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
        }
        invalidate();
    }

    private void resetSwipe(final boolean animate) {
        final View view = this.swipedView;
        if (view != null) {
            if (animate) {
                view.animate()
                        .translationX(0)
                        .setDuration(180)
                        .setUpdateListener(a -> invalidate())
                        .withEndAction(this::invalidate)
                        .start();
            } else {
                view.animate().cancel();
                view.setTranslationX(0);
            }
        }
        if (!animate) {
            swipeIcon = null;
        }
        swiping = false;
        thresholdReached = false;
        candidate = false;
        if (!animate) {
            swipedView = null;
        }
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        super.dispatchDraw(canvas);
        final View view = this.swipedView;
        final Drawable icon = this.swipeIcon;
        if (view == null || icon == null || view.getParent() != this) {
            return;
        }
        final float translation = view.getTranslationX();
        if (translation == 0) {
            return;
        }
        final float progress =
                Math.min(1f, Math.abs(translation) / (THRESHOLD_DP * density));
        final int size = (int) (ICON_SIZE_DP * density * (0.6f + 0.4f * progress));
        final int margin = (int) (ICON_MARGIN_DP * density);
        final int centerY = (view.getTop() + view.getBottom()) / 2;
        final int left;
        if (translation > 0) {
            left = margin;
        } else {
            left = getWidth() - margin - size;
        }
        icon.setBounds(left, centerY - size / 2, left + size, centerY + size / 2);
        icon.setAlpha((int) (255 * progress));
        icon.draw(canvas);
    }
}
