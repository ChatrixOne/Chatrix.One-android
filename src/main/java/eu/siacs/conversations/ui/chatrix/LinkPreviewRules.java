package eu.siacs.conversations.ui.chatrix;

import androidx.annotation.Nullable;
import com.google.common.net.InetAddresses;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.SocketFactory;

/**
 * Chatrix.One: the rules link previews apply to everything that comes from the web: which
 * addresses may be contacted, how a page is read, how large a picture may be decoded and how long
 * a title may be. Plain Java without Android, so unit tests can check every rule.
 */
public final class LinkPreviewRules {

    /** Square pictures (compact card). */
    static final int THUMB_PX = 192;

    /** Wide pictures (large card) are kept up to this width. */
    static final int WIDE_PX = 720;

    /** Pictures at least this much wider than high get the large card. */
    static final float WIDE_MIN_RATIO = 1.3f;

    /** Even wider pictures are trimmed at the sides to this ratio. */
    static final float WIDE_MAX_RATIO = 2.0f;

    /** A decoded picture stays below this (16 MB), whatever size the file claims. */
    static final long MAX_DECODED_PIXELS = 4L * 1024 * 1024;

    static final int MAX_TITLE_CHARS = 200;

    /** A title from the page is cut to this before it is read as HTML. */
    static final int MAX_RAW_CHARS = 2048;

