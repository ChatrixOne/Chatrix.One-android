package eu.siacs.conversations.ui.chatrix;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Assert;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Chatrix.One: every translation in our files (values-xx/chatrix*.xml, also our overrides of
 * upstream texts) must use the same placeholders (%1$s, %d, ...) as the English text. A wrong one
 * throws at runtime, e.g. in the reaction notification on the connection thread, which would crash
 * the app whenever a reaction arrives.
 */
public class StringPlaceholdersTest {

    private static final Pattern PLACEHOLDER =
            Pattern.compile("%(?:(\\d+)\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?([sSdfxXcboeEgGaA%n])");

    @Test
    public void translationsUseTheSamePlaceholdersAsEnglish() throws Exception {
        final File res = new File(System.getProperty("user.dir"), "src/main/res");
        Assert.assertTrue("resources not found at " + res, new File(res, "values").isDirectory());
        final Map<String, String> english = new HashMap<>();
        for (final File file : xmlFiles(new File(res, "values"), "")) {
            english.putAll(strings(file));
        }
        final List<String> problems = new ArrayList<>();
        int checked = 0;
        final File[] dirs = res.listFiles((d, name) -> name.startsWith("values-"));
        Assert.assertNotNull(dirs);
        for (final File dir : dirs) {
            for (final File file : xmlFiles(dir, "chatrix")) {
                for (final var entry : strings(file).entrySet()) {
                    final String name = entry.getKey();
                    final String base = baseText(english, name);
                    final String where = dir.getName() + "/" + file.getName() + " " + name;
                    if (base == null) {
                        problems.add(where + ": no English text");
                        continue;
                    }
                    checked++;
                    final TreeSet<String> expected = placeholders(base);
                    final TreeSet<String> actual = placeholders(entry.getValue());
                    // "one" may leave out the number ("1 file" -> "a file")
                    final boolean ok =
                            name.endsWith("#one")
                                    ? expected.containsAll(actual)
                                    : expected.equals(actual);
                    if (!ok) {
                        problems.add(where + ": English " + expected + ", here " + actual);
                    }
                }
            }
        }
        Assert.assertTrue("no translations found", checked > 1000);
        Assert.assertTrue(String.join("\n", problems), problems.isEmpty());
    }

    @Test
    public void placeholdersAreRecognised() {
        Assert.assertEquals(
                new TreeSet<>(List.of("1s", "2s", "3s")),
                placeholders("%1$s reacted %2$s to: %3$s"));
        Assert.assertEquals(new TreeSet<>(List.of("1s", "2d")), placeholders("%s has %d (100%%)"));
        Assert.assertEquals(new TreeSet<>(List.of("1s")), placeholders("%1$S"));
        Assert.assertEquals(new TreeSet<>(), placeholders("100%% sure%n"));
    }

    private static String baseText(final Map<String, String> english, final String name) {
        final String exact = english.get(name);
        if (exact != null) {
            return exact;
        }
        final int hash = name.indexOf('#');
        return hash < 0 ? null : english.get(name.substring(0, hash) + "#other");
    }

    /** "%1$s" -> "1s", "%d" (second one without index) -> "2d"; %% and %n are not arguments. */
    static TreeSet<String> placeholders(final String text) {
        final TreeSet<String> result = new TreeSet<>();
        final Matcher matcher = PLACEHOLDER.matcher(text);
        int next = 0;
        while (matcher.find()) {
            final String conversion = matcher.group(2);
            if (conversion.equals("%") || conversion.equals("n")) {
                continue;
            }
            final int index =
                    matcher.group(1) == null ? ++next : Integer.parseInt(matcher.group(1));
            result.add(index + (conversion.equals("S") ? "s" : conversion));
        }
        return result;
    }

    private static List<File> xmlFiles(final File dir, final String prefix) {
        final File[] files =
                dir.listFiles((d, name) -> name.startsWith(prefix) && name.endsWith(".xml"));
        return files == null ? List.of() : List.of(files);
    }

    /** name -> text for every string; plurals as name#quantity. */
    private static Map<String, String> strings(final File file) throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final DocumentBuilder builder = factory.newDocumentBuilder();
        final NodeList children = builder.parse(file).getDocumentElement().getChildNodes();
        final Map<String, String> result = new HashMap<>();
        for (int i = 0; i < children.getLength(); ++i) {
            if (!(children.item(i) instanceof Element element)) {
                continue;
            }
            final String name = element.getAttribute("name");
            if (name.isEmpty()) {
                continue;
            }
            if (element.getTagName().equals("string")) {
                result.put(name, element.getTextContent());
            } else if (element.getTagName().equals("plurals")) {
                final NodeList items = element.getChildNodes();
                for (int j = 0; j < items.getLength(); ++j) {
                    final Node item = items.item(j);
                    if (item instanceof Element e && e.getTagName().equals("item")) {
                        result.put(name + "#" + e.getAttribute("quantity"), e.getTextContent());
                    }
                }
            }
        }
        return result;
    }
}
