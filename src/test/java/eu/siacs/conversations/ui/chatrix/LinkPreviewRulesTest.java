package eu.siacs.conversations.ui.chatrix;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.junit.Assert;
import org.junit.Test;

/**
 * Chatrix.One: the rules link previews apply to web input. Fixed cases for each rule, plus
 * seeded random inputs (property tests): a failure prints the input, so it can be turned into a
 * fixed case.
 */
public class LinkPreviewRulesTest {

    /** Independent reference for the IPv4 ranges that must never be contacted. */
    private static final String[] BLOCKED_V4 = {
        "0.0.0.0/8",
        "10.0.0.0/8",
        "100.64.0.0/10",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "224.0.0.0/3"
    };

    @Test
    public void publicAndLocalAddresses() throws Exception {
        final String[] notPublic = {
            "0.0.0.0", "0.1.2.3", "10.0.0.1", "100.64.0.1", "100.100.100.100", "100.127.255.255",
            "127.0.0.1", "127.1.2.3", "169.254.169.254", "172.16.0.1", "172.31.255.255",
            "192.168.1.1", "224.0.0.1", "255.255.255.255", "::", "::1", "fe80::1", "fec0::1",
            "fc00::1", "fd12:3456::1", "ff02::1", "::ffff:192.168.1.1", "64:ff9b::c0a8:101"
        };
        final String[] isPublic = {
            "1.1.1.1", "8.8.8.8", "100.63.255.255", "100.128.0.1", "172.15.255.255",
            "172.32.0.1", "192.167.1.1", "192.169.1.1", "223.255.255.255", "2606:4700:4700::1111",
            "2001:4860:4860::8888", "::ffff:8.8.8.8", "64:ff9b::808:808"
        };
        for (final String address : notPublic) {
            Assert.assertFalse(address, LinkPreviewRules.isPublic(InetAddress.getByName(address)));
        }
        for (final String address : isPublic) {
            Assert.assertTrue(address, LinkPreviewRules.isPublic(InetAddress.getByName(address)));
        }
    }

    @Test
    public void randomIpv4AddressesMatchTheRangeTable() throws Exception {
        final Random random = new Random(20261120);
        for (int i = 0; i < 200_000; i++) {
            final int ip = i % 2 == 0 ? random.nextInt() : nearRangeEdge(random);
            final InetAddress address =
                    InetAddress.getByAddress(
                            new byte[] {
                                (byte) (ip >>> 24), (byte) (ip >>> 16), (byte) (ip >>> 8), (byte) ip
                            });
            Assert.assertEquals(
                    address.getHostAddress(), !blocked(ip), LinkPreviewRules.isPublic(address));
        }
    }

    @Test
    public void connectionsToLocalAddressesAreRefusedBeforeConnecting() throws Exception {
        final var sockets = new LinkPreviewRules.PublicOnlySockets();
        for (final String address :
                new String[] {"127.0.0.1", "192.168.1.1", "10.0.0.1", "100.100.100.100", "::1"}) {
            try (Socket socket = sockets.createSocket()) {
                socket.connect(new InetSocketAddress(InetAddress.getByName(address), 443), 1000);
                Assert.fail("connected to " + address);
            } catch (final LinkPreviewRules.NotPublic expected) {
                // refused before anything is sent
            }
        }
        try (Socket socket = sockets.createSocket()) {
            socket.connect(InetSocketAddress.createUnresolved("example.com", 443), 1000);
            Assert.fail("connected to an unresolved address");
        } catch (final LinkPreviewRules.NotPublic expected) {
            // the address must be known to be checked
        }
    }

    @Test(expected = SocketException.class)
    public void otherSocketFactoryMethodsAreNotSupported() throws Exception {
        new LinkPreviewRules.PublicOnlySockets().createSocket("example.com", 443);
    }

    @Test
    public void obviousLocalHostsAreSpottedBeforeAnyLookup() {
        for (final String host :
                new String[] {
                    "localhost", "router", "printer.local", "nas.lan", "127.0.0.1", "192.168.1.1",
                    "10.1.2.3", "::1", "fe80::1"
                }) {
            Assert.assertTrue(host, LinkPreviewRules.looksLocal(host));
        }
        // names are checked when connecting (PublicOnlySockets), not here
        for (final String host :
                new String[] {
                    "example.com", "fritz.box", "chatrix.one", "8.8.8.8", "2606:4700:4700::1111"
                }) {
            Assert.assertFalse(host, LinkPreviewRules.looksLocal(host));
        }
    }

