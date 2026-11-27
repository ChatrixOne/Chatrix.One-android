package eu.siacs.conversations.ui.chatrix;

import android.content.SharedPreferences;
import eu.siacs.conversations.AppSettings;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;

/**
 * Chatrix.One: the one-time Google push "disable" only reaches the addresses that were on the
 * phone at the first check (ultrareview 2026-11-27: the gate never closed, so every account added
 * later got it and lost the push registrations of its other phones).
 */
public class GooglePushGateTest {

    private static final String A = "alice@example.org";
    private static final String B = "bob@example.net";
    private static final String NEW = "new@example.com";

    /** SharedPreferences in memory, enough for the gate. */
    private static final class Prefs implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return values;
        }

        @Override
        public String getString(final String key, final String defValue) {
            return (String) values.getOrDefault(key, defValue);
        }

        @SuppressWarnings("unchecked")
        @Override
        public Set<String> getStringSet(final String key, final Set<String> defValues) {
            final Object v = values.get(key);
            return v == null ? defValues : new HashSet<>((Set<String>) v);
        }

        @Override
        public int getInt(final String key, final int defValue) {
            return (int) values.getOrDefault(key, defValue);
        }

        @Override
        public long getLong(final String key, final long defValue) {
            return (long) values.getOrDefault(key, defValue);
        }

        @Override
        public float getFloat(final String key, final float defValue) {
            return (float) values.getOrDefault(key, defValue);
        }

        @Override
        public boolean getBoolean(final String key, final boolean defValue) {
            return (boolean) values.getOrDefault(key, defValue);
        }

        @Override
        public boolean contains(final String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            final Map<String, Object> changes = new HashMap<>();
            return new Editor() {
                @Override
                public Editor putString(final String key, final String value) {
                    changes.put(key, value);
                    return this;
                }

                @Override
                public Editor putStringSet(final String key, final Set<String> value) {
                    changes.put(key, new HashSet<>(value));
                    return this;
                }

                @Override
                public Editor putInt(final String key, final int value) {
                    changes.put(key, value);
                    return this;
                }

                @Override
                public Editor putLong(final String key, final long value) {
                    changes.put(key, value);
                    return this;
                }

                @Override
                public Editor putFloat(final String key, final float value) {
                    changes.put(key, value);
                    return this;
                }

                @Override
                public Editor putBoolean(final String key, final boolean value) {
                    changes.put(key, value);
                    return this;
                }

                @Override
                public Editor remove(final String key) {
                    changes.put(key, null);
                    return this;
                }

                @Override
                public Editor clear() {
                    values.clear();
                    return this;
                }

                @Override
                public boolean commit() {
                    apply();
                    return true;
                }

                @Override
                public void apply() {
                    changes.forEach(
                            (k, v) -> {
                                if (v == null) {
                                    values.remove(k);
                                } else {
                                    values.put(k, v);
                                }
                            });
                }
            };
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(
                final OnSharedPreferenceChangeListener listener) {}

        @Override
        public void unregisterOnSharedPreferenceChangeListener(
                final OnSharedPreferenceChangeListener listener) {}
    }

    private static Prefs switchedOn() {
        final Prefs prefs = new Prefs();
        prefs.edit().putBoolean(AppSettings.GOOGLE_PUSH, true).apply();
        return prefs;
    }

    private static boolean needed(final Prefs prefs, final String id, final String jid) {
        return GooglePushGate.needed(prefs, id, jid, () -> List.of(A, B));
    }

    @Test
    public void neverSwitchedOnNeverAsked() {
        final Prefs prefs = new Prefs();
        Assert.assertFalse(needed(prefs, "id-a", A));
        Assert.assertTrue("nothing stored", prefs.values.isEmpty());
    }

    @Test
    public void anAccountAddedLaterNeverGetsTheRequest() {
        final Prefs prefs = switchedOn();
        Assert.assertTrue(needed(prefs, "id-a", A)); // the first check: A and B are on the phone
        Assert.assertFalse("a new address", needed(prefs, "id-new", NEW));
    }

    @Test
    public void eachAddressOnceAndThenTheGateCloses() {
        final Prefs prefs = switchedOn();
        Assert.assertTrue(needed(prefs, "id-a", A));
        GooglePushGate.markDone(prefs, "id-a", A);
        Assert.assertFalse(needed(prefs, "id-a", A));
        Assert.assertTrue(
                "google_push stays on until all are done",
                prefs.getBoolean(AppSettings.GOOGLE_PUSH, false));
        Assert.assertTrue(needed(prefs, "id-b", B));
        GooglePushGate.markDone(prefs, "id-b", B);
        Assert.assertFalse("closed", prefs.getBoolean(AppSettings.GOOGLE_PUSH, true));
        Assert.assertFalse(needed(prefs, "id-new", NEW));
    }

    @Test
    public void aReAddedAccountKeepsItsAddress() {
        final Prefs prefs = switchedOn();
        Assert.assertTrue(needed(prefs, "id-a", A));
        // deleted before its login finished, added again: new id, same address
        Assert.assertTrue(needed(prefs, "id-a-again", "Alice@Example.org"));
        GooglePushGate.markDone(prefs, "id-a-again", A);
        Assert.assertFalse("done by address", needed(prefs, "id-a", A));
    }

    @Test
    public void accountsDoneByEarlierVersionsCountAsDone() {
        final Prefs prefs = switchedOn();
        prefs.edit().putBoolean(GooglePushGate.DONE_ID_PREFIX + "id-a", true).apply(); // cx1.1
        Assert.assertFalse(needed(prefs, "id-a", A));
        Assert.assertTrue(needed(prefs, "id-b", B));
        GooglePushGate.markDone(prefs, "id-b", B);
        Assert.assertFalse(
                "closed after the last one", prefs.getBoolean(AppSettings.GOOGLE_PUSH, true));
    }

    @Test
    public void theListIsTakenOnceAndALoginInTheMiddleOfAChangeWaits() {
        final Prefs prefs = switchedOn();
        Assert.assertFalse(
                "account list unreadable: decide later",
                GooglePushGate.needed(prefs, "id-a", A, () -> null));
        Assert.assertFalse(prefs.contains(GooglePushGate.ADDRESSES));
        final AtomicInteger asked = new AtomicInteger();
        GooglePushGate.needed(
                prefs,
                "id-a",
                A,
                () -> {
                    asked.incrementAndGet();
                    return List.of(A, B);
                });
        GooglePushGate.needed(
                prefs,
                "id-new",
                NEW,
                () -> {
                    asked.incrementAndGet();
                    return List.of(A, B, NEW); // added later: must not count
                });
        Assert.assertEquals(1, asked.get());
        Assert.assertFalse(needed(prefs, "id-new", NEW));
    }

    /**
     * Random logins, additions and answers: a request only ever goes to an address from the first
     * list, at most until it is done, and never once the gate has closed.
     */
    @Test
    public void randomLoginsKeepEveryRule() {
        final Random random = new Random(5);
        for (int round = 0; round < 2_000; round++) {
            final Prefs prefs = switchedOn();
            final List<String> phone = new ArrayList<>();
            final int start = 1 + random.nextInt(4);
            for (int i = 0; i < start; i++) {
                phone.add("user" + i + "@example.org");
            }
            Set<String> firstList = null;
            final Set<String> done = new HashSet<>();
            for (int step = 0; step < 40; step++) {
                if (random.nextInt(5) == 0) {
                    phone.add("later" + step + "@example.org"); // an account added later
                }
                final String jid = phone.get(random.nextInt(phone.size()));
                final List<String> now = List.copyOf(phone);
                final boolean asked = GooglePushGate.needed(prefs, "id-" + jid, jid, () -> now);
                if (firstList == null) {
                    firstList = new HashSet<>(now);
                    firstList.add(jid);
                }
                if (asked) {
                    Assert.assertTrue(
                            "only addresses of the first list", firstList.contains(jid));
                    Assert.assertFalse("only until done", done.contains(jid));
                    if (random.nextBoolean()) {
                        GooglePushGate.markDone(prefs, "id-" + jid, jid);
                        done.add(jid);
                    }
                }
                if (done.containsAll(firstList)) {
                    Assert.assertFalse(prefs.getBoolean(AppSettings.GOOGLE_PUSH, true));
                }
            }
        }
    }

    /** GooglePushOff asks the gate with the account list from the login. */
    @Test
    public void theLoginUsesTheGate() throws IOException {
        final String off =
                read("src/main/java/eu/siacs/conversations/ui/chatrix/GooglePushOff.java");
        Assert.assertTrue(off.contains("GooglePushGate.needed(prefs, account.getUuid(), jid,"));
        Assert.assertEquals(
                3, count(off, "GooglePushGate.markDone(prefs, account.getUuid(), jid);"));
        Assert.assertFalse("no gate by account id alone", off.contains("DONE_PREFIX"));
        final String bind =
                read("src/main/java/im/conversations/android/xmpp/processor/BindProcessor.java");
        Assert.assertTrue(
                bind.contains(
                        "GooglePushOff.onBind(\n"
                                + "                context, connection, account,"
                                + " service.getAccounts());"));
    }

    private static int count(final String text, final String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
            n++;
        }
        return n;
    }

    private static String read(final String path) throws IOException {
        final File file = new File(System.getProperty("user.dir"), path);
        Assert.assertTrue("source not found at " + file, file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
