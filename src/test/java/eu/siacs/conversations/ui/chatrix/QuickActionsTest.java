package eu.siacs.conversations.ui.chatrix;

import android.content.Context;
import androidx.preference.PreferenceManager;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class QuickActionsTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
    }

    @Test
    public void parsesActionNames() {
        Assert.assertEquals(QuickActions.Action.QUOTE, QuickActions.Action.of("quote"));
        Assert.assertEquals(QuickActions.Action.EDIT, QuickActions.Action.of("EDIT"));
        Assert.assertEquals(QuickActions.Action.NONE, QuickActions.Action.of("unknown"));
        Assert.assertEquals(QuickActions.Action.NONE, QuickActions.Action.of(null));
    }

    @Test
    public void defaultGestures() {
        Assert.assertEquals(QuickActions.Action.QUOTE, QuickActions.swipeLeft(context));
        Assert.assertEquals(QuickActions.Action.REACT, QuickActions.swipeRight(context));
        Assert.assertEquals(QuickActions.Action.COPY, QuickActions.doubleTap(context));
        Assert.assertEquals(QuickActions.DEFAULT_EMOJI, QuickActions.quickEmoji(context));
    }

    @Test
    public void configuredGestures() {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(QuickActions.PREF_SWIPE_LEFT, "edit")
                .putString(QuickActions.PREF_SWIPE_RIGHT, "none")
                .commit();
        Assert.assertEquals(QuickActions.Action.EDIT, QuickActions.swipeLeft(context));
        Assert.assertEquals(QuickActions.Action.NONE, QuickActions.swipeRight(context));
    }

    @Test
    public void quickEmojiCanBeAnyEmoji() {
        QuickActions.setQuickEmoji(context, "🦄");
        Assert.assertEquals("🦄", QuickActions.quickEmoji(context));
        QuickActions.setQuickEmoji(context, "");
        Assert.assertEquals("🦄", QuickActions.quickEmoji(context));
    }

    @Test
    public void nothingIsAllowedForMissingMessage() {
        for (final QuickActions.Action action : QuickActions.Action.values()) {
            Assert.assertFalse(QuickActions.isAllowed(action, null));
        }
    }
}
