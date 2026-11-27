package eu.siacs.conversations.ui.chatrix;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Chatrix.One: reaction notifications come in bursts (ultrareview 2026-11-26: after a catch-up
 * every reaction posted its own notification update with a sound, 10 or 20 sounds in a row, and
 * Android drops notification updates beyond about 5 per second).
 *
 * <ul>
 *   <li>A chat's notification is posted once its reactions pause for {@link #QUIET_MS}, at the
 *       latest {@link #MAX_WAIT_MS} after the first one, and never sooner than {@link #SPACING_MS}
 *       after the last post of any chat (each post updates the chat and the group summary: at
 *       most 4 updates a second).
 *   <li>Only a burst makes a sound: a reaction that comes within {@link #SOUND_GAP_MS} of the
 *       previous one (any chat) stays silent, and two sounds are never closer than that. Every
 *       notification still shows.
 * </ul>
 *
 * <p>This needs no word from the catch-up code, so live bursts are covered too. The caller keeps
 * exactly one scheduled {@link #check} per pending chat: {@link #add} says when to start one, and
 * a check that is not due yet says when to run again. Times come from a monotonic clock, in ms.
 * Thread safe.
 */
public final class ReactionBurst {

    public static final long QUIET_MS = 1_000;
    public static final long MAX_WAIT_MS = 5_000;
    public static final long SPACING_MS = 500;
    public static final long SOUND_GAP_MS = 15_000;

    private static final ScheduledExecutorService TIMER =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        final Thread t = new Thread(r, "chatrix-reactions");
                        t.setDaemon(true);
                        return t;
                    });

    /** Runs a check later, on one background thread (so checks never run at the same time). */
    public static void later(final Runnable check, final long delayMs) {
        TIMER.schedule(check, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * What a scheduled check should do: post now (waitMs 0, with or without a sound), or run
     * again after waitMs.
     */
    public record Check(long waitMs, boolean sound) {
        public boolean postNow() {
            return waitMs == 0;
        }
    }

    private static final class Pending {
        final long first;
        long last;
        boolean burstStart;

        Pending(final long now, final boolean burstStart) {
            this.first = now;
            this.last = now;
            this.burstStart = burstStart;
        }
    }

    private final LongSupplier clock;
    private final Map<String, Pending> pending = new HashMap<>();
    private long lastReaction;
    private long lastPost;
    private long lastSound;

    public ReactionBurst(final LongSupplier clock) {
        this.clock = clock;
        final long now = clock.getAsLong();
        this.lastReaction = now - SOUND_GAP_MS;
        this.lastPost = now - SPACING_MS;
        this.lastSound = now - SOUND_GAP_MS;
    }

    /**
     * A reaction in this chat is to be shown. Returns true if no check is pending for the chat:
     * the caller then schedules one in {@link #QUIET_MS}.
     */
    public synchronized boolean add(final String chat) {
        final long now = clock.getAsLong();
        final boolean burstStart = now - lastReaction >= SOUND_GAP_MS;
        lastReaction = now;
        final Pending p = pending.get(chat);
        if (p != null) {
            p.last = now;
            p.burstStart |= burstStart;
            return false;
        }
        pending.put(chat, new Pending(now, burstStart));
        return true;
    }

    /** The scheduled check for a chat. Null: nothing to post (already posted or forgotten). */
    public synchronized Check check(final String chat) {
        final Pending p = pending.get(chat);
        if (p == null) {
            return null;
        }
        final long now = clock.getAsLong();
        final long due =
                Math.max(Math.min(p.last + QUIET_MS, p.first + MAX_WAIT_MS), lastPost + SPACING_MS);
        if (now < due) {
            return new Check(due - now, false);
        }
        pending.remove(chat);
        lastPost = now;
        final boolean sound = p.burstStart && now - lastSound >= SOUND_GAP_MS;
        if (sound) {
            lastSound = now;
        }
        return new Check(0, sound);
    }

    /** The chat was read or its notification dismissed: drop what is waiting. */
    public synchronized void forget(final String chat) {
        pending.remove(chat);
    }
}