    /** IPv6 prefixes that carry an IPv4 address in the last four bytes. */
    private static final byte[] V4_MAPPED = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff
    };

    private static final byte[] NAT64 = {0, 0x64, (byte) 0xff, (byte) 0x9b, 0, 0, 0, 0, 0, 0, 0, 0};

    // A hostile page must not keep the phone busy: tags longer than 2048 characters are skipped,
    // and possessive quantifiers plus "a name starts only after a non-name character" keep the
    // attribute search linear (the old patterns needed minutes for one long tag).
    private static final Pattern META =
            Pattern.compile("<meta\\s[^>]{0,2048}+>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK =
            Pattern.compile("<link\\s[^>]{0,2048}+>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR =
            Pattern.compile(
                    "(?<![a-zA-Z_:-])([a-zA-Z_:-]++)\\s*+=\\s*+"
                            + "(?:\"([^\"]*+)\"|'([^']*+)'|([^\\s\"'>]++))");

    private LinkPreviewRules() {}

    /**
     * Quick check of the address as written, before any lookup: obvious local names and address
     * literals. Names such as fritz.box pass; {@link PublicOnlySockets} checks the address every
     * connection really goes to.
     *
     * <p>Runs on the UI thread (message list), so it must never look anything up. Not
     * InetAddress.getByName: Android takes only a.b.c.d as a literal and sends "1.2.3", "127.1" or
     * "300.1.1.1" to DNS, which throws NetworkOnMainThreadException (a crash on every opening of
     * the chat). Guava's InetAddresses.forString never does a lookup (documented) and accepts only
     * strict literals; anything else made of digits and dots gets no preview.
     */
    static boolean looksLocal(final String host) {
        final String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost")
                || h.endsWith(".local")
                || h.endsWith(".lan")
                || !h.contains(".") && !h.contains(":")) {
            return true;
        }
        if (!h.matches("[0-9.]+") && !h.contains(":")) {
            return false; // a name, not an address literal
        }
        if (h.contains("%")) {
            return true; // IPv6 with a scope (fe80::1%wlan0): only for the local network
        }
        try {
            return !isPublic(InetAddresses.forString(h));
        } catch (final IllegalArgumentException e) {
            return true; // not a strict literal: no preview, and no lookup
        }
    }

    /**
     * False for the phone itself, the home network, VPN ranges (100.64.0.0/10, e.g. Tailscale),
     * link-local, multicast and reserved addresses.
     */
    static boolean isPublic(final InetAddress address) {
        final byte[] b = address.getAddress();
        if (b.length == 4) {
            return isPublicV4(b, 0);
        }
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || (b[0] & 0xfe) == 0xfc) { // fc00::/7, unique local
            return false;
        }
        // an IPv4 address inside IPv6 (::ffff:a.b.c.d, NAT64 64:ff9b::a.b.c.d)
        if (startsWith(b, V4_MAPPED) || startsWith(b, NAT64)) {
            return isPublicV4(b, 12);
        }
        return true;
    }

    private static boolean isPublicV4(final byte[] b, final int i) {
        final int x = b[i] & 0xff;
        final int y = b[i + 1] & 0xff;
        return x != 0 // "this network"
                && x != 10 // private
                && x != 127 // the phone itself
                && !(x == 100 && y >= 64 && y <= 127) // carrier NAT, Tailscale
                && !(x == 169 && y == 254) // link-local
                && !(x == 172 && y >= 16 && y <= 31) // private
                && !(x == 192 && y == 168) // private
                && x < 224; // multicast, reserved, broadcast
    }

    private static boolean startsWith(final byte[] b, final byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (b[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** The link leads to an address that is not public: not fetched, and remembered as such. */
    static final class NotPublic extends IOException {
        NotPublic(@Nullable final InetAddress address) {
            super("not a public address: " + address);
        }
    }

    /** A server error, overload or timeout (408, 425, 429, 5xx): asked again after an hour. */
    static final long RETRY_SOON_MS = 60L * 60 * 1000;

    /** Refused (403, often a bot wall): rarely lifted soon, asked again after a day. */
    static final long RETRY_LATER_MS = 24L * 60 * 60 * 1000;

    /**
     * Cache entry of a link that is not fetched again yet; the time follows (epoch ms). After a
     * failure on the way ({@link #networkRetryEntry}) a space and the number of failures in a row
     * follow too.
     */
    static final String RETRY_PREFIX = "\u0000retry ";

    /**
     * After a failure on the way (the name does not resolve, TLS fails, timeout, reset): the first
     * wait; it doubles with each failure in a row, up to {@link #NETWORK_MAX_WAIT_MS}.
     *
     * <p>Ultrareview 2026-11-27: nothing was stored after such a failure, so every redraw of the
     * chat (each typing notice or receipt) fetched the dead link again. The wait is on disk, so an
     * app restart does not end it; a fetch only ever starts when the message is on screen.
     */
    static final long NETWORK_FIRST_WAIT_MS = 5L * 60 * 1000;

    static final long NETWORK_MAX_WAIT_MS = 60L * 60 * 1000;

    /** Failures in a row are counted up to this; the wait reached its maximum long before. */
    static final int MAX_FAILURES = 99;

    /**
     * How long to wait before asking again after this unsuccessful HTTP answer; 0 if the answer
     * is final (404, 410, 401, ...) and the link is remembered as having no preview.
     *
     * <p>Ultrareview 2026-11-26: every unsuccessful answer used to be final, so a site that was
     * down for a moment never got a preview for that link.
     */
    static long retryAfterMillis(final int httpCode) {
        if (httpCode == 408 || httpCode == 425 || httpCode == 429 || httpCode / 100 == 5) {
            return RETRY_SOON_MS;
        }
        return httpCode == 403 ? RETRY_LATER_MS : 0;
    }

    /** A server's "not now" ({@link #retryAfterMillis}). */
    static String retryEntry(final long untilMillis) {
        return RETRY_PREFIX + untilMillis;
    }

    /** The wait after this many failures on the way in a row: 5, 10, 20, 40, 60, 60... minutes. */
    static long networkWaitMillis(final int failures) {
        // clamped first, so no count overflows; the cap is reached after 4 doublings
        final int doublings = Math.max(1, Math.min(failures, 6)) - 1;
        return Math.min(NETWORK_FIRST_WAIT_MS << doublings, NETWORK_MAX_WAIT_MS);
    }

    /** A failure on the way; {@code failures} counts this one too. */
    static String networkRetryEntry(final long untilMillis, final int failures) {
        return RETRY_PREFIX + untilMillis + ' ' + Math.max(1, Math.min(failures, MAX_FAILURES));
    }

    /**
     * The failures on the way in a row an entry has counted, also once its wait is over; 0 for
     * any other entry (a preview, "no preview", a server's wait, a broken one).
     */
    static int networkFailures(@Nullable final String entry) {
        final long[] parsed = parseRetry(entry);
        return parsed == null ? 0 : (int) parsed[1];
    }

    /**
     * True while a cache entry from {@link #retryEntry} or {@link #networkRetryEntry} still says
     * "not yet". A broken entry, or one further away than its longest wait (the clock was set
     * back), has expired: the link is fetched again, so it can never get stuck.
     */
    static boolean retryPending(final String entry, final long nowMillis) {
        final long[] parsed = parseRetry(entry);
        if (parsed == null) {
            return false;
        }
        final long until = parsed[0];
        final long longest = parsed[1] > 0 ? NETWORK_MAX_WAIT_MS : RETRY_LATER_MS;
        return until > nowMillis && until - nowMillis <= longest;
    }

    /** {until, failures on the way (0: a server's wait)}, or null if not a valid retry entry. */
    @Nullable
    private static long[] parseRetry(@Nullable final String entry) {
        if (entry == null
                || !entry.startsWith(RETRY_PREFIX)
                // longer than any long, a space and the largest count
                || entry.length() > RETRY_PREFIX.length() + 19 + 1 + 2) {
            return null;
        }
        final String rest = entry.substring(RETRY_PREFIX.length()).trim();
        final int space = rest.indexOf(' ');
        try {
            if (space < 0) {
                return new long[] {Long.parseLong(rest), 0};
            }
            final String count = rest.substring(space + 1);
            // ASCII digits only: Character.isDigit and parseInt also take other scripts' digits
            if (count.isEmpty()
                    || count.length() > 2
                    || !count.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return null;
            }
            final int failures = Integer.parseInt(count);
            if (failures < 1) {
                return null;
            }
            return new long[] {Long.parseLong(rest.substring(0, space)), failures};
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /** The server failed for now ({@link #retryAfterMillis} > 0): no preview yet, nothing final. */
    static final class TryAgainLater extends IOException {
        final long retryMillis;

        TryAgainLater(final int httpCode, final long retryMillis) {
            super("HTTP " + httpCode + ", trying again later");
            this.retryMillis = retryMillis;
        }
    }

    /** Refuses every connection to an address that is not public, before it is made. */
    static final class PublicOnlySockets extends SocketFactory {

        @Override
        public Socket createSocket() {
            return new Socket() {
                @Override
                public void connect(final SocketAddress endpoint, final int timeout)
                        throws IOException {
                    final InetAddress address =
                            endpoint instanceof InetSocketAddress a ? a.getAddress() : null;
                    if (address == null || !isPublic(address)) {
                        throw new NotPublic(address);
                    }
                    super.connect(endpoint, timeout);
                }
            };
        }

        // OkHttp only uses createSocket() above

        @Override
        public Socket createSocket(final String host, final int port) throws IOException {
            throw new SocketException("not supported");
        }

        @Override
        public Socket createSocket(
                final String host, final int port, final InetAddress local, final int localPort)
                throws IOException {
            throw new SocketException("not supported");
        }

        @Override
        public Socket createSocket(final InetAddress host, final int port) throws IOException {
            throw new SocketException("not supported");
        }

        @Override
        public Socket createSocket(
                final InetAddress host,
                final int port,
                final InetAddress local,
                final int localPort)
                throws IOException {
            throw new SocketException("not supported");
        }
    }

    /** {@code <meta>} contents by lower-case property or name; the first tag wins. */
    static Map<String, String> metaTags(final String html) {
        final Map<String, String> result = new HashMap<>();
        final Matcher tags = META.matcher(html);
        while (tags.find()) {
            String key = null;
            String content = null;
            for (final String[] attribute : attributes(tags.group())) {
                if (attribute[0].equals("property") || attribute[0].equals("name")) {
                    key = attribute[1].toLowerCase(Locale.ROOT).trim();
                } else if (attribute[0].equals("content")) {
                    content = attribute[1];
                }
            }
            if (key != null && content != null && !result.containsKey(key)) {
                result.put(key, content);
            }
        }
        return result;
    }

    /** The href of every {@code <link type="application/json+oembed">}, as written, in order. */
    static List<String> oEmbedLinks(final String html) {
        final List<String> result = new ArrayList<>();
        final Matcher tags = LINK.matcher(html);
        while (tags.find()) {
            String type = null;
            String href = null;
            for (final String[] attribute : attributes(tags.group())) {
                if (attribute[0].equals("type")) {
                    type = attribute[1].toLowerCase(Locale.ROOT).trim();
                } else if (attribute[0].equals("href")) {
                    href = attribute[1];
                }
            }
            if ("application/json+oembed".equals(type) && href != null) {
                result.add(href);
            }
        }
        return result;
    }

    /** The attributes of one tag in order, as {lower-case name, value}. */
    private static List<String[]> attributes(final String tag) {
        final List<String[]> result = new ArrayList<>();
        final Matcher m = ATTR.matcher(tag);
        while (m.find()) {
            final String value =
                    m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
            result.add(new String[] {m.group(1).toLowerCase(Locale.ROOT), value});
        }
        return result;
    }

    /** Text of the first {@code <title>} element, or null; a plain search, no regex. */
    @Nullable
    static String pageTitle(final String html) {
        final int tag = indexOfTag(html, "<title", 0);
        final int start = tag < 0 ? -1 : html.indexOf('>', tag);
        final int end = start < 0 ? -1 : indexOfTag(html, "</title", start);
        return end < 0 ? null : html.substring(start + 1, end);
    }

    /** Where {@code tag} (lower case) starts at or after {@code from}, case ignored; or -1. */
    private static int indexOfTag(final String html, final String tag, final int from) {
        for (int i = html.indexOf('<', from); i >= 0; i = html.indexOf('<', i + 1)) {
            if (html.regionMatches(true, i, tag, 0, tag.length())) {
                return i;
            }
        }
        return -1;
    }

    /** Whether a picture of this size gets the large card. */
    static boolean isWide(final int width, final int height) {
        return width >= height * WIDE_MIN_RATIO;
    }

    /**
     * Power-of-two sample size for decoding a picture of this size: enough detail for the card,
     * but never more than {@link #MAX_DECODED_PIXELS} decoded pixels.
     */
    static int sampleSize(final int width, final int height) {
        int sample = 1;
        if (isWide(width, height)) {
            while (width / (sample * 2) >= WIDE_PX) {
                sample *= 2;
            }
        } else {
            while (Math.min(width, height) / (sample * 2) >= THUMB_PX) {
                sample *= 2;
            }
        }
        // a long, narrow picture would still be huge: shrink it further to fit the budget
        return ImageBudget.fit(width, height, sample, MAX_DECODED_PIXELS);
    }

    static String shorten(final String title) {
        return title.length() > MAX_TITLE_CHARS ? cut(title, MAX_TITLE_CHARS) + "…" : title;
    }

    /**
     * The first {@code max} characters, without splitting an emoji or other surrogate pair. Also
     * used for the reaction notification text (NotificationService.pushReaction).
     */
    public static String cut(final String text, final int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max);
    }
}
