package eu.siacs.conversations.ui.chatrix;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.color.MaterialColors;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Message;
import java.util.Set;
import java.util.function.Consumer;

/** Chatrix.One: row of quick reaction emojis shown as header of the message context menu. */
public final class ReactionHeader {

    private ReactionHeader() {}

    public static View create(
            final Context context,
            final Message message,
            final Consumer<String> onEmoji,
            final Runnable onMore) {
        final float density = context.getResources().getDisplayMetrics().density;
        final int accent =
                MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0xff00a87a);
        final Set<String> ours = message.getAggregatedReactions().ourReactions;

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        final int padH = (int) (12 * density);
        final int padV = (int) (14 * density);
        row.setPadding(padH, padV, padH, (int) (6 * density));

        for (final String emoji : QuickActions.MENU_EMOJIS) {
            final TextView tv = new TextView(context);
            tv.setText(emoji);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            tv.setGravity(Gravity.CENTER);
            tv.setContentDescription(emoji);
            tv.setBackground(circle(accent, ours.contains(emoji)));
            tv.setOnClickListener(
                    v -> {
                        v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                        onEmoji.accept(emoji);
                    });
            row.addView(tv, cell(density));
        }

        final ImageView more = new ImageView(context);
        more.setImageResource(R.drawable.ic_add_reaction_24dp);
        more.setImageTintList(ColorStateList.valueOf(accent));
        more.setScaleType(ImageView.ScaleType.CENTER);
        more.setContentDescription(context.getString(R.string.add_reaction));
        more.setBackground(circle(accent, false));
        more.setOnClickListener(v -> onMore.run());
        row.addView(more, cell(density));
        return row;
    }

    private static LinearLayout.LayoutParams cell(final float density) {
        final int size = (int) (40 * density);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, size, 1f);
        lp.setMargins((int) (1 * density), 0, (int) (1 * density), 0);
        return lp;
    }

    private static RippleDrawable circle(final int accent, final boolean selected) {
        final GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        // accent-soft from the website: accent at 14% opacity
        shape.setColor(selected ? (accent & 0x00ffffff) | 0x30000000 : 0x00000000);
        final GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xff000000);
        return new RippleDrawable(
                ColorStateList.valueOf((accent & 0x00ffffff) | 0x40000000), shape, mask);
    }
}
