package eu.siacs.conversations.ui.chatrix;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Chatrix.One: clean-up of per-chat background pictures whose chat is gone. */
public class ChatBackgroundFilesTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long OLD = NOW - ChatBackgroundFiles.MIN_AGE_MS - 1;

    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void deletesOnlyPicturesOfChatsThatAreGone() throws IOException {
        final File kept = picture("kept", OLD);
        final File gone = picture("gone", OLD);
        final File young = picture("new", NOW - 1000);
        final File defaultPicture = file("chatrix_chat_background.jpg", OLD); // from settings
        final File other = file("chatrix_chat_background_.jpg", OLD); // no uuid
        final File unrelated = file("avatar_gone.jpg", OLD);
        final int deleted =
                ChatBackgroundFiles.sweep(folder.getRoot(), Set.of("kept")::contains, NOW);
        Assert.assertEquals(1, deleted);
        Assert.assertFalse(gone.exists());
        for (final File file : new File[] {kept, young, defaultPicture, other, unrelated}) {
            Assert.assertTrue(file.getName(), file.exists());
        }
    }

    @Test
    public void databaseTroubleDeletesNothing() throws IOException {
        final File picture = picture("unknown", OLD);
        try {
            ChatBackgroundFiles.sweep(
                    folder.getRoot(),
                    uuid -> {
                        throw new IllegalStateException("database is locked");
                    },
                    NOW);
            Assert.fail("the error must reach the caller");
        } catch (final IllegalStateException expected) {
            Assert.assertTrue(picture.exists());
        }
    }

    @Test
    public void missingFolderIsNoError() {
        Assert.assertEquals(
                0, ChatBackgroundFiles.sweep(new File(folder.getRoot(), "none"), uuid -> false, NOW));
    }

    @Test
    public void namesAndUuidsMatch() {
        final Random random = new Random(42);
        for (int i = 0; i < 10_000; ++i) {
            final String uuid = new UUID(random.nextLong(), random.nextLong()).toString();
            Assert.assertEquals(uuid, ChatBackgroundFiles.uuidOf(ChatBackgroundFiles.name(uuid)));
            final String noise = randomName(random);
            final String parsed = ChatBackgroundFiles.uuidOf(noise);
            if (parsed != null) {
                Assert.assertEquals(noise, ChatBackgroundFiles.name(parsed));
            }
        }
        Assert.assertNull(ChatBackgroundFiles.uuidOf(null));
        Assert.assertNull(ChatBackgroundFiles.uuidOf("chatrix_chat_background.jpg"));
        Assert.assertNull(ChatBackgroundFiles.uuidOf("chatrix_chat_background_x.png"));
    }

    private static String randomName(final Random random) {
        final String[] parts = {"chatrix_chat_background", "_", ".jpg", "x", "", ".", "-1"};
        final StringBuilder name = new StringBuilder();
        for (int i = random.nextInt(5); i >= 0; --i) {
            name.append(parts[random.nextInt(parts.length)]);
        }
        return name.toString();
    }

    private File picture(final String uuid, final long modified) throws IOException {
        return file(ChatBackgroundFiles.name(uuid), modified);
    }

    private File file(final String name, final long modified) throws IOException {
        final File file = folder.newFile(name);
        Files.write(file.toPath(), new byte[] {1, 2, 3});
        Assert.assertTrue(file.setLastModified(modified));
        return file;
    }
}
