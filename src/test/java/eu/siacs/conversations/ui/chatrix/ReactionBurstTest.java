package eu.siacs.conversations.ui.chatrix;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import org.junit.Assert;
import org.junit.Test;

/**
 * Chatrix.One: after a catch-up, 20 reactions made 20 notification updates with 20 sounds
 * (ultrareview 2026-11-26). Now each chat posts once per burst and a burst makes one sound.
 */
public class ReactionBurstTest {

    /** A fake clock and timer that run the checks the way NotificationService schedules them. */
    private static final class Sim {
        long now = 1_000_000; // any origin
        final ReactionBurst burst = new ReactionBurst(() -> now);
        private final PriorityQueue<long[]> timer =
                new PriorityQueue<>(
                        (a, b) ->
                                a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        private final List<String> chats = new ArrayList<>();
        private long seq;
        final List<Object[]> posts = new ArrayList<>(); // time, chat, sound
        final Map<String, Integer> outstanding = new HashMap<>();
        int maxOutstanding;
        long checks;

        private void schedule(final String chat, final long delay) {
            int i = chats.indexOf(chat);
            if (i < 0) {
                chats.add(chat);
                i = chats.size() - 1;
            }
            timer.add(new long[] {now + delay, seq++, i});
            final int n = outstanding.merge(chat, 1, Integer::sum);
            maxOutstanding = Math.max(maxOutstanding, n);
        }

        void add(final String chat) {
            if (burst.add(chat)) {
                schedule(chat, ReactionBurst.QUIET_MS);
            }
        }

        /** Runs every check due up to the given time. */
        void runUntil(final long until) {
            while (!timer.isEmpty() && timer.peek()[0] <= until) {
                final long[] t = timer.poll();
                now = t[0];
                final String chat = chats.get((int) t[2]);
                outstanding.merge(chat, -1, Integer::sum);
                checks++;
                final ReactionBurst.Check check = burst.check(chat);
                if (check == null) {
                    continue;
                }
                if (check.postNow()) {
                    posts.add(new Object[] {now, chat, check.sound()});
                } else {
                    Assert.assertTrue("a check waits forward", check.waitMs() > 0);
                    schedule(chat, check.waitMs());
                }
            }
            now = Math.max(now, until);
        }

        void advance(final long ms) {
            runUntil(now + ms);
        }

        long sounds() {
            return posts.stream().filter(p -> (Boolean) p[2]).count();
        }
    }

    @Test
    public void aSingleReactionSoundsAfterTheQuietPause() {
        final Sim sim = new Sim();
        final long start = sim.now;
        sim.add("a");
        sim.advance(ReactionBurst.QUIET_MS - 1);
        Assert.assertTrue("not before the pause", sim.posts.isEmpty());
        sim.advance(1);
        Assert.assertEquals(1, sim.posts.size());
        Assert.assertEquals(start + ReactionBurst.QUIET_MS, sim.posts.get(0)[0]);
        Assert.assertEquals(true, sim.posts.get(0)[2]);
    }

    @Test
    public void aCatchUpOfTwentyReactionsMakesOneSound() {
        final Sim sim = new Sim();
        final String[] chats = {"a", "b", "c"};
        for (int i = 0; i < 20; i++) {
            sim.add(chats[i % 3]);
            sim.advance(30);
        }
        sim.advance(60_000);
        Assert.assertEquals("one post per chat", 3, sim.posts.size());
        Assert.assertEquals("one sound", 1, sim.sounds());
    }

    @Test
    public void quietForTheSoundGapSoundsAgain() {
        final Sim sim = new Sim();
        sim.add("a");
        sim.advance(ReactionBurst.SOUND_GAP_MS - 1);
        sim.add("b"); // part of the same burst: the gap counts from the last reaction
        sim.advance(ReactionBurst.SOUND_GAP_MS - 1);
        sim.add("a"); // still within 15 s of "b"
        sim.advance(ReactionBurst.SOUND_GAP_MS);
        sim.add("c"); // 15 s after the last one: a new burst
        sim.advance(ReactionBurst.SOUND_GAP_MS);
        Assert.assertEquals(4, sim.posts.size());
        Assert.assertEquals(
                List.of(true, false, false, true), sim.posts.stream().map(p -> p[2]).toList());
    }

    @Test
    public void aSteadyStreamStillPostsWithinTheMaximumWait() {
        final Sim sim = new Sim();
        final long start = sim.now;
        for (int i = 0; i < 40; i++) {
            sim.add("a");
            sim.advance(ReactionBurst.QUIET_MS / 2);
        }
        Assert.assertFalse(sim.posts.isEmpty());
        Assert.assertEquals(start + ReactionBurst.MAX_WAIT_MS, sim.posts.get(0)[0]);
    }

    @Test
    public void manyChatsAreSpacedOut() {
        final Sim sim = new Sim();
        for (int i = 0; i < 10; i++) {
            sim.add("chat" + i);
        }
        sim.advance(60_000);
        Assert.assertEquals(10, sim.posts.size());
        for (int i = 1; i < sim.posts.size(); i++) {
            Assert.assertEquals(
                    ReactionBurst.SPACING_MS,
                    (long) sim.posts.get(i)[0] - (long) sim.posts.get(i - 1)[0]);
        }
        Assert.assertEquals(1, sim.sounds());
    }

