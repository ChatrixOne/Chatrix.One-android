package eu.siacs.conversations.ui.chatrix;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.core.text.HtmlCompat;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import de.gultsch.common.Linkify;
import de.gultsch.common.MiniUri;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.BuildConfig;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.http.HttpConnectionManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.SocketFactory;
import okhttp3.ConnectionPool;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Chatrix.One: link preview card (title, website, small picture) under a text message.
 *
 * <p>Off by default (Settings > Privacy). Only for our own messages and messages from people in
 * the contact list, also in group chats; never with Tor or a proxy. Only the first https link of
 * a message, only over https and only to public addresses: never the home network, a VPN or the
 * phone itself. The website line always names the host of the link a tap opens, so a page cannot
 * pose as another site. The page is read once (at most {@link #MAX_HTML_BYTES}), the result is
 * kept in memory and in the private app cache, so each link is fetched only once; the cache is
 * deleted when previews are turned off.
 */
public final class LinkPreviews {

    public record Preview(String url, String title, String site, @Nullable Bitmap image) {}

    private interface Callback {
        void onPreview(@Nullable Preview preview);
    }

    private static final int MAX_HTML_BYTES = 256 * 1024;
    private static final int MAX_IMAGE_BYTES = 3 * 1024 * 1024;
    private static final int MAX_OEMBED_BYTES = 64 * 1024;
    /** Square pictures (compact card). */
    private static final int THUMB_PX = 192;

    /** Wide pictures (large card) are kept up to this width. */
    private static final int WIDE_PX = 720;

    /** Pictures at least this much wider than high get the large card. */
    private static final float WIDE_MIN_RATIO = 1.3f;

    /** Even wider pictures are trimmed at the sides to this ratio. */
    private static final float WIDE_MAX_RATIO = 2.0f;
    private static final int MAX_TITLE_CHARS = 200;

    /** A title from the page is cut to this before it is read as HTML. */
    private static final int MAX_RAW_CHARS = 2048;

    /** A decoded picture stays below this (16 MB), whatever size the file claims. */
    private static final long MAX_DECODED_PIXELS = 4L * 1024 * 1024;

    /** v4: the website line is always the host of the link itself. */
    private static final String CACHE_DIR = "chatrix_link_previews_v4";

    private static final String[] OLD_CACHE_DIRS = {
        "chatrix_link_previews", "chatrix_link_previews_v2", "chatrix_link_previews_v3"
    };

    private static final String NONE = "\u0000none";

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
    private static final Pattern CHARSET =
            Pattern.compile("charset\\s*=\\s*[\"']?([a-zA-Z0-9_-]+)", Pattern.CASE_INSENSITIVE);

    /** url -> preview, limited by picture size; {@link #MISSING} marks links without one. */
    private static final LruCache<String, Preview> MEMORY =
            new LruCache<>(12 * 1024 * 1024) {
                @Override
                protected int sizeOf(final String key, final Preview value) {
                    return value.image() == null ? 512 : value.image().getByteCount();
                }
            };

    private static final Preview MISSING = new Preview("", "", "", null);
    private static final AtomicBoolean OLD_CACHE_REMOVED = new AtomicBoolean(false);
    private static final AtomicBoolean CACHE_CLEARED = new AtomicBoolean(false);
    private static final Map<String, List<Callback>> PENDING = new HashMap<>();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final OkHttpClient CLIENT =
            HttpConnectionManager.OK_HTTP_CLIENT
                    .newBuilder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .callTimeout(20, TimeUnit.SECONDS)
                    // every connection is checked before it is made (also after a redirect),
                    // so none may come from the pool the rest of the app shares
                    .connectionPool(new ConnectionPool())
                    .socketFactory(new PublicOnlySockets())
                    // an https page may not send us on to plain http
                    .followSslRedirects(false)
                    // a refused address is final, not a reason to try again
                    .retryOnConnectionFailure(false)
                    // the app's name, but not its version
                    .addInterceptor(
                            chain ->
                                    chain.proceed(
                                            chain.request()
                                                    .newBuilder()
                                                    .header("User-Agent", BuildConfig.APP_NAME)
                                                    .build()))
                    .build();

    private LinkPreviews() {}

    /** Hides the card; call for every message, then {@link #bind} for text messages. */
    public static void hide(final View card) {
        card.setTag(null);
        card.setVisibility(View.GONE);
    }

    /**
     * Shows the card for the first web link of {@code message} if previews are on and the sender
     * may get one. The card stays hidden until the preview has loaded.
     */
    public static void bind(
            final View card,
            final TextView body,
            final Message message,
            final @ColorInt int textColor) {
        hide(card);
        final Context context = card.getContext();
        final AppSettings settings = new AppSettings(context);
        if (!settings.isLinkPreviews()) {
            clearCache(context);
            return;
        }
        CACHE_CLEARED.set(false);
        if (settings.isUseTor()) {
            return;
        }
        final String[] link = eligibleLink(message);
        if (link == null) {
            return;
        }
        final String url = link[0];
        // a message that is only the link shows just the card, without the address
        final boolean linkOnly = message.getBody().trim().equals(link[1]);
        card.setTag(url);
        final File cacheDir = new File(context.getCacheDir(), CACHE_DIR);
        if (OLD_CACHE_REMOVED.compareAndSet(false, true)) {
            final File base = context.getCacheDir();
            EXECUTOR.execute(() -> {
                for (final String old : OLD_CACHE_DIRS) {
                    deleteRecursively(new File(base, old));
                }
            });
        }
        get(cacheDir, url, preview -> {
            if (preview != null && url.equals(card.getTag())) {
                show(card, body, preview, textColor, linkOnly);
            }
        });
    }

    /** {normalised url, url as written in the message}, or null. */
    @Nullable
    private static String[] eligibleLink(final Message message) {
        if (message.getStatus() == Message.STATUS_RECEIVED) {
            final var contact = message.getContact();
            if (contact == null || !contact.showInContactList()) {
                return null;
            }
        }
        final String body = message.getBody();
        if (Strings.isNullOrEmpty(body)) {
            return null;
        }
        for (final MiniUri link : Linkify.getLinks(body)) {
            final String scheme = Strings.nullToEmpty(link.getScheme()).toLowerCase(Locale.ROOT);
            // plain http could be read and changed on the way: no preview for it
            if (scheme.equals("https")) {
                final HttpUrl url = HttpUrl.parse(link.getRaw());
                return url == null || isLocal(url.host())
                        ? null
                        : new String[] {url.toString(), link.getRaw()};
            }
        }
        return null;
    }

    private static void deleteRecursively(final File file) {
        final File[] children = file.listFiles();
        if (children != null) {
            for (final File child : children) {
                deleteRecursively(child);
            }
        }
        if (file.exists() && !file.delete()) {
            Log.d(Config.LOGTAG, "could not delete " + file);
        }
    }

    /** Previews are off: forget every stored title and picture (once per switch). */
    private static void clearCache(final Context context) {
        if (CACHE_CLEARED.compareAndSet(false, true)) {
            MEMORY.evictAll();
            final File base = context.getCacheDir();
            EXECUTOR.execute(
                    () -> {
                        deleteRecursively(new File(base, CACHE_DIR));
                        for (final String old : OLD_CACHE_DIRS) {
                            deleteRecursively(new File(base, old));
                        }
                    });
        }
    }

    /**
     * Quick check of the address as written, before any lookup. The real guard is {@link
     * PublicOnlySockets}, which checks every address a connection actually goes to.
     */
    private static boolean isLocal(final String host) {
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
        try {
            return !isPublic(InetAddress.getByName(h)); // literal: no DNS lookup
        } catch (final IOException e) {
            return true;
        }
    }

    /**
     * False for the phone itself, the home network, VPN ranges (100.64.0.0/10, e.g. Tailscale),
     * link-local, multicast and reserved addresses.
     */
    private static boolean isPublic(final InetAddress address) {
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
    private static final class NotPublic extends IOException {
        NotPublic(@Nullable final InetAddress address) {
            super("not a public address: " + address);
        }
    }

    /** Refuses every connection to an address that is not public, before it is made. */
    private static final class PublicOnlySockets extends SocketFactory {

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

    private static void show(
            final View card,
            final TextView body,
            final Preview preview,
            final int textColor,
            final boolean linkOnly) {
        final ImageView banner = card.findViewById(R.id.link_preview_banner);
        final View row = card.findViewById(R.id.link_preview_row);
        final ImageView image = card.findViewById(R.id.link_preview_image);
        final TextView title = card.findViewById(R.id.link_preview_title);
        final TextView site = card.findViewById(R.id.link_preview_site);
        final float density = card.getResources().getDisplayMetrics().density;
        title.setText(preview.title());
        title.setTextColor(textColor);
        title.setPaintFlags(title.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        site.setText(preview.site());
        site.setTextColor(textColor);

        final Bitmap bitmap = preview.image();
        final boolean wide =
                bitmap != null && bitmap.getWidth() >= bitmap.getHeight() * WIDE_MIN_RATIO;
        if (wide) {
            // large card: the whole picture across the full width, text below
            final int width = card.getResources().getDimensionPixelSize(R.dimen.audio_player_width);
            final var params = banner.getLayoutParams();
            params.height = Math.round(width * bitmap.getHeight() / (float) bitmap.getWidth());
            banner.setLayoutParams(params);
            banner.setImageBitmap(bitmap);
            banner.setVisibility(View.VISIBLE);
            image.setImageDrawable(null);
            image.setVisibility(View.GONE);
            row.setMinimumHeight(0);
        } else {
            // compact card: square picture (if any) next to the text
            banner.setImageDrawable(null);
            banner.setVisibility(View.GONE);
            if (bitmap != null) {
                image.setImageBitmap(bitmap);
                image.setVisibility(View.VISIBLE);
            } else {
                image.setImageDrawable(null);
                image.setVisibility(View.GONE);
            }
            row.setMinimumHeight(Math.round(64 * density));
        }

        // link-only message with a wide picture: the bubble itself is the card (edge to edge)
        final boolean flush = linkOnly && wide;
        final var margins = (ViewGroup.MarginLayoutParams) card.getLayoutParams();
        final int inset = flush ? 0 : Math.round(8 * density);
        margins.setMarginStart(inset);
        margins.setMarginEnd(inset);
        margins.topMargin = flush ? 0 : Math.round(6 * density);
        card.setLayoutParams(margins);
        card.setBackgroundTintList(
                ColorStateList.valueOf(
                        flush
                                ? Color.TRANSPARENT
                                : ColorUtils.setAlphaComponent(textColor, 0x24)));
        card.setOnClickListener(v -> open(v.getContext(), preview.url()));
        card.setVisibility(View.VISIBLE);
        if (linkOnly) {
            body.setVisibility(View.GONE);
        }
    }

    private static void open(final Context context, final String url) {
        final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
        try {
            context.startActivity(intent);
        } catch (final ActivityNotFoundException e) {
            Log.d(Config.LOGTAG, "no app to open link preview");
        }
    }

    private static void get(final File cacheDir, final String url, final Callback callback) {
        final Preview cached = MEMORY.get(url);
        if (cached != null) {
            callback.onPreview(cached == MISSING ? null : cached);
            return;
        }
        synchronized (PENDING) {
            final List<Callback> waiting = PENDING.get(url);
            if (waiting != null) {
                waiting.add(callback);
                return;
            }
            final List<Callback> list = new ArrayList<>();
            list.add(callback);
            PENDING.put(url, list);
        }
        EXECUTOR.execute(() -> {
            Preview preview = readDisk(cacheDir, url);
            if (preview == null) {
                preview = fetchAndStore(cacheDir, url);
            }
            if (preview != null) {
                MEMORY.put(url, preview);
            }
            final Preview result = preview == MISSING ? null : preview;
            final List<Callback> callbacks;
            synchronized (PENDING) {
                callbacks = PENDING.remove(url);
            }
            MAIN.post(() -> {
                if (callbacks != null) {
                    for (final Callback c : callbacks) {
                        c.onPreview(result);
                    }
                }
            });
        });
    }

    private static String key(final String url) {
        return Hashing.sha256().hashString(url, StandardCharsets.UTF_8).toString();
    }

    /** Null if not on disk yet; {@link #MISSING} if we already know there is no preview. */
    @Nullable
    private static Preview readDisk(final File dir, final String url) {
        final File text = new File(dir, key(url) + ".txt");
        if (!text.isFile()) {
            return null;
        }
        try {
            final String content = Files.asCharSource(text, StandardCharsets.UTF_8).read();
            if (content.startsWith(NONE)) {
                return MISSING;
            }
            final int nl = content.indexOf('\n');
            if (nl < 0) {
                return null;
            }
            final File img = new File(dir, key(url) + ".jpg");
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565; // half the memory, no alpha needed
            final Bitmap bitmap =
                    img.isFile() ? BitmapFactory.decodeFile(img.getAbsolutePath(), options) : null;
            return new Preview(url, content.substring(nl + 1), content.substring(0, nl), bitmap);
        } catch (final IOException e) {
            return null;
        }
    }

    /** Null on a network error (offline, timeout): not cached, so it is tried again later. */
    @Nullable
    private static Preview fetchAndStore(final File dir, final String url) {
        Preview preview;
        try {
            preview = fetch(url);
        } catch (final IOException e) {
            if (!Iterables.any(Throwables.getCausalChain(e), NotPublic.class::isInstance)) {
                Log.d(Config.LOGTAG, "link preview failed: " + e.getMessage());
                return null;
            }
            preview = MISSING; // leads to an address that is not public: never again
        } catch (final RuntimeException | OutOfMemoryError | StackOverflowError e) {
            // a broken or hostile page must never take the app down: no preview for this link
            Log.d(Config.LOGTAG, "link preview failed", e);
            preview = MISSING;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return preview;
        }
        final String k = key(url);
        try {
            if (preview == MISSING) {
                Files.asCharSink(new File(dir, k + ".txt"), StandardCharsets.UTF_8).write(NONE);
            } else {
                if (preview.image() != null) {
                    try (FileOutputStream out = new FileOutputStream(new File(dir, k + ".jpg"))) {
                        preview.image().compress(Bitmap.CompressFormat.JPEG, 85, out);
                    }
                }
                Files.asCharSink(new File(dir, k + ".txt"), StandardCharsets.UTF_8)
                        .write(preview.site() + '\n' + preview.title());
            }
        } catch (final IOException e) {
            Log.d(Config.LOGTAG, "could not cache link preview", e);
        }
        return preview;
    }

    private static Preview fetch(final String url) throws IOException {
        final HttpUrl original = HttpUrl.get(url);
        // through a proxy we cannot see where a request really goes: no preview (tried again
        // later, when no proxy is set)
        final ProxySelector proxies = ProxySelector.getDefault();
        if (proxies != null) {
            for (final Proxy proxy : proxies.select(original.uri())) {
                if (proxy.type() != Proxy.Type.DIRECT) {
                    throw new IOException("proxy in use");
                }
            }
        }
        // Sites that build their pages with JavaScript (YouTube) offer oEmbed for apps instead
        final HttpUrl knownOEmbed = knownOEmbed(original);
        if (knownOEmbed != null) {
            final Preview fromOEmbed = fetchOEmbed(url, knownOEmbed);
            if (fromOEmbed != null) {
                return fromOEmbed;
            }
        }
        final Request request =
                new Request.Builder()
                        .url(url)
                        .header("Accept", "text/html,application/xhtml+xml")
                        .build();
        final String html;
        final HttpUrl finalUrl;
        try (Response response = CLIENT.newCall(request).execute()) {
            final ResponseBody body = response.body();
            final MediaType type = body.contentType();
            if (!response.isSuccessful()
                    || type == null
                    || !type.subtype().toLowerCase(Locale.ROOT).contains("html")) {
                return MISSING;
            }
            finalUrl = response.request().url();
            final byte[] bytes = readAtMost(body.byteStream(), MAX_HTML_BYTES);
            html = new String(bytes, charset(type, bytes));
        }
        final Map<String, String> meta = metaTags(html);
        String title = first(meta.get("og:title"), meta.get("twitter:title"));
        if (title == null) {
            // the page may point to its own oEmbed address
            final HttpUrl discovered = discoverOEmbed(html, finalUrl);
            if (discovered != null) {
                final Preview fromOEmbed = fetchOEmbed(url, discovered);
                if (fromOEmbed != null) {
                    return fromOEmbed;
                }
            }
            title = pageTitle(html);
        }
        title = clean(title);
        if (title == null || title.startsWith("- ")) {
            return MISSING;
        }
        final String imageRef =
                first(meta.get("og:image"), meta.get("og:image:url"), meta.get("twitter:image"));
        final HttpUrl imageUrl =
                imageRef == null
                        ? null
                        : finalUrl.resolve(HtmlCompat.fromHtml(imageRef, 0).toString().trim());
        // the website line names the link a tap opens, not where the page redirected us
        return new Preview(url, shorten(title), siteName(original), loadImage(imageUrl));
    }

    private static String siteName(final HttpUrl url) {
        final String host = url.host();
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    private static String shorten(final String title) {
        return title.length() > MAX_TITLE_CHARS ? cut(title, MAX_TITLE_CHARS) + "…" : title;
    }

    /** The first {@code max} characters, without splitting an emoji or other surrogate pair. */
    private static String cut(final String text, final int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max);
    }

    @Nullable
    private static Bitmap loadImage(@Nullable final HttpUrl imageUrl) {
        if (imageUrl == null || !imageUrl.isHttps() || isLocal(imageUrl.host())) {
            return null;
        }
        try {
            return fetchImage(imageUrl);
        } catch (final IOException e) {
            Log.d(Config.LOGTAG, "link preview image failed: " + e.getMessage());
            return null;
        }
    }

    /** oEmbed endpoints of big sites whose pages have no usable title without JavaScript. */
    @Nullable
    private static HttpUrl knownOEmbed(final HttpUrl url) {
        final String host = url.host().toLowerCase(Locale.ROOT);
        final String endpoint;
        if (host.equals("youtu.be")
                || host.equals("youtube.com")
                || host.endsWith(".youtube.com")) {
            endpoint = "https://www.youtube.com/oembed";
        } else if (host.equals("vimeo.com") || host.endsWith(".vimeo.com")) {
            endpoint = "https://vimeo.com/api/oembed.json";
        } else {
            return null;
        }
        return HttpUrl.get(endpoint)
                .newBuilder()
                .addQueryParameter("format", "json")
                .addQueryParameter("url", url.toString())
                .build();
    }

    @Nullable
    private static HttpUrl discoverOEmbed(final String html, final HttpUrl base) {
        final Matcher links = LINK.matcher(html);
        while (links.find()) {
            String type = null;
            String href = null;
            final Matcher attrs = ATTR.matcher(links.group());
            while (attrs.find()) {
                final String name = attrs.group(1).toLowerCase(Locale.ROOT);
                final String value =
                        attrs.group(2) != null
                                ? attrs.group(2)
                                : attrs.group(3) != null ? attrs.group(3) : attrs.group(4);
                if (name.equals("type")) {
                    type = value.toLowerCase(Locale.ROOT).trim();
                } else if (name.equals("href")) {
                    href = value;
                }
            }
            if ("application/json+oembed".equals(type) && href != null) {
                final HttpUrl url =
                        base.resolve(HtmlCompat.fromHtml(href, 0).toString().trim());
                if (url != null && url.isHttps() && !isLocal(url.host())) {
                    return url;
                }
            }
        }
        return null;
    }

    /** Title and thumbnail from an oEmbed JSON answer; null if it has no title. */
    @Nullable
    private static Preview fetchOEmbed(final String url, final HttpUrl endpoint) {
        final JSONObject json;
        try (Response response =
                CLIENT.newCall(
                                new Request.Builder()
                                        .url(endpoint)
                                        .header("Accept", "application/json")
                                        .build())
                        .execute()) {
            if (!response.isSuccessful()) {
                return null;
            }
            final byte[] bytes = readAtMost(response.body().byteStream(), MAX_OEMBED_BYTES);
            json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (final IOException | JSONException e) {
            Log.d(Config.LOGTAG, "oEmbed failed: " + e.getMessage());
            return null;
        }
        final String title = clean(json.optString("title", null));
        if (title == null) {
            return null;
        }
        final String thumbnail = json.optString("thumbnail_url", null);
        final HttpUrl imageUrl = thumbnail == null ? null : HttpUrl.parse(thumbnail);
        // the website line names the link itself, never the provider's own words
        return new Preview(url, shorten(title), siteName(HttpUrl.get(url)), loadImage(imageUrl));
    }

    @Nullable
    private static Bitmap fetchImage(final HttpUrl url) throws IOException {
        final Request request = new Request.Builder().url(url).build();
        final byte[] bytes;
        try (Response response = CLIENT.newCall(request).execute()) {
            final ResponseBody body = response.body();
            final MediaType type = body.contentType();
            if (!response.isSuccessful() || type == null || !type.type().equals("image")) {
                return null;
            }
            if (body.contentLength() > MAX_IMAGE_BYTES) {
                return null;
            }
            bytes = readAtMost(body.byteStream(), MAX_IMAGE_BYTES + 1);
            if (bytes.length > MAX_IMAGE_BYTES) {
                return null;
            }
        }
        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        final boolean wideSource = bounds.outWidth >= bounds.outHeight * WIDE_MIN_RATIO;
        int sample = 1;
        if (wideSource) {
            while (bounds.outWidth / (sample * 2) >= WIDE_PX) {
                sample *= 2;
            }
        } else {
            while (Math.min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMB_PX) {
                sample *= 2;
            }
        }
        // a long, narrow picture (a few KB can claim 383 x 1,000,000 pixels) would still be
        // huge: shrink it further until the decoded picture fits the budget
        final long width = bounds.outWidth;
        final long height = bounds.outHeight;
        while (((width + sample - 1) / sample) * ((height + sample - 1) / sample)
                > MAX_DECODED_PIXELS) {
            sample *= 2;
        }
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        final Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (decoded == null) {
            return null;
        }
        final int w = decoded.getWidth();
        final int h = decoded.getHeight();
        final boolean wide = w >= h * WIDE_MIN_RATIO;
        final int cropW;
        final int cropH;
        final int targetW;
        if (wide) {
            // keep the whole picture; only panoramas wider than 2:1 lose a little at the sides
            cropH = h;
            cropW = Math.min(w, Math.round(h * WIDE_MAX_RATIO));
            targetW = Math.min(cropW, WIDE_PX);
        } else {
            cropW = Math.min(w, h);
            cropH = cropW;
            targetW = Math.min(cropW, THUMB_PX);
        }
        final Bitmap cropped =
                Bitmap.createBitmap(decoded, (w - cropW) / 2, (h - cropH) / 2, cropW, cropH);
        final Bitmap thumb;
        if (targetW < cropW) {
            thumb =
                    Bitmap.createScaledBitmap(
                            cropped,
                            targetW,
                            Math.max(1, Math.round(cropH * (float) targetW / cropW)),
                            true);
            if (cropped != decoded && cropped != thumb) {
                cropped.recycle();
            }
        } else {
            thumb = cropped;
        }
        if (thumb != decoded) {
            decoded.recycle();
        }
        return thumb;
    }

    private static byte[] readAtMost(final InputStream in, final int max) throws IOException {
        return ByteStreams.toByteArray(ByteStreams.limit(in, max));
    }

    private static Charset charset(final MediaType type, final byte[] bytes) {
        final Charset fromHeader = type.charset(null);
        if (fromHeader != null) {
            return fromHeader;
        }
        final String head = new String(bytes, 0, Math.min(bytes.length, 2048), StandardCharsets.ISO_8859_1);
        final Matcher m = CHARSET.matcher(head);
        if (m.find()) {
            try {
                return Charset.forName(m.group(1));
            } catch (final IllegalArgumentException e) {
                // unknown charset name
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static Map<String, String> metaTags(final String html) {
        final Map<String, String> result = new HashMap<>();
        final Matcher tags = META.matcher(html);
        while (tags.find()) {
            String key = null;
            String content = null;
            final Matcher attrs = ATTR.matcher(tags.group());
            while (attrs.find()) {
                final String name = attrs.group(1).toLowerCase(Locale.ROOT);
                final String value =
                        attrs.group(2) != null
                                ? attrs.group(2)
                                : attrs.group(3) != null ? attrs.group(3) : attrs.group(4);
                if (name.equals("property") || name.equals("name")) {
                    key = value.toLowerCase(Locale.ROOT).trim();
                } else if (name.equals("content")) {
                    content = value;
                }
            }
            if (key != null && content != null && !result.containsKey(key)) {
                result.put(key, content);
            }
        }
        return result;
    }

    /** Text of the first {@code <title>} element, or null; a plain search, no regex. */
    @Nullable
    private static String pageTitle(final String html) {
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

    @Nullable
    private static String first(final String... values) {
        for (final String v : values) {
            if (!Strings.isNullOrEmpty(v) && !v.trim().isEmpty()) {
                return v;
            }
        }
        return null;
    }

    @Nullable
    private static String clean(@Nullable final String raw) {
        if (raw == null) {
            return null;
        }
        final String text =
                HtmlCompat.fromHtml(cut(raw, MAX_RAW_CHARS), HtmlCompat.FROM_HTML_MODE_LEGACY)
                        .toString()
                        .replaceAll("\\s+", " ")
                        .trim();
        return text.isEmpty() ? null : text;
    }
}
