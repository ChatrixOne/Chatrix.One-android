package eu.siacs.conversations.ui.chatrix;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import java.util.Locale;

/** Chatrix.One: in-app language choice (English / Bulgarian / system default). */
public final class AppLanguage {

    public static final String SYSTEM = "";
    public static final String ENGLISH = "en";
    public static final String BULGARIAN = "bg";

    private AppLanguage() {}

    /** The language tag chosen in the app, or {@link #SYSTEM} when following the phone. */
    public static String chosen() {
        final LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
        if (locales.isEmpty() || locales.get(0) == null) {
            return SYSTEM;
        }
        return locales.get(0).getLanguage();
    }

    /** The language the app is actually shown in: Bulgarian or (everything else) English. */
    public static String effective() {
        final String chosen = chosen();
        final String language = chosen.isEmpty() ? Locale.getDefault().getLanguage() : chosen;
        return BULGARIAN.equals(language) ? BULGARIAN : ENGLISH;
    }

    /** Switches the app language; the visible activities are recreated by AppCompat. */
    public static void set(final String tag) {
        if (tag.equals(chosen())) {
            return;
        }
        AppCompatDelegate.setApplicationLocales(
                tag.isEmpty()
                        ? LocaleListCompat.getEmptyLocaleList()
                        : LocaleListCompat.forLanguageTags(tag));
    }
}
