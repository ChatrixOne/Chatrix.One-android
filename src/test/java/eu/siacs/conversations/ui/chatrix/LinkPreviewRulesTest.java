package eu.siacs.conversations.ui.chatrix;

import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    /**
     * Ultrareview 2026-11-26: Android reads only a.b.c.d as an address literal and sent these to
     * DNS on the UI thread (NetworkOnMainThreadException, a crash whenever the chat was opened).
     * Desktop Java reads "1.2.3" as 1.2.0.3, so the old code fails here too.
     */
    @Test
    public void digitsAndDotsThatAreNoStrictAddressGetNoPreview() {
        for (final String host :
                new String[] {
                    "1.2.3", "127.1", "8.8.8", "300.1.1.1", "1.2.3.4.5", "1.2.3.", ".1.2.3.4",
                    "1..2.3", "08.8.8.8", "8.8.8.08", "1.2.3.256", "9999999999.1.1.1",
                    "fe80::1%wlan0", "::ffff:1.2.3", "1:2:3"
                }) {
            Assert.assertTrue(host, LinkPreviewRules.looksLocal(host));
        }
    }

    @Test
    public void randomDigitsAndDotsMatchAStrictReading() {
        final Random random = new Random(20261127);
        final String alphabet = "0123456789.";
        for (int i = 0; i < 200_000; i++) {
            final StringBuilder host = new StringBuilder();
            if (random.nextBoolean()) {
                final int length = 1 + random.nextInt(18);
                for (int j = 0; j < length; j++) {
                    host.append(alphabet.charAt(random.nextInt(alphabet.length())));
                }
            } else { // address-like: 3 to 5 parts, sometimes too large or with a leading zero
                final int parts = 3 + random.nextInt(3);
                for (int j = 0; j < parts; j++) {
                    host.append(j == 0 ? "" : ".").append(random.nextInt(8) == 0 ? "0" : "");
                    host.append(random.nextInt(random.nextInt(8) == 0 ? 1000 : 256));
                }
            }
            final Integer ip = strictIpv4(host.toString());
            final boolean expected = ip == null || blocked(ip);
            Assert.assertEquals(host.toString(), expected, LinkPreviewRules.looksLocal(host.toString()));
        }
    }

    /** It runs on the UI thread: no way to a lookup, whatever the input. */
    @Test
    public void looksLocalNeverLooksAnythingUp() throws Exception {
        final File file =
                new File(
                        System.getProperty("user.dir"),
                        "src/main/java/eu/siacs/conversations/ui/chatrix/LinkPreviewRules.java");
        Assert.assertTrue("source not found at " + file, file.isFile());
        final String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        final int start = source.indexOf("static boolean looksLocal(");
        final int end = source.indexOf("static boolean isPublic(", start);
        Assert.assertTrue("looksLocal not found", start >= 0 && end > start);
        final String body = source.substring(start, end);
        for (final String lookup : new String[] {"getByName", "getAllByName", "Dns", "resolve"}) {
            Assert.assertFalse(lookup + " in looksLocal", body.contains(lookup));
        }
    }

    /**
     * Ultrareview 2026-11-26: a server error, overload or bot wall was cached as "this page has
     * no preview" for good.
     */
    @Test
    public void temporaryServerAnswersAreAskedAgain() {
        for (final int code : new int[] {408, 425, 429, 500, 502, 503, 504, 520, 599}) {
            Assert.assertEquals(
                    String.valueOf(code),
                    LinkPreviewRules.RETRY_SOON_MS,
                    LinkPreviewRules.retryAfterMillis(code));
        }
        Assert.assertEquals(LinkPreviewRules.RETRY_LATER_MS, LinkPreviewRules.retryAfterMillis(403));
        for (final int code : new int[] {300, 304, 400, 401, 404, 405, 410, 451, 499, 600, 0, -1}) {
            Assert.assertEquals(String.valueOf(code), 0, LinkPreviewRules.retryAfterMillis(code));
        }
    }

    @Test
    public void aRetryEntryHoldsUntilItsTime() {
        final long now = 1_800_000_000_000L;
        for (final long wait :
                new long[] {LinkPreviewRules.RETRY_SOON_MS, LinkPreviewRules.RETRY_LATER_MS}) {
            final String entry = LinkPreviewRules.retryEntry(now + wait);
            Assert.assertTrue(LinkPreviewRules.retryPending(entry, now));
            Assert.assertTrue(LinkPreviewRules.retryPending(entry, now + wait - 1));
            Assert.assertFalse(LinkPreviewRules.retryPending(entry, now + wait));
            Assert.assertFalse(LinkPreviewRules.retryPending(entry, now + wait + 1));
        }
    }

    /** Broken entries and a clock set back expire: a link can never get stuck without a preview. */
    @Test
    public void brokenOrFarRetryEntriesHaveExpired() {
        final long now = 1_800_000_000_000L;
        final String prefix = LinkPreviewRules.RETRY_PREFIX;
        for (final String entry :
                new String[] {
                    prefix,
                    prefix + "soon",
                    prefix + "-5",
                    prefix + "1e15",
                    prefix + "99999999999999999999",
                    prefix + (now + LinkPreviewRules.RETRY_LATER_MS + 1), // clock set back
                    prefix + Long.MAX_VALUE,
                    prefix + Long.MIN_VALUE,
                    "\u0000none", // the old "no preview" entry is not a retry entry
                    "retry " + (now + 1000),
                    ""
                }) {
            Assert.assertFalse(entry, LinkPreviewRules.retryPending(entry, now));
        }
    }

    @Test
    public void randomRetryEntriesNeverBreakAndOnlyHoldWithinTheLongestWait() {
        final Random random = new Random(20261127L);
        final String alphabet = "0123456789- +.e";
        final long now = 1_800_000_000_000L;
        for (int i = 0; i < 100_000; i++) {
            final String entry;
            if (random.nextBoolean()) {
                final StringBuilder b = new StringBuilder(LinkPreviewRules.RETRY_PREFIX);
                final int length = random.nextInt(24);
                for (int j = 0; j < length; j++) {
                    b.append(alphabet.charAt(random.nextInt(alphabet.length())));
                }
                entry = b.toString();
            } else {
                entry = LinkPreviewRules.retryEntry(now + random.nextLong() % (3 * 86_400_000L));
            }
            if (LinkPreviewRules.retryPending(entry, now)) {
                final String[] parts =
                        entry.substring(LinkPreviewRules.RETRY_PREFIX.length()).trim().split(" ");
                final long until = Long.parseLong(parts[0]);
                Assert.assertTrue(entry, until > now);
                Assert.assertTrue(entry, until - now <= LinkPreviewRules.RETRY_LATER_MS);
                if (parts.length > 1) {
                    Assert.assertEquals(entry, 2, parts.length);
                    Assert.assertTrue(entry, until - now <= LinkPreviewRules.NETWORK_MAX_WAIT_MS);
                    final int failures = Integer.parseInt(parts[1]);
                    Assert.assertTrue(entry, failures >= 1 && failures <= 99);
                }
            }
        }
    }

    /** Ultrareview 2026-11-27: a failure on the way (DNS, TLS, timeout) waits, longer each time. */
    @Test
    public void networkWaitsDoubleUpToOneHour() {
        final long min = 60_000;
        Assert.assertEquals(5 * min, LinkPreviewRules.networkWaitMillis(1));
        Assert.assertEquals(10 * min, LinkPreviewRules.networkWaitMillis(2));
        Assert.assertEquals(20 * min, LinkPreviewRules.networkWaitMillis(3));
        Assert.assertEquals(40 * min, LinkPreviewRules.networkWaitMillis(4));
        Assert.assertEquals(60 * min, LinkPreviewRules.networkWaitMillis(5));
        Assert.assertEquals(60 * min, LinkPreviewRules.networkWaitMillis(99));
        Assert.assertEquals("no overflow", 60 * min, LinkPreviewRules.networkWaitMillis(64));
        Assert.assertEquals(60 * min, LinkPreviewRules.networkWaitMillis(Integer.MAX_VALUE));
        Assert.assertEquals(
                "a broken count: the shortest", 5 * min, LinkPreviewRules.networkWaitMillis(0));
        Assert.assertEquals(5 * min, LinkPreviewRules.networkWaitMillis(Integer.MIN_VALUE));
    }

    /** The entry holds until its time and keeps the count after that, for the next wait. */
    @Test
    public void aNetworkEntryHoldsAndCountsTheFailures() {
        final long now = 1_800_000_000_000L;
        for (int failures = 1; failures <= 6; failures++) {
            final long wait = LinkPreviewRules.networkWaitMillis(failures);
            final String entry = LinkPreviewRules.networkRetryEntry(now + wait, failures);
            Assert.assertTrue(entry, LinkPreviewRules.retryPending(entry, now));
            Assert.assertTrue(LinkPreviewRules.retryPending(entry, now + wait - 1));
            Assert.assertFalse(
                    "over: fetched again", LinkPreviewRules.retryPending(entry, now + wait));
            Assert.assertEquals(failures, LinkPreviewRules.networkFailures(entry));
        }
        Assert.assertEquals(
                "counted up to 99",
                99,
                LinkPreviewRules.networkFailures(LinkPreviewRules.networkRetryEntry(now, 1000)));
        Assert.assertEquals(
                1, LinkPreviewRules.networkFailures(LinkPreviewRules.networkRetryEntry(now, 0)));
        for (final String other :
                new String[] {
                    LinkPreviewRules.retryEntry(now + 1000), // a server's wait starts over
                    "\u0000none",
                    "example.org\nA title",
                    "",
                    null
                }) {
            Assert.assertEquals(String.valueOf(other), 0, LinkPreviewRules.networkFailures(other));
        }
    }

    /** Broken network entries, and one an hour away after the clock was set back, have expired. */
    @Test
    public void brokenOrFarNetworkEntriesHaveExpired() {
        final long now = 1_800_000_000_000L;
        final String prefix = LinkPreviewRules.RETRY_PREFIX;
        final long soon = now + 1000;
        for (final String entry :
                new String[] {
                    prefix + soon + " 0",
                    prefix + soon + " 100",
                    prefix + soon + " -1",
                    prefix + soon + " +1",
                    prefix + soon + " 1 2",
                    prefix + soon + "  3",
                    prefix + soon + " \u0663", // ARABIC-INDIC DIGIT THREE
                    prefix + soon + " x",
                    prefix + " 3",
                    prefix + "soon 3",
                    // the clock was set back
                    prefix + (now + LinkPreviewRules.NETWORK_MAX_WAIT_MS + 1) + " 5",
                    prefix + Long.MAX_VALUE + " 99",
                }) {
            Assert.assertFalse(entry, LinkPreviewRules.retryPending(entry, now));
        }
        Assert.assertTrue(
                "the trailing space is trimmed",
                LinkPreviewRules.retryPending(prefix + soon + " 3 ", now));
    }

    /** The fetch uses the table, and a cached retry entry is read as such. */
    @Test
    public void linkPreviewsUseTheRetryRules() throws Exception {
        final File file =
                new File(
                        System.getProperty("user.dir"),
                        "src/main/java/eu/siacs/conversations/ui/chatrix/LinkPreviews.java");
        Assert.assertTrue("source not found at " + file, file.isFile());
        final String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        for (final String use :
                new String[] {
                    "LinkPreviewRules.retryAfterMillis(response.code())",
                    "LinkPreviewRules.retryPending(content, System.currentTimeMillis())",
                    "LinkPreviewRules.retryEntry(untilMillis)",
                    "catch (final LinkPreviewRules.TryAgainLater e)"
                }) {
            Assert.assertTrue(use, source.contains(use));
        }
    }

    /**
     * Ultrareview 2026-11-27: a failure on the way stores its wait (nothing was stored, so every
     * redraw fetched the link again), the count comes from the entry before, and nothing is
     * fetched offline.
     */
    @Test
    public void linkPreviewsStoreTheNetworkWait() throws Exception {
        final File file =
                new File(
                        System.getProperty("user.dir"),
                        "src/main/java/eu/siacs/conversations/ui/chatrix/LinkPreviews.java");
        Assert.assertTrue("source not found at " + file, file.isFile());
        final String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        final String fetchAndStore = part(source, "private static Preview fetchAndStore(");
        final int io = fetchAndStore.indexOf("catch (final IOException e)");
        final int store =
                fetchAndStore.indexOf("storeNetworkRetry(dir, url, System.currentTimeMillis());");
        final int notYet = fetchAndStore.indexOf("return NOT_YET;", io);
        Assert.assertTrue(
                "a failure on the way is stored", io >= 0 && store > io && notYet > store);
        Assert.assertFalse("never forgotten at once", fetchAndStore.contains("return null;"));
        final String network = part(source, "private static void storeNetworkRetry(");
        Assert.assertTrue(network.contains("LinkPreviewRules.networkFailures(before) + 1"));
        Assert.assertTrue(
                network.contains(
                        "nowMillis + LinkPreviewRules.networkWaitMillis(failures), failures"));
        Assert.assertTrue("a bounded read", network.contains("text.length() <= 64"));
        final String get = part(source, "private static void get(");
        Assert.assertTrue(
                "offline: no fetch",
                get.contains(
                        "online(context) ? fetchAndStore(cacheDir, url, generation) : NOT_YET;"));
        Assert.assertEquals(1, source.split("fetchAndStore\\(cacheDir", -1).length - 1);
        Assert.assertTrue(
                part(source, "private static boolean online(")
                        .contains("getHint() == NetworkManager.Hint.ACTIVE"));
    }

    private static String part(final String source, final String start) {
        final int from = source.indexOf(start);
        final int to = source.indexOf("\n    }\n", from);
        Assert.assertTrue(start + " not found", from >= 0 && to > from);
        return source.substring(from, to);
    }

    /** Independent reference: exactly four decimal parts 0-255, no leading zeros. */
    private static Integer strictIpv4(final String host) {
        final String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        int ip = 0;
        for (final String part : parts) {
            if (part.isEmpty() || part.length() > 3 || part.length() > 1 && part.charAt(0) == '0') {
                return null;
            }
            final int value = Integer.parseInt(part);
            if (value > 255) {
                return null;
            }
            ip = ip << 8 | value;
        }
        return ip;
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
