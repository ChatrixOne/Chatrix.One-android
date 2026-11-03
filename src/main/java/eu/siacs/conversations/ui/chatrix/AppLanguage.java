package eu.siacs.conversations.ui.chatrix;

import android.content.Context;
import android.content.res.XmlResourceParser;
import android.util.Log;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.xmlpull.v1.XmlPullParser;

/** Chatrix.One: in-app language choice from every translation the app ships. */
public final class AppLanguage {

    /** Follow the language of the phone or tablet. */
    public static final String DEVICE = "";

    private AppLanguage() {}

    /** Language tags listed in res/xml/locales_config.xml. */
    public static List<String> available(final Context context) {
        final List<String> tags = new ArrayList<>();
        try (final XmlResourceParser parser = context.getResources().getXml(R.xml.locales_config)) {
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "locale".equals(parser.getName())) {
                    final String name =
                            parser.getAttributeValue(
                                    "http://schemas.android.com/apk/res/android", "name");
                    if (name != null && !name.isEmpty()) {
                        tags.add(name);
                    }
                }
                event = parser.next();
            }
        } catch (final Exception e) {
            Log.d(Config.LOGTAG, "could not read locales_config", e);
        }
        return tags;
    }

    /** The available tag chosen in the app, or {@link #DEVICE}. */
    public static String chosen(final Context context) {
        final LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
        final Locale current = locales.isEmpty() ? null : locales.get(0);
        if (current == null) {
            return DEVICE;
        }
        final List<String> tags = available(context);
        for (final String tag : tags) {
            if (Locale.forLanguageTag(tag).equals(current)) {
                return tag;
            }
        }
        for (final String tag : tags) {
            if (Locale.forLanguageTag(tag).getLanguage().equals(current.getLanguage())) {
                return tag;
            }
        }
        return DEVICE;
    }

    public static void set(final Context context, final String tag) {
        if (tag.equals(chosen(context))) {
            return;
        }
        AppCompatDelegate.setApplicationLocales(
                tag.isEmpty()
                        ? LocaleListCompat.getEmptyLocaleList()
                        : LocaleListCompat.forLanguageTags(tag));
    }

    /** Values for a list: {@link #DEVICE} first, then all languages sorted by their own name. */
    public static List<String> values(final Context context) {
        final List<String> tags = available(context);
        final Map<String, String> names = names(tags);
        final Collator collator = Collator.getInstance();
        tags.sort(Comparator.comparing(names::get, collator));
        tags.add(0, DEVICE);
        return tags;
    }

    /** Names for {@link #values(Context)}, each language written in that language. */
    public static CharSequence[] entries(final Context context, final List<String> values) {
        final Map<String, String> names = names(available(context));
        final CharSequence[] entries = new CharSequence[values.size()];
        for (int i = 0; i < values.size(); ++i) {
            final String tag = values.get(i);
            entries[i] =
                    tag.isEmpty()
                            ? context.getString(R.string.chatrix_language_device)
                            : names.get(tag);
        }
        return entries;
    }

    /** Label for a button: the chosen language, or "Device language". */
    public static String label(final Context context) {
        final String tag = chosen(context);
        return tag.isEmpty()
                ? context.getString(R.string.chatrix_language_device)
                : names(available(context)).get(tag);
    }

    /** Shows the language list in a Material dialog and applies the choice. */
    public static void showPicker(final Context context) {
        final List<String> values = values(context);
        final int checked = Math.max(0, values.indexOf(chosen(context)));
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.chatrix_pref_language)
                .setSingleChoiceItems(
                        entries(context, values),
                        checked,
                        (dialog, which) -> {
                            dialog.dismiss();
                            set(context, values.get(which));
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static Map<String, String> names(final List<String> tags) {
        final Map<String, Integer> perLanguage = new HashMap<>();
        for (final String tag : tags) {
            perLanguage.merge(Locale.forLanguageTag(tag).getLanguage(), 1, Integer::sum);
        }
        final Map<String, String> names = new HashMap<>();
        for (final String tag : tags) {
            final Locale locale = Locale.forLanguageTag(tag);
            // show the country only where two variants of one language exist (pt / pt-BR)
            final boolean ambiguous = perLanguage.getOrDefault(locale.getLanguage(), 0) > 1;
            String name =
                    ambiguous ? locale.getDisplayName(locale) : locale.getDisplayLanguage(locale);
            if (name == null || name.isEmpty() || name.equalsIgnoreCase(locale.getLanguage())) {
                name = tag;
            }
            names.put(tag, name.substring(0, 1).toUpperCase(locale) + name.substring(1));
        }
        return names;
    }
}
