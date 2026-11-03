package eu.siacs.conversations.ui;

import android.os.Bundle;
import android.widget.Toast;
import androidx.databinding.DataBindingUtil;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityAddReactionBinding;
import eu.siacs.conversations.ui.chatrix.QuickActions;

public class AddReactionActivity extends XmppActivity {

    /** Chatrix.One: open the full picker to choose the quick (swipe) reaction emoji. */
    public static final String EXTRA_PICK_QUICK_EMOJI = "chatrix_pick_quick_emoji";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final ActivityAddReactionBinding binding =
                DataBindingUtil.setContentView(this, R.layout.activity_add_reaction);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());

        setSupportActionBar(binding.toolbar);
        binding.toolbar.setNavigationIcon(R.drawable.ic_clear_24dp);
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        final boolean pickQuickEmoji =
                getIntent() != null
                        && getIntent().getBooleanExtra(EXTRA_PICK_QUICK_EMOJI, false);
        if (pickQuickEmoji) {
            setTitle(R.string.chatrix_pref_quick_emoji);
            binding.emojiPicker.setOnEmojiPickedListener(
                    emojiViewItem -> {
                        QuickActions.setQuickEmoji(this, emojiViewItem.getEmoji());
                        finish();
                    });
            return;
        }
        setTitle(R.string.add_reaction_title);
        binding.emojiPicker.setOnEmojiPickedListener(
                emojiViewItem -> addReaction(emojiViewItem.getEmoji()));
    }

    private void addReaction(final String emoji) {
        final var intent = getIntent();
        final var conversation = intent == null ? null : intent.getStringExtra("conversation");
        final var message = intent == null ? null : intent.getStringExtra("message");
        if (Strings.isNullOrEmpty(conversation) || Strings.isNullOrEmpty(message)) {
            Toast.makeText(this, R.string.could_not_add_reaction, Toast.LENGTH_LONG).show();
            return;
        }
        final var c = xmppConnectionService.findConversationByUuid(conversation);
        final var m = c == null ? null : c.findMessageWithUuid(message);
        if (m == null) {
            Toast.makeText(this, R.string.could_not_add_reaction, Toast.LENGTH_LONG).show();
            return;
        }
        final var aggregated = m.getAggregatedReactions();
        if (aggregated.ourReactions.contains(emoji)) {
            this.sendReactions(m, aggregated.ourReactions);
        } else {
            final ImmutableSet.Builder<String> reactionBuilder = new ImmutableSet.Builder<>();
            reactionBuilder.addAll(aggregated.ourReactions);
            reactionBuilder.add(emoji);
            this.sendReactions(m, reactionBuilder.build());
        }
        finish();
    }

    @Override
    protected void refreshUiReal() {}

    @Override
    protected void onBackendConnected() {}
}
