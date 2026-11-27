package eu.siacs.conversations.ui.chatrix;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.ConscryptMode;

/**
 * Chatrix.One: after a process restart the reaction notifications are still in the tray while the
 * in-memory list is empty (ultrareview 2026-11-26). Opening a chat must clear its notification, and
 * the summary, which takes the whole group with it, may only go when no other chat's reactions
 * show.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ReactionTrayTest {

    private static final String GROUP = "one.chatrix.reactions";
    private static final int ID = 16; // the reaction notification id, tag = chat uuid
    private static final int SUMMARY_ID = 17;

    private Context context;
    private NotificationManager manager;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        manager = context.getSystemService(NotificationManager.class);
    }

    private void show(final String tag, final int id, final String group, final boolean summary) {
        final Notification notification =
                new Notification.Builder(context, "test")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(tag)
                        .setGroup(group)
                        .setGroupSummary(summary)
                        .build();
        manager.notify(tag, id, notification);
    }

    @Test
    public void otherChatsKeepTheSummary() {
        show("chat-a", ID, GROUP, false);
        show("chat-b", ID, GROUP, false);
        show(null, SUMMARY_ID, GROUP, true);
        Assert.assertTrue(ReactionTray.showsOthers(context, GROUP, "chat-a", ID));
    }

    @Test
    public void theLastChatLetsTheSummaryGo() {
        show("chat-a", ID, GROUP, false); // cancelled just now, still listed
        show(null, SUMMARY_ID, GROUP, true);
        Assert.assertFalse(ReactionTray.showsOthers(context, GROUP, "chat-a", ID));
    }

    @Test
    public void anEmptyTrayShowsNothing() {
        Assert.assertFalse(ReactionTray.showsOthers(context, GROUP, "chat-a", ID));
    }

    @Test
    public void otherGroupsDoNotCount() {
        show("chat-a", ID, GROUP, false);
        show("chat-b", 1, "messages", false); // a message notification of another chat
        show(null, 2, "messages", true);
        Assert.assertFalse(ReactionTray.showsOthers(context, GROUP, "chat-a", ID));
    }

    @Test
    public void tagAndIdTogetherNameTheChat() {
        show("chat-b", ID, GROUP, false);
        Assert.assertTrue(
                "same id, other chat", ReactionTray.showsOthers(context, GROUP, "chat-a", ID));
        manager.cancelAll();
        show("chat-a", ID + 1, GROUP, false);
        Assert.assertTrue(
                "same chat, other id", ReactionTray.showsOthers(context, GROUP, "chat-a", ID));
    }

    /** clearReactions cancels the chat's notification always and asks the tray for the summary. */
    @Test
    public void clearReactionsAsksTheTray() throws IOException {
        final File file =
                new File(
                        System.getProperty("user.dir"),
                        "src/main/java/eu/siacs/conversations/services/NotificationService.java");
        Assert.assertTrue("source not found at " + file, file.isFile());
        final String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        final int start = source.indexOf("public void clearReactions(");
        final int end = source.indexOf("\n    }\n", start);
        Assert.assertTrue("clearReactions not found", start >= 0 && end > start);
        final String clear = source.substring(start, end);
        Assert.assertFalse("memory alone decides nothing", clear.contains("if (had)"));
        final int chat = clear.indexOf("cancel(conversation.getUuid(), REACTION_NOTIFICATION_ID);");
        final int tray = clear.indexOf("ReactionTray.showsOthers(");
        final int summary = clear.indexOf("cancel(REACTION_SUMMARY_NOTIFICATION_ID);");
        Assert.assertTrue("the chat is always cancelled", chat >= 0);
        Assert.assertTrue("the tray is asked", tray > chat);
        Assert.assertTrue("the summary only after asking", summary > tray);
        Assert.assertTrue(clear.contains("if (none\n"));
    }
}
