package de.gultsch.common;

import com.google.common.base.Joiner;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import eu.siacs.conversations.BuildConfig;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public class Patterns {

    public static final Pattern UUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    private static final List<String> PUBLIC_URI_SCHEMES =
            Arrays.asList(
                    "tel", "xmpp", "http", "https", "geo", "mailto", "web+ap", "gemini", "magnet",
                    "taler", "mumble");
    private static final List<String> INTERNAL_URI_SCHEMES =
            new ImmutableList.Builder<String>()
                    .addAll(PUBLIC_URI_SCHEMES)
                    .add(BuildConfig.APPLICATION_ID)
                    .add("imto")
                    .add("cid")
                    .build();

    public static final Pattern URI_GENERIC = genericUri(PUBLIC_URI_SCHEMES);
    public static final Pattern URIS_GENERIC_IN_TEXT = genericUri(PUBLIC_URI_SCHEMES, true);
    public static final Pattern URI_INTERNAL = genericUri(INTERNAL_URI_SCHEMES);
    public static final Pattern URI_TEL =
            Pattern.compile("^tel:\\+?(\\d{1,4}[-./()\\s]?)*\\d{1,4}(;.*)?$");

    public static final Pattern URI_HTTP = Pattern.compile("https?://\\S+");
    public static final Pattern URI_MUMBLE = Pattern.compile("mumble://\\S+");
    public static final Pattern URI_WEB_AP = Pattern.compile("web\\+ap://\\S+");
    public static final Pattern URI_GEO = genericUri(ImmutableList.of("geo"));
    public static final Pattern URI_TALER = Pattern.compile("taler://\\S+");

    private static Pattern genericUri(final List<String> schemes) {
        return genericUri(schemes, false);
    }

    private static Pattern genericUri(
            final List<String> schemes, final boolean negativeLookbehind) {
        return Pattern.compile(
                "(?<=^|\\p{Z}|\\s|\\p{P}|<)("
                        + Joiner.on('|')
                                .join(
                                        Collections2.transform(
                                                schemes,
                                                s -> {
                                                    assert s != null;
                                                    return Pattern.quote(s);
                                                }))
                        + "):[\\p{L}\\p{M}\\p{N}\\-._~:/?#\\[\\]@!$&'*+,;=%]+(\\([\\p{L}\\p{M}\\p{N}\\-._~:/?#\\[\\]@!$&'*+,;=%]+\\))*[\\p{L}\\p{M}\\p{N}\\-._~:/?#\\[\\]@!$&'*+,;=%]*"
                        + (negativeLookbehind ? "(?<![.,?!])" : ""));
    }

    private Patterns() {
        throw new AssertionError("Do not instantiate me");
    }
}
