package eu.siacs.conversations.ui.chatrix;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;

/**
 * Chatrix.One: a chat's own picture is decoded off the main thread and kept in a small cache
 * (ultrareview 2026-11-26: every switch between two chats with their own pictures decoded a
 * full-screen JPEG on the main thread).
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real JPEG encoding and decoding
public class ChatBackgroundTest {

    private static final String CHAT_A = "0b1c2d3e-4f50-4617-8293-a4b5c6d7e8f9";
    private static final String CHAT_B = "1c2d3e4f-5061-4728-93a4-b5c6d7e8f9a0";
    private static final int WINDOW_BG = 0xff101820;
    private static final int ACCENT = 0xff2ecc71;

    private final List<Runnable> decodes = new ArrayList<>();
    private Context context;
    private Executor original;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        original = ChatBackground.decoder;
        ChatBackground.decoder = decodes::add; // decodes run when the test says so
        ChatBackground.forgetPictures();
    }

    @After
    public void tearDown() {
        ChatBackground.decoder = original;
        ChatBackground.forgetPictures();
    }

    private File picture(final String uuid) throws IOException {
        final File file = ChatBackground.file(context, uuid);
        final Bitmap bitmap = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888);
        try (OutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out);
        }
        return file;
    }

    /** Unit tests have no app resources: the colours come from here, not from R.color. */
    private static void apply(final View view, final String uuid, final String own) {
        ChatBackground.apply(view, uuid, own, WINDOW_BG, ACCENT);
    }

    /** Runs the waiting decodes, then what they posted to the main thread. */
    private void finishDecodes() {
        final List<Runnable> now = new ArrayList<>(decodes);
        decodes.clear();
        now.forEach(Runnable::run);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static String kind(final View view) {
        final Drawable background = view.getBackground();
        return background == null ? "none" : background.getClass().getSimpleName();
    }

    @Test
    public void aNewPictureShowsTheColourFirstThenThePicture() throws IOException {
        picture(CHAT_A);
        final View view = new View(context);
        apply(view, CHAT_A, ChatBackground.CUSTOM);
        Assert.assertTrue(
                "plain colour while decoding", view.getBackground() instanceof ColorDrawable);
        Assert.assertEquals(WINDOW_BG, ((ColorDrawable) view.getBackground()).getColor());
        Assert.assertEquals("decoded off the main thread", 1, decodes.size());
        finishDecodes();
        Assert.assertEquals("PictureDrawable", kind(view));
    }

    @Test
    public void aCachedPictureShowsAtOnce() throws IOException {
        picture(CHAT_A);
        final View view = new View(context);
        apply(view, CHAT_A, ChatBackground.CUSTOM);
        finishDecodes();
        apply(view, CHAT_B, ChatBackground.GLOW); // another chat
        apply(view, CHAT_A, ChatBackground.CUSTOM); // and back
        Assert.assertEquals("PictureDrawable", kind(view));
        Assert.assertTrue("no second decode", decodes.isEmpty());
    }

    @Test
    public void aNewPictureUnderTheSameNameIsDecodedAgain() throws IOException {
        final File file = picture(CHAT_A);
        final View view = new View(context);
        apply(view, CHAT_A, ChatBackground.CUSTOM);
        finishDecodes();
        picture(CHAT_A);
        Assert.assertTrue(file.setLastModified(file.lastModified() + 10_000));
        apply(view, CHAT_A, ChatBackground.CUSTOM);
        Assert.assertEquals(1, decodes.size());
        Assert.assertTrue(view.getBackground() instanceof ColorDrawable);
        finishDecodes();
        Assert.assertEquals("PictureDrawable", kind(view));
    }

    @Test
    public void aQuickSwitchKeepsTheNewChatsBackground() throws IOException {
        picture(CHAT_A);
        final View view = new View(context);
        apply(view, CHAT_A, ChatBackground.CUSTOM);
        apply(view, CHAT_B, ChatBackground.GLOW); // before A's picture is ready
        finishDecodes();
        Assert.assertEquals("GlowDrawable", kind(view));
    }

    @Test
    public void twoViewsDoNotDisturbEachOther() throws IOException {
        picture(CHAT_A);
        final View one = new View(context);
        final View two = new View(context);
        apply(one, CHAT_A, ChatBackground.CUSTOM);
        apply(two, CHAT_B, ChatBackground.PLAIN);
        finishDecodes();
        Assert.assertEquals("PictureDrawable", kind(one));
        Assert.assertEquals("none", kind(two));
    }

    @Test
    public void aMissingOrBrokenPictureFallsBackToTheGlow() throws IOException {
        final View view = new View(context);
        apply(view, CHAT_A, ChatBackground.CUSTOM); // no file
        Assert.assertEquals("GlowDrawable", kind(view));
        Assert.assertTrue(decodes.isEmpty());
        Files.write(ChatBackground.file(context, CHAT_B).toPath(), new byte[0]);
        apply(view, CHAT_B, ChatBackground.CUSTOM); // empty file
        finishDecodes();
        Assert.assertEquals("GlowDrawable", kind(view));
    }

    /** No picture is decoded on the main thread any more: the fragment only calls apply(). */
    @Test
    public void theChatScreenUsesApply() throws IOException {
        final String fragment =
                read("src/main/java/eu/siacs/conversations/ui/ConversationFragment.java");
        Assert.assertTrue(
                fragment.contains(
                        "eu.siacs.conversations.ui.chatrix.ChatBackground.apply(\n"
                                + "                binding.chatContainer, conversation);"));
        Assert.assertFalse(fragment.contains("ChatBackground.create("));
        final String background =
                read("src/main/java/eu/siacs/conversations/ui/chatrix/ChatBackground.java");
        Assert.assertFalse("no main-thread decode left", background.contains("Drawable create("));
        final String apply = slice(background, "    static void apply(");
        Assert.assertFalse("apply() decodes nothing itself", apply.contains("BitmapFactory"));
        Assert.assertTrue(apply.contains("decoder.execute("));
        final String decode = slice(background, "    private static Bitmap decode(");
        Assert.assertEquals(
                "files are decoded only in decode()",
                count(background, "BitmapFactory.decodeFile("),
                count(decode, "BitmapFactory.decodeFile("));
    }

    private static String slice(final String source, final String start) {
        final int from = source.indexOf(start);
        Assert.assertTrue(start + " not found", from >= 0);
        final int end = source.indexOf("\n    }\n", from);
        Assert.assertTrue(end > from);
        return source.substring(from, end);
    }

    private static int count(final String text, final String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
            n++;
        }
        return n;
    }

    private static String read(final String path) throws IOException {
        final File file = new File(System.getProperty("user.dir"), path);
        Assert.assertTrue("source not found at " + file, file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
