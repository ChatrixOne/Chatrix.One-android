package eu.siacs.conversations.ui.chatrix;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.core.text.HtmlCompat;
import com.google.common.base.Strings;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import de.gultsch.common.Linkify;
import de.gultsch.common.MiniUri;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.http.HttpConnectionManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
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
 * the contact list, also in group chats; never with Tor. Only the first web link of a message.
 * The page is read once (at most {@link #MAX_HTML_BYTES}), the result is kept in memory and in
 * the private app cache, so each link is fetched only once.
 */
public final class LinkPreviews {

    public record Preview(String url, String title, String site, @Nullable Bitmap image) {}

    private interface Callback {
        void onPreview(@Nullable Preview preview);
    }

    private static final int MAX_HTML_BYTES = 256 * 1024;
    private static final int MAX_IMAGE_BYTES = 3 * 1024 * 1024;
    private static final int MAX_OEMBED_BYTES = 64 * 1024;
    private static final int THUMB_PX = 192;

    /** Pictures at least this much wider than high get the wide box (16:9) on the card. */
    private static final float WIDE_MIN_RATIO = 1.3f;
    private static final int MAX_TITLE_CHARS = 200;

    /** v2: wide pictures are stored 16:9 and YouTube/Vimeo use oEmbed. */
    private static final String CACHE_DIR = "chatrix_link_previews_v2";

    private static final String OLD_CACHE_DIR = "chatrix_link_previews";

    private static final String NONE = "\u0000none";

    private static final Pattern META = Pattern.compile("<meta\\s[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR =
            Pattern.compile(
                    "([a-zA-Z_:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))");
    private static final Pattern LINK = Pattern.compile("<link\\s[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE =
            Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CHARSET =
            Pattern.compile("charset\\s*=\\s*[\"']?([a-zA-Z0-9_-]+)", Pattern.CASE_INSENSITIVE);

    /** url -> preview; {@link #MISSING} marks links without a preview (this session only). */
    private static final LruCache<String, Preview> MEMORY = new LruCache<>(128);

    private static final Preview MISSING = new Preview("", "", "", null);
    private static final AtomicBoolean OLD_CACHE_REMOVED = new AtomicBoolean(false);
    private static final Map<String, List<Callback>> PENDING = new HashMap<>();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final OkHttpClient CLIENT =
            HttpConnectionManager.OK_HTTP_CLIENT
                    .newBuilder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .callTimeout(20, TimeUnit.SECONDS)
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
            final View card, final Message message, final @ColorInt int textColor) {
        hide(card);
        final Context context = card.getContext();
        final AppSettings settings = new AppSettings(context);
        if (!settings.isLinkPreviews() || settings.isUseTor()) {
            return;
        }
        final String url = eligibleUrl(message);
        if (url == null) {
            return;
        }
        card.setTag(url);
        final File cacheDir = new File(context.getCacheDir(), CACHE_DIR);
        if (OLD_CACHE_REMOVED.compareAndSet(false, true)) {
            final File old = new File(context.getCacheDir(), OLD_CACHE_DIR);
            EXECUTOR.execute(() -> deleteRecursively(old));
        }
        get(cacheDir, url, preview -> {
            if (preview != null && url.equals(card.getTag())) {
                show(card, preview, textColor);
            }
        });
    }

    @Nullable
    private static String eligibleUrl(final Message message) {
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
            if (scheme.equals("http") || scheme.equals("https")) {
                final HttpUrl url = HttpUrl.parse(link.getRaw());
                return url == null || isLocal(url.host()) ? null : url.toString();
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

    /** Never reach into the user's own network (router pages, printers, ...). */
    private static boolean isLocal(final String host) {
        final String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.endsWith(".local") || h.endsWith(".lan") || !h.contains(".") && !h.contains(":")) {
            return true;
        }
        if (!h.matches("[0-9.]+") && !h.contains(":")) {
            return false; // a name, not an address literal
        }
        try {
            final InetAddress address = InetAddress.getByName(h); // literal: no DNS lookup
            return address.isLoopbackAddress()
                    || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress()
                    || address.isAnyLocalAddress()
                    || h.startsWith("fc")
                    || h.startsWith("fd");
        } catch (final IOException e) {
            return true;
        }
    }

    private static void show(final View card, final Preview preview, final int textColor) {
        final TextView title = card.findViewById(R.id.link_preview_title);
        final TextView site = card.findViewById(R.id.link_preview_site);
        final ImageView image = card.findViewById(R.id.link_preview_image);
        title.setText(preview.title());
        title.setTextColor(textColor);
        site.setText(preview.site());
        site.setTextColor(textColor);
        if (preview.image() != null) {
            // square box for square pictures, wide 16:9 box for banners and videos
            final Bitmap bitmap = preview.image();
            final float density = card.getResources().getDisplayMetrics().density;
            final boolean wide = bitmap.getWidth() >= bitmap.getHeight() * WIDE_MIN_RATIO;
            final var params = image.getLayoutParams();
            params.width = Math.round((wide ? 114 : 64) * density);
            image.setLayoutParams(params);
            image.setImageBitmap(bitmap);
            image.setVisibility(View.VISIBLE);
        } else {
            image.setImageDrawable(null);
            image.setVisibility(View.GONE);
        }
        card.setBackgroundTintList(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(textColor, 0x24)));
        card.setOnClickListener(v -> open(v.getContext(), preview.url()));
        card.setVisibility(View.VISIBLE);
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
            final Bitmap bitmap = img.isFile() ? BitmapFactory.decodeFile(img.getAbsolutePath()) : null;
            return new Preview(url, content.substring(nl + 1), content.substring(0, nl), bitmap);
        } catch (final IOException e) {
            return null;
        }
    }

    /** Null on a network error (offline, timeout): not cached, so it is tried again later. */
    @Nullable
    private static Preview fetchAndStore(final File dir, final String url) {
        final Preview preview;
        try {
            preview = fetch(url);
        } catch (final IOException e) {
            Log.d(Config.LOGTAG, "link preview failed: " + e.getMessage());
            return null;
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
            final Matcher m = TITLE.matcher(html);
            title = m.find() ? m.group(1) : null;
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
        return new Preview(url, shorten(title), siteName(finalUrl), loadImage(imageUrl));
    }

    private static String siteName(final HttpUrl url) {
        final String host = url.host();
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    private static String shorten(final String title) {
        return title.length() > MAX_TITLE_CHARS
                ? title.substring(0, MAX_TITLE_CHARS) + "…"
                : title;
    }

    @Nullable
    private static Bitmap loadImage(@Nullable final HttpUrl imageUrl) {
        if (imageUrl == null || isLocal(imageUrl.host())) {
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
                if (url != null && !isLocal(url.host())) {
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
        final String author = clean(json.optString("author_name", null));
        final String provider = clean(json.optString("provider_name", null));
        final String site;
        if (author != null && provider != null) {
            site = author + " · " + provider;
        } else if (provider != null) {
            site = provider;
        } else {
            site = siteName(endpoint);
        }
        final String thumbnail = json.optString("thumbnail_url", null);
        final HttpUrl imageUrl = thumbnail == null ? null : HttpUrl.parse(thumbnail);
        return new Preview(url, shorten(title), site, loadImage(imageUrl));
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
        int sample = 1;
        while (Math.min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMB_PX) {
            sample *= 2;
        }
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        final Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (decoded == null) {
            return null;
        }
        // Centre crop to what the card shows: wide pictures (website banners, videos) get the
        // wide 16:9 box, everything else the square one.
        final int w = decoded.getWidth();
        final int h = decoded.getHeight();
        final boolean wide = w >= h * WIDE_MIN_RATIO;
        final int cropW;
        final int cropH;
        if (wide) {
            cropH = Math.min(h, Math.round(w * 9f / 16f));
            cropW = Math.min(w, Math.round(cropH * 16f / 9f));
        } else {
            cropW = Math.min(w, h);
            cropH = cropW;
        }
        final Bitmap cropped =
                Bitmap.createBitmap(decoded, (w - cropW) / 2, (h - cropH) / 2, cropW, cropH);
        final Bitmap thumb;
        if (cropH > THUMB_PX) {
            thumb =
                    Bitmap.createScaledBitmap(
                            cropped, Math.round(cropW * (float) THUMB_PX / cropH), THUMB_PX, true);
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
                HtmlCompat.fromHtml(raw, HtmlCompat.FROM_HTML_MODE_LEGACY)
                        .toString()
                        .replaceAll("\\s+", " ")
                        .trim();
        return text.isEmpty() ? null : text;
    }
}