    @Test
    public void aReadChatPostsNothing() {
        final Sim sim = new Sim();
        sim.add("a");
        sim.advance(200);
        sim.burst.forget("a"); // the user opened the chat
        sim.advance(60_000);
        Assert.assertTrue(sim.posts.isEmpty());
        sim.add("a"); // a later reaction notifies again
        sim.advance(60_000);
        Assert.assertEquals(1, sim.posts.size());
    }

    /**
     * Random reactions in random chats: no reaction is lost, posts keep their spacing, two sounds
     * are never closer than the gap, and each chat has one check waiting at most.
     */
    @Test
    public void randomBurstsKeepEveryRule() {
        final Random random = new Random(7);
        for (int round = 0; round < 300; round++) {
            final Sim sim = new Sim();
            final Map<String, Long> lastAdd = new HashMap<>();
            long previousAdd = Long.MIN_VALUE;
            int burstStarts = 0;
            final int adds = 1 + random.nextInt(200);
            final int chatCount = 1 + random.nextInt(8);
            for (int i = 0; i < adds; i++) {
                final String chat = "c" + random.nextInt(chatCount);
                sim.add(chat);
                if (previousAdd == Long.MIN_VALUE
                        || sim.now - previousAdd >= ReactionBurst.SOUND_GAP_MS) {
                    burstStarts++;
                }
                previousAdd = sim.now;
                lastAdd.put(chat, sim.now);
                final int kind = random.nextInt(10);
                final long gap =
                        kind < 6
                                ? random.nextInt(300)
                                : kind < 9 ? random.nextInt(3_000) : random.nextInt(40_000);
                sim.advance(gap);
            }
            sim.advance(10 * 60_000);
            Assert.assertEquals("one check per chat", 1, sim.maxOutstanding);
            Assert.assertTrue("bounded checks", sim.checks <= 4L * adds);
            final Map<String, Long> lastPost = new HashMap<>();
            long previousPost = Long.MIN_VALUE;
            long previousSound = Long.MIN_VALUE;
            for (final Object[] p : sim.posts) {
                final long t = (long) p[0];
                if (previousPost != Long.MIN_VALUE) {
                    Assert.assertTrue("spacing", t - previousPost >= ReactionBurst.SPACING_MS);
                }
                previousPost = t;
                final Long chatPost = lastPost.put((String) p[1], t);
                if (chatPost != null) {
                    Assert.assertTrue("per chat", t - chatPost >= ReactionBurst.QUIET_MS);
                }
                if ((Boolean) p[2]) {
                    if (previousSound != Long.MIN_VALUE) {
                        Assert.assertTrue(
                                "sound gap", t - previousSound >= ReactionBurst.SOUND_GAP_MS);
                    }
                    previousSound = t;
                }
            }
            // the sound may come with another chat's post of the same burst, not the first one
            Assert.assertTrue("a burst sounds", sim.sounds() >= 1);
            Assert.assertTrue("one sound per burst at most", sim.sounds() <= burstStarts);
            for (final Map.Entry<String, Long> e : lastAdd.entrySet()) {
                final Long posted = lastPost.get(e.getKey());
                Assert.assertTrue(
                        "lost reaction in " + e.getKey(),
                        posted != null && posted >= e.getValue());
            }
        }
    }

    /** NotificationService posts through the burst and drops it when the chat is read. */
    @Test
    public void notificationServiceUsesTheBurst() throws IOException {
        final File file =
                new File(
                        System.getProperty("user.dir"),
                        "src/main/java/eu/siacs/conversations/services/NotificationService.java");
        Assert.assertTrue("source not found at " + file, file.isFile());
        final String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        final String push = slice(source, "public void pushReaction(");
        Assert.assertTrue(push.contains("if (reactionBurst.add(conversation.getUuid())) {"));
        Assert.assertFalse("no direct post", push.contains("notify("));
        final String post =
                slice(source, "private void postReactions(final Conversation conversation) {");
        Assert.assertTrue(post.contains("reactionBurst.check(conversation.getUuid())"));
        Assert.assertTrue(
                post.contains(
                        "ReactionBurst.later(() -> postReactions(conversation), check.waitMs())"));
        Assert.assertTrue(post.contains("postReactions(conversation, check.sound())"));
        final String posting =
                slice(
                        source,
                        "private void postReactions(final Conversation conversation,"
                                + " final boolean burstSound) {");
        Assert.assertTrue("the burst decides the sound", posting.contains("burstSound\n"));
        Assert.assertTrue(
                slice(source, "public void clearReactions(")
                        .contains("reactionBurst.forget(conversation.getUuid());"));
    }

    private static String slice(final String source, final String start) {
        final int from = source.indexOf(start);
        Assert.assertTrue(start + " not found", from >= 0);
        final int end = source.indexOf("\n    }\n", from);
        Assert.assertTrue(end > from);
        return source.substring(from, end);
    }
}
