package eu.siacs.conversations.ui.chatrix;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Assert;
import org.junit.Test;

/**
 * Chatrix.One: a swipe acts on the message the finger touched (ultrareview 2026-11-27: when older
 * history loaded during a swipe, the reaction went to the message that now sat at the old index).
 */
public class SwipeTargetTest {

    /** A message object; equal text does not make two of them the same message. */
    private record Msg(String text) {
        @Override
        public boolean equals(final Object o) {
            return o instanceof Msg m && m.text.equals(text);
        }

        @Override
        public int hashCode() {
            return text.hashCode();
        }
    }

    private static List<Object> chat(final int size) {
        final List<Object> list = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            list.add(new Msg("message " + i));
        }
        return list;
    }

    private static int find(final List<Object> list, final int hint, final Object item) {
        return SwipeTarget.find(list.size(), list::get, hint, item);
    }

    @Test
    public void anUnchangedListKeepsTheIndex() {
        final List<Object> list = chat(50);
        Assert.assertEquals(17, find(list, 17, list.get(17)));
    }

    @Test
    public void olderHistoryInFrontMovesTheTouchedMessage() {
        final List<Object> list = chat(50);
        final Object touched = list.get(17);
        list.addAll(0, chat(50)); // the next page of history arrived during the swipe
        Assert.assertNotSame("the old index holds another message now", touched, list.get(17));
        Assert.assertEquals(67, find(list, 17, touched));
    }

    @Test
    public void aRemovedPlaceholderMovesItBack() {
        final List<Object> list = chat(51); // index 0: the "load more" placeholder
        final Object touched = list.get(30);
        list.remove(0);
        Assert.assertEquals(29, find(list, 30, touched));
    }

    @Test
    public void aMessageThatLeftTheListIsNoTarget() {
        final List<Object> list = chat(50);
        final Object touched = list.remove(17); // deleted or retracted during the swipe
        Assert.assertEquals(SwipeTarget.NONE, find(list, 17, touched));
    }

    @Test
    public void anEqualCopyIsNotTheTouchedMessage() {
        final List<Object> list = chat(50);
        final Object touched = list.get(17);
        list.set(17, new Msg("message 17")); // same text, other message
        Assert.assertEquals(SwipeTarget.NONE, find(list, 17, touched));
    }

    @Test
    public void nothingTouchedNothingFound() {
        final List<Object> list = chat(5);
        Assert.assertEquals(SwipeTarget.NONE, find(list, 2, null));
        Assert.assertEquals(SwipeTarget.NONE, find(new ArrayList<>(), 0, list.get(0)));
        Assert.assertEquals("a hint out of range is only a hint", 4, find(list, 99, list.get(4)));
        Assert.assertEquals(3, find(list, -1, list.get(3)));
    }

    /**
     * Random changes during a swipe (pages in front, placeholder, removals, re-sorting): the result
     * is always the touched object itself, or NONE exactly when it is gone.
     */
    @Test
    public void randomChangesAlwaysFindTheTouchedMessage() {
        final Random random = new Random(11);
        for (int round = 0; round < 5_000; round++) {
            final List<Object> list = chat(1 + random.nextInt(300));
            final int downPosition = random.nextInt(list.size());
            final Object touched = list.get(downPosition);
            final int changes = random.nextInt(4);
            for (int c = 0; c < changes; c++) {
                switch (random.nextInt(4)) {
                    case 0 -> list.addAll(0, chat(random.nextInt(60)));
                    case 1 -> {
                        if (!list.isEmpty()) {
                            list.remove(random.nextInt(list.size()));
                        }
                    }
                    case 2 -> java.util.Collections.shuffle(list, random);
                    default -> list.add(new Msg("new " + c));
                }
            }
            final int found = find(list, downPosition, touched);
            final int reference = indexOfIdentity(list, touched);
            if (reference < 0) {
                Assert.assertEquals(SwipeTarget.NONE, found);
            } else {
                Assert.assertSame(touched, list.get(found));
            }
        }
    }

    private static int indexOfIdentity(final List<Object> list, final Object item) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == item) {
                return i;
            }
        }
        return -1;
    }

    /** MessageListView keeps the touched message and acts on where it is now. */
    @Test
    public void messageListViewActsOnTheTouchedMessage() throws IOException {
        final File file =
                new File(
                        System.getProperty("user.dir"),
                        "src/main/java/eu/siacs/conversations/ui/widget/MessageListView.java");
        Assert.assertTrue("source not found at " + file, file.isFile());
        final String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Assert.assertTrue(source.contains("downItem =\n"));
        Assert.assertTrue(source.contains("getItemAtPosition(downPosition)"));
        final int up = source.indexOf("case MotionEvent.ACTION_UP -> {");
        final int cancel = source.indexOf("case MotionEvent.ACTION_CANCEL -> {", up);
        Assert.assertTrue(up >= 0 && cancel > up);
        final String onUp = source.substring(up, cancel);
        Assert.assertTrue(onUp.contains("final int position = touchedPosition();"));
        Assert.assertFalse("not the old index", onUp.contains("= downPosition;"));
        final int start = source.indexOf("private void startSwipe(");
        Assert.assertTrue(
                "the swipe icon is asked for the touched message too",
                source.indexOf("final int position = touchedPosition();", start) > start
                        && source.indexOf("getSwipeIcon(position, direction)", start) > start);
        Assert.assertTrue(source.contains("SwipeTarget.find("));
        Assert.assertEquals("same as AdapterView.INVALID_POSITION", -1, SwipeTarget.NONE);
    }
}