    @Test
    public void readsMetaTagsAsPagesWriteThem() {
        final Map<String, String> meta =
                LinkPreviewRules.metaTags(
                        "<meta property=\"og:title\" content=\"A &amp; B\">"
                                + "<META NAME='twitter:title' CONTENT='Single quoted'>"
                                + "<meta content=\"Content first\" property=\"og:description\">"
                                + "<meta property=og:site_name content=Unquoted>"
                                + "<meta property = \"og:image\" content = \"/img.png\" >"
                                + "<meta property=\"og:title\" content=\"second one ignored\">");
        Assert.assertEquals("A &amp; B", meta.get("og:title"));
        Assert.assertEquals("Single quoted", meta.get("twitter:title"));
        Assert.assertEquals("Content first", meta.get("og:description"));
        Assert.assertEquals("Unquoted", meta.get("og:site_name"));
        Assert.assertEquals("/img.png", meta.get("og:image"));
    }

    @Test
    public void skipsTagsLongerThanTheLimit() {
        Assert.assertTrue(
                LinkPreviewRules.metaTags(
                                "<meta property=\"og:title\" content=\"" + "x".repeat(3000) + "\">")
                        .isEmpty());
    }

    @Test
    public void findsTheTitle() {
        Assert.assertEquals(
                "Hello <b>world</b>",
                LinkPreviewRules.pageTitle("<head><title>Hello <b>world</b></title>"));
        Assert.assertEquals(
                "Upper", LinkPreviewRules.pageTitle("<TITLE lang=\"en\">Upper</TITLE>"));
        Assert.assertEquals(
                "first",
                LinkPreviewRules.pageTitle("<title>first</title><svg><title>second</title></svg>"));
        Assert.assertNull(LinkPreviewRules.pageTitle("<title>unterminated"));
        Assert.assertNull(LinkPreviewRules.pageTitle("no title at all"));
    }

    @Test
    public void findsOEmbedLinks() {
        Assert.assertEquals(
                List.of("https://x/oembed?url=1&amp;format=json", "//y/o.json"),
                LinkPreviewRules.oEmbedLinks(
                        "<link rel=\"alternate\" type=\"application/json+oembed\""
                                + " href=\"https://x/oembed?url=1&amp;format=json\">"
                                + "<link rel=\"stylesheet\" href=\"/style.css\">"
                                + "<LINK TYPE='application/json+oembed' HREF=//y/o.json>"));
    }

    /** The old patterns needed about 40 minutes for the first page: a regression times out. */
    @Test(timeout = 30_000)
    public void hostilePagesAreReadQuickly() {
        final int n = 256 * 1024; // the most a preview reads
        final String[] pages = {
            "<meta " + "a".repeat(n - 7) + ">",
            ("<meta " + "a".repeat(2000) + ">").repeat(n / 2007),
            "<meta ".repeat(n / 6),
            "<link ".repeat(n / 6),
            ("<meta " + "a=".repeat(1000) + ">").repeat(n / 2007),
            ("<meta a=\"" + "b ".repeat(1000) + ">").repeat(n / 2010),
            "<title>".repeat(n / 7),
            "<".repeat(n)
        };
        for (final String page : pages) {
            LinkPreviewRules.metaTags(page);
            LinkPreviewRules.oEmbedLinks(page);
            LinkPreviewRules.pageTitle(page);
        }
    }

    /**
     * Within the tag limit, the attribute search must stay linear too: with backtracking it needs
     * minutes for these pages (found by putting the old pattern back: the test above still passed).
     */
    @Test(timeout = 10_000)
    public void manyLongTagsAreReadQuickly() {
        final String page = ("<meta " + "a".repeat(2000) + ">").repeat(256 * 1024 / 2007);
        for (int i = 0; i < 10; i++) {
            Assert.assertTrue(LinkPreviewRules.metaTags(page).isEmpty());
        }
    }

