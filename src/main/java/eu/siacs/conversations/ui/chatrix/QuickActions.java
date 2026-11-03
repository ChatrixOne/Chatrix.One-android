package eu.siacs.conversations.ui.chatrix;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import com.google.android.material.color.MaterialColors;
import com.google.common.collect.ImmutableSet;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.Transferable;
import eu.siacs.conversations.utils.MessageUtils;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/** Chatrix.One quick gestures: which action a gesture triggers and whether a message allows it. */
public final class QuickActions {

    public enum Action {
        QUOTE,
        REACT,
        COPY,
        EDIT,
        NONE;

        static Action of(final String value) {
            if (value == null) {
                return NONE;
            }
            try {
                return Action.valueOf(value.toUpperCase(java.util.Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                return NONE;
            }
        }
    }

    public static final String PREF_SWIPE_LEFT = "chatrix_swipe_left";
    public static final String PREF_SWIPE_RIGHT = "chatrix_swipe_right";
    public static final String PREF_DOUBLE_TAP = "chatrix_double_tap";
    public static final String PREF_QUICK_EMOJI = "chatrix_quick_emoji";

    public static final String DEFAULT_EMOJI = "👍"; // 👍

    /** Emojis shown at the top of the long-press menu. */
    public static final List<String> MENU_EMOJIS =
            Arrays.asList(
                    "👍", // 👍
                    "👎", // 👎
                    "❤️", // ❤️
                    "😂", // 😂
                    "😮", // 😮
                    "😢", // 😢
                    "🙏" // 🙏
                    );

    private QuickActions() {}

    private static SharedPreferences prefs(final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static Action swipeLeft(final Context context) {
        return Action.of(prefs(context).getString(PREF_SWIPE_LEFT, "quote"));
    }

    public static Action swipeRight(final Context context) {
        return Action.of(prefs(context).getString(PREF_SWIPE_RIGHT, "react"));
    }

    public static Action doubleTap(final Context context) {
        return Action.of(prefs(context).getString(PREF_DOUBLE_TAP, "copy"));
    }

    public static String quickEmoji(final Context context) {
        final String emoji = prefs(context).getString(PREF_QUICK_EMOJI, DEFAULT_EMOJI);
        return emoji == null || emoji.isEmpty() ? DEFAULT_EMOJI : emoji;
    }

    public static void setQuickEmoji(final Context context, final String emoji) {
        if (emoji != null && !emoji.isEmpty()) {
            prefs(context).edit().putString(PREF_QUICK_EMOJI, emoji).apply();
        }
    }

    /** Same pre-conditions the context menu applies before showing any option. */
    private static boolean baseConditions(final Message m) {
        if (m == null
                || m.getType() == Message.TYPE_STATUS
                || m.getType() == Message.TYPE_RTP_SESSION) {
            return false;
        }
        if (m.getEncryption() == Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE
                || m.getEncryption() == Message.ENCRYPTION_AXOLOTL_FAILED) {
            return false;
        }
        final Transferable t = m.getTransferable();
        return !(m.getStatus() == Message.STATUS_RECEIVED
                && t != null
                && (t.getStatus() == Transferable.STATUS_CANCELLED
                        || t.getStatus() == Transferable.STATUS_FAILED));
    }

    public static boolean canCopy(final Message m) {
        if (!baseConditions(m)) {
            return false;
        }
        final boolean encrypted =
                m.getEncryption() == Message.ENCRYPTION_DECRYPTION_FAILED
                        || m.getEncryption() == Message.ENCRYPTION_PGP;
        return !m.isFileOrImage()
                && !encrypted
                && !m.isGeoUri()
                && !m.treatAsDownloadable()
                && !MessageUtils.unInitiatedButKnownSize(m)
                && m.getTransferable() == null;
    }

    public static boolean canQuote(final Message m) {
        if (!canCopy(m)) {
            return false;
        }
        final boolean showError =
                m.getStatus() == Message.STATUS_SEND_FAILED
                        && m.getErrorMessage() != null
                        && !Message.ERROR_MESSAGE_CANCELLED.equals(m.getErrorMessage());
        return !showError && !MessageUtils.prepareQuote(m).isEmpty();
    }

    public static boolean canReact(final Message m) {
        if (!baseConditions(m)) {
            return false;
        }
        if (!(m.getConversation() instanceof Conversation c)) {
            return false;
        }
        final boolean singleOrOccupantId =
                c.getMode() == Conversational.MODE_SINGLE
                        || (c.getMucOptions().occupantId() && c.getMucOptions().participating());
        return m.getStatus() != Message.STATUS_SEND_FAILED && !m.isDeleted() && singleOrOccupantId;
    }

    /** Same conditions as "Correct message" in the context menu: our own, correctable message. */
    public static boolean canEdit(final Message m) {
        if (!baseConditions(m)) {
            return false;
        }
        if (!(m.getConversation() instanceof Conversation c)) {
            return false;
        }
        final boolean showError =
                m.getStatus() == Message.STATUS_SEND_FAILED
                        && m.getErrorMessage() != null
                        && !Message.ERROR_MESSAGE_CANCELLED.equals(m.getErrorMessage());
        final boolean singleOrOccupantId =
                c.getMode() == Conversational.MODE_SINGLE
                        || (c.getMucOptions().occupantId() && c.getMucOptions().participating());
        return !showError
                && m.getStatus() != Message.STATUS_RECEIVED
                && m.acceptMessageCorrection()
                && singleOrOccupantId;
    }

    public static boolean isAllowed(final Action action, final Message m) {
        return switch (action) {
            case QUOTE -> canQuote(m);
            case REACT -> canReact(m);
            case COPY -> canCopy(m);
            case EDIT -> canEdit(m);
            case NONE -> false;
        };
    }

    /** Toggle: adds the emoji to our reactions, or removes it if it is already there. */
    public static Collection<String> toggle(final Message message, final String emoji) {
        final var ours = new LinkedHashSet<>(message.getAggregatedReactions().ourReactions);
        if (!ours.remove(emoji)) {
            ours.add(emoji);
        }
        return ImmutableSet.copyOf(ours);
    }

    @Nullable
    public static Drawable icon(final Context context, final Action action) {
        final Drawable drawable =
                switch (action) {
                    case QUOTE -> ContextCompat.getDrawable(context, R.drawable.ic_reply_24dp);
                    case COPY -> ContextCompat.getDrawable(context, R.drawable.ic_chatrix_copy_24dp);
                    case EDIT -> ContextCompat.getDrawable(context, R.drawable.ic_edit_24dp);
                    case REACT -> new EmojiDrawable(quickEmoji(context));
                    case NONE -> null;
                };
        if (drawable != null && action != Action.REACT) {
            drawable.mutate()
                    .setTint(
                            MaterialColors.getColor(
                                    context,
                                    androidx.appcompat.R.attr.colorPrimary,
                                    0xff00a87a));
        }
        return drawable;
    }

    /** Draws a single emoji, used as the reveal icon when swiping to react. */
    public static final class EmojiDrawable extends Drawable {
        private final String emoji;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public EmojiDrawable(final String emoji) {
            this.emoji = emoji;
            paint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            final Rect b = getBounds();
            paint.setTextSize(b.height() * 0.9f);
            final Paint.FontMetrics fm = paint.getFontMetrics();
            final float baseline = b.exactCenterY() - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(emoji, b.exactCenterX(), baseline, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
