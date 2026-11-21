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
import java.net.Proxy;
import java.net.ProxySelector;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    /** v4: the website line is always the host of the link itself. */
    private static final String CACHE_DIR = "chatrix_link_previews_v4";
    /** At most 30 MB and 2000 links on disk; the ones used longest ago go first. */
    private static final CacheLimit CACHE_LIMIT = new CacheLimit(30L * 1024 * 1024, 2000, 50);

    private static final String[] OLD_CACHE_DIRS = {
        "chatrix_link_previews", "chatrix_link_previews_v2", "chatrix_link_previews_v3"
    };

    private static final String NONE = "\u0000none";

    private static final Pattern CHARSET =
            Pattern.compile("charset\\s*=\\s*[\"']?([a-zA-Z0-9_-]+)", Pattern.CASE_INSENSITIVE);

    /** url -> preview, limited by picture size; {@link #NO_PREVIEW} marks links without one. */
    private static final LruCache<String, Preview> MEMORY =
            new LruCache<>(12 * 1024 * 1024) {
                @Override
                protected int sizeOf(final String key, final Preview value) {
                    return value.image() == null ? 512 : value.image().getByteCount();
                }
            };

    private static final Preview NO_PREVIEW = new Preview("", "", "", null);
    private static final AtomicBoolean OLD_CACHE_REMOVED = new AtomicBoolean(false);
    private static final AtomicBoolean CACHE_CLEARED = new AtomicBoolean(false);

    /** Bumped when previews are turned off: a fetch that started before must not store. */
    private static final AtomicInteger GENERATION = new AtomicInteger();

    /** Storing a preview and deleting the cache never overlap. */
    private static final Object DISK = new Object();
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
                    .socketFactory(new LinkPreviewRules.PublicOnlySockets())
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
                return url == null || LinkPreviewRules.looksLocal(url.host())
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
            GENERATION.incrementAndGet();
            MEMORY.evictAll();
            final File base = context.getCacheDir();
            EXECUTOR.execute(
                    () -> {
                        synchronized (DISK) {
                            deleteRecursively(new File(base, CACHE_DIR));
                            for (final String old : OLD_CACHE_DIRS) {
                                deleteRecursively(new File(base, old));
                            }
                        }
                    });
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
                bitmap != null && LinkPreviewRules.isWide(bitmap.getWidth(), bitmap.getHeight());
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
            callback.onPreview(cached == NO_PREVIEW ? null : cached);
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
        final int generation = GENERATION.get();
        EXECUTOR.execute(() -> {
            Preview preview = readDisk(cacheDir, url);
            if (preview == null) {
                preview = fetchAndStore(cacheDir, url, generation);
            }
            if (preview != null && generation == GENERATION.get()) {
                MEMORY.put(url, preview);
            }
            final Preview result = preview == NO_PREVIEW ? null : preview;
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

    /** Null if not on disk yet; {@link #NO_PREVIEW} if we already know there is no preview. */
    @Nullable
    private static Preview readDisk(final File dir, final String url) {
        final File text = new File(dir, key(url) + ".txt");
        if (!text.isFile()) {
            return null;
        }
        try {
            final String content = Files.asCharSource(text, StandardCharsets.UTF_8).read();
            CacheLimit.touch(text); // used now: removed last when the cache is full
            if (content.startsWith(NONE)) {
                return NO_PREVIEW;
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
    private static Preview fetchAndStore(final File dir, final String url, final int generation) {
        Preview preview;
        try {
            preview = fetch(url);
        } catch (final IOException e) {
            if (!Iterables.any(
                    Throwables.getCausalChain(e), LinkPreviewRules.NotPublic.class::isInstance)) {
                Log.d(Config.LOGTAG, "link preview failed: " + e.getMessage());
                return null;
            }
            preview = NO_PREVIEW; // leads to an address that is not public: never again
        } catch (final RuntimeException | OutOfMemoryError | StackOverflowError e) {
            if (BuildConfig.DEBUG) {
                throw e; // most likely our own bug: crash loudly while developing
            }
            // a broken or hostile page must never take the app down: no preview for this link
            Log.w(Config.LOGTAG, "link preview failed", e);
            preview = NO_PREVIEW;
        }
        synchronized (DISK) {
            if (generation != GENERATION.get()) {
                return preview; // previews were turned off meanwhile: store nothing
            }
            store(dir, url, preview);
            CACHE_LIMIT.afterWrite(dir);
        }
        return preview;
    }

    private static void store(final File dir, final String url, final Preview preview) {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return;
        }
        final String k = key(url);
        try {
            if (preview == NO_PREVIEW) {
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
                return NO_PREVIEW;
            }
            finalUrl = response.request().url();
            final byte[] bytes = readAtMost(body.byteStream(), MAX_HTML_BYTES);
            html = new String(bytes, charset(type, bytes));
        }
        final Map<String, String> meta = LinkPreviewRules.metaTags(html);
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
            title = LinkPreviewRules.pageTitle(html);
        }
        title = clean(title);
        if (title == null || title.startsWith("- ")) {
            return NO_PREVIEW;
        }
        final String imageRef =
                first(meta.get("og:image"), meta.get("og:image:url"), meta.get("twitter:image"));
        final HttpUrl imageUrl =
                imageRef == null
                        ? null
                        : finalUrl.resolve(HtmlCompat.fromHtml(imageRef, 0).toString().trim());
        // the website line names the link a tap opens, not where the page redirected us
        return new Preview(
                url, LinkPreviewRules.shorten(title), siteName(original), loadImage(imageUrl));
    }

    private static String siteName(final HttpUrl url) {
        final String host = url.host();
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    @Nullable
    private static Bitmap loadImage(@Nullable final HttpUrl imageUrl) {
        if (imageUrl == null
                || !imageUrl.isHttps()
                || LinkPreviewRules.looksLocal(imageUrl.host())) {
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
        for (final String href : LinkPreviewRules.oEmbedLinks(html)) {
            final HttpUrl url = base.resolve(HtmlCompat.fromHtml(href, 0).toString().trim());
            if (url != null && url.isHttps() && !LinkPreviewRules.looksLocal(url.host())) {
                return url;
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
        return new Preview(
                url,
                LinkPreviewRules.shorten(title),
                siteName(HttpUrl.get(url)),
                loadImage(imageUrl));
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
        final int sample = LinkPreviewRules.sampleSize(bounds.outWidth, bounds.outHeight);
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        final Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (decoded == null) {
            return null;
        }
        final int w = decoded.getWidth();
        final int h = decoded.getHeight();
        final boolean wide = LinkPreviewRules.isWide(w, h);
        final int cropW;
        final int cropH;
        final int targetW;
        if (wide) {
            // keep the whole picture; only panoramas wider than 2:1 lose a little at the sides
            cropH = h;
            cropW = Math.min(w, Math.round(h * LinkPreviewRules.WIDE_MAX_RATIO));
            targetW = Math.min(cropW, LinkPreviewRules.WIDE_PX);
        } else {
            cropW = Math.min(w, h);
            cropH = cropW;
            targetW = Math.min(cropW, LinkPreviewRules.THUMB_PX);
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
                HtmlCompat.fromHtml(
                                LinkPreviewRules.cut(raw, LinkPreviewRules.MAX_RAW_CHARS),
                                HtmlCompat.FROM_HTML_MODE_LEGACY)
                        .toString()
                        .replaceAll("\\s+", " ")
                        .trim();
        return text.isEmpty() ? null : text;
    }
}