    @Test(timeout = 30_000)
    public void randomPagesNeverBreakTheParser() {
        final String[] tokens = {
            "<meta ", "<link ", "<title>", "</title>", "<TITLE ", "property=", "name=", "content=",
            "type=", "href=", "application/json+oembed", "og:title", "\"", "'", "=", ">", "<", " ",
            "a", "-", ":", "\n", "é", "😀"
        };
        final Random random = new Random(42);
        for (int i = 0; i < 5_000; i++) {
            final StringBuilder page = new StringBuilder();
            final int length = random.nextInt(400);
            for (int j = 0; j < length; j++) {
                page.append(tokens[random.nextInt(tokens.length)]);
            }
            final String html = page.toString();
            for (final Map.Entry<String, String> tag : LinkPreviewRules.metaTags(html).entrySet()) {
                Assert.assertEquals(
                        html, tag.getKey().toLowerCase(Locale.ROOT).trim(), tag.getKey());
                Assert.assertNotNull(html, tag.getValue());
            }
            for (final String href : LinkPreviewRules.oEmbedLinks(html)) {
                Assert.assertNotNull(html, href);
            }
            final String title = LinkPreviewRules.pageTitle(html);
            if (title != null) {
                Assert.assertFalse(html, title.toLowerCase(Locale.ROOT).contains("</title"));
            }
        }
    }

    @Test
    public void picturesKeepTheirUsualDetail() {
        Assert.assertEquals(1, LinkPreviewRules.sampleSize(1200, 630));
        Assert.assertEquals(4, LinkPreviewRules.sampleSize(4000, 3000));
        Assert.assertEquals(4, LinkPreviewRules.sampleSize(1080, 1920));
        Assert.assertEquals(8, LinkPreviewRules.sampleSize(8000, 6000));
        Assert.assertEquals(2, LinkPreviewRules.sampleSize(400, 400));
        Assert.assertEquals(1, LinkPreviewRules.sampleSize(1, 1));
    }

    @Test
    public void aHugeNarrowPictureFitsTheBudget() {
        // a 47 KB PNG can claim this size; decoded in full it would need 1.43 GiB
        final int sample = LinkPreviewRules.sampleSize(383, 1_000_000);
        Assert.assertTrue(
                decodedPixels(383, 1_000_000, sample) <= LinkPreviewRules.MAX_DECODED_PIXELS);
    }

    @Test
    public void randomPictureSizesNeverExceedTheBudget() {
        final Random random = new Random(7);
        for (int i = 0; i < 1_000_000; i++) {
            final int w = randomSide(random);
            final int h = randomSide(random);
            final int sample = LinkPreviewRules.sampleSize(w, h);
            Assert.assertTrue(w + "x" + h, sample >= 1 && Integer.bitCount(sample) == 1);
            Assert.assertTrue(
                    w + "x" + h + " sample " + sample,
                    decodedPixels(w, h, sample) <= LinkPreviewRules.MAX_DECODED_PIXELS);
        }
    }

    @Test
    public void cuttingNeverSplitsAnEmoji() {
        final String text = "a".repeat(199) + "😀" + "b";
        Assert.assertEquals("a".repeat(199), LinkPreviewRules.cut(text, 200));
        Assert.assertEquals("a".repeat(199) + "😀", LinkPreviewRules.cut(text, 201));
        Assert.assertEquals("short", LinkPreviewRules.cut("short", 200));
        Assert.assertEquals("a".repeat(199) + "…", LinkPreviewRules.shorten(text));
    }

    private static boolean blocked(final int ip) {
        for (final String range : BLOCKED_V4) {
            final int bits = Integer.parseInt(range.substring(range.indexOf('/') + 1));
            final int mask = bits == 0 ? 0 : -1 << (32 - bits);
            if ((ip & mask) == (base(range) & mask)) {
                return true;
            }
        }
        return false;
    }

    /** An address just inside or just outside one of the ranges. */
    private static int nearRangeEdge(final Random random) {
        final String range = BLOCKED_V4[random.nextInt(BLOCKED_V4.length)];
        final int bits = Integer.parseInt(range.substring(range.indexOf('/') + 1));
        final long first = base(range) & 0xffffffffL;
        final long last = first + (1L << (32 - bits)) - 1;
        final long[] edges = {first - 1, first, last, last + 1};
        return (int) edges[random.nextInt(edges.length)];
    }

    private static int base(final String range) {
        final String[] parts = range.substring(0, range.indexOf('/')).split("\\.");
        int ip = 0;
        for (final String part : parts) {
            ip = ip << 8 | Integer.parseInt(part);
        }
        return ip;
    }

    private static int randomSide(final Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> 1 + random.nextInt(4096); // everyday sizes
            case 1 -> 1 + random.nextInt(1 << 20); // large
            case 2 -> 1 + random.nextInt(Integer.MAX_VALUE - 1); // anything a header can claim
            default -> 1 + random.nextInt(400); // tiny or narrow
        };
    }

    private static long decodedPixels(final int width, final int height, final int sample) {
        return ((long) width + sample - 1) / sample * (((long) height + sample - 1) / sample);
    }
}
