package eu.siacs.conversations.ui.chatrix;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * Chatrix.One (security review S4-7): our code must not use java.nio.file. It only exists from
 * Android 8 (API 26), the app runs from Android 6 (minSdk 23), and the build's desugaring does not
 * cover it: on Android 6 and 7 the call is an Error that crashes the app. Use java.io instead.
 * Other APIs above minSdk are found by the release check (notes/release/check-apk.py) in the APK.
 */
public class OldAndroidApisTest {

    @Test
    public void ourCodeDoesNotUseJavaNioFile() throws IOException {
        final File dir =
                new File(
                        System.getProperty("user.dir"),
                        "src/main/java/eu/siacs/conversations/ui/chatrix");
        Assert.assertTrue("sources not found at " + dir, dir.isDirectory());
        final File[] files = dir.listFiles((d, name) -> name.endsWith(".java"));
        Assert.assertNotNull(files);
        Assert.assertTrue(files.length > 10);
        final List<String> found = new ArrayList<>();
        for (final File file : files) {
            final List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); ++i) {
                final String code = lines.get(i).replaceFirst("//.*$", "");
                if (code.contains("java.nio.file") || code.contains(".toPath()")) {
                    found.add(file.getName() + ":" + (i + 1));
                }
            }
        }
        Assert.assertEquals("java.nio.file needs Android 8", List.of(), found);
    }
}
