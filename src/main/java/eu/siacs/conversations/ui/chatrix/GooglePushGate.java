package eu.siacs.conversations.ui.chatrix;

import android.content.SharedPreferences;
import eu.siacs.conversations.AppSettings;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Chatrix.One: which accounts still get the one-time Google push "disable" of {@link
 * GooglePushOff}, decided by address, not by the app's account id (ultrareview 2026-11-27: the
 * gate never closed, so accounts added later got the request too and lost the push registrations
 * of their other phones).
 *
 * <ul>
 *   <li>Only someone who switched Google push on in 2.21.4-cx1.1 ({@code google_push} true).
 *   <li>Only the addresses on this phone at the first check: those could have registered. A
 *       re-added account keeps its address (the server still holds its registration), a new one
 *       is never on the list.
 *   <li>Once per address. When all are done, {@code google_push} goes back to false: closed.
 * </ul>
 */
public final class GooglePushGate {

    /** Per account id: what 2.21.5-cx1.1 and cx1.2 stored; still honoured. */
    static final String DONE_ID_PREFIX = "chatrix_google_push_off_";

    static final String ADDRESSES = "chatrix_google_push_gate_addresses";
    static final String DONE = "chatrix_google_push_gate_done";

    private GooglePushGate() {}

    /** Bare JID as stored here: lower case, so "Me@Example.org" and "me@example.org" match. */
    public static String address(final String bareJid) {
        return bareJid.toLowerCase(Locale.ROOT);
    }

    /**
     * True if this account still gets the request. {@code addressesNow} (bare JIDs of all
     * accounts on this phone) is only asked once, at the very first check; if it gives null,
     * nothing is decided yet and the next login asks again.
     */
    public static synchronized boolean needed(
            final SharedPreferences prefs,
            final String accountId,
            final String bareJid,
            final Supplier<Collection<String>> addressesNow) {
        if (!prefs.getBoolean(AppSettings.GOOGLE_PUSH, false)) {
            return false;
        }
        final String address = address(bareJid);
        Set<String> addresses = prefs.getStringSet(ADDRESSES, null);
        if (addresses == null) {
            final Collection<String> now = addressesNow.get();
            if (now == null) {
                return false;
            }
            addresses = new HashSet<>();
            for (final String jid : now) {
                addresses.add(address(jid));
            }
            addresses.add(address); // the account that is logging in right now
            prefs.edit().putStringSet(ADDRESSES, addresses).apply();
        }
        if (prefs.getBoolean(DONE_ID_PREFIX + accountId, false)) {
            // done by 2.21.5-cx1.1 or cx1.2: count it, so the gate can close
            if (!prefs.getStringSet(DONE, Set.of()).contains(address)) {
                markDone(prefs, accountId, bareJid);
            }
            return false;
        }
        return addresses.contains(address)
                && !prefs.getStringSet(DONE, Set.of()).contains(address);
    }

    /** The request was answered for this account; closes the gate after the last address. */
    public static synchronized void markDone(
            final SharedPreferences prefs, final String accountId, final String bareJid) {
        final Set<String> done = new HashSet<>(prefs.getStringSet(DONE, Set.of()));
        done.add(address(bareJid));
        final var edit =
                prefs.edit()
                        .putBoolean(DONE_ID_PREFIX + accountId, true)
                        .putStringSet(DONE, done);
        final Set<String> addresses = prefs.getStringSet(ADDRESSES, null);
        if (addresses != null && done.containsAll(addresses)) {
            edit.putBoolean(AppSettings.GOOGLE_PUSH, false);
        }
        edit.apply();
    }
}
