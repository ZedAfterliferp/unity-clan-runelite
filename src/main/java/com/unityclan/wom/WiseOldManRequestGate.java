package com.unityclan.wom;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/** Coordinates WOM requests made by the competition, top players and weekly XP panels.
 * Calls are serialized across worker threads. This is intentionally independent
 * of the Swing EDT and the RuneLite client thread.
 */
public final class WiseOldManRequestGate {
    private static final Object LOCK = new Object();
    private static final long SPACING_MS = TimeUnit.SECONDS.toMillis(10);
    private static final long DEFAULT_429_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long MAX_429_MS = TimeUnit.HOURS.toMillis(1);
    private static long nextAllowedAt;
    private static long cooldownUntil;
    private static int consecutive429;

    private WiseOldManRequestGate() { }

    public static <T> T execute(Callable<T> request) throws Exception {
        synchronized (LOCK) {
            long cooldown = cooldownUntil - System.currentTimeMillis();
            if (cooldown > 0)
                throw new IllegalStateException("WOM cooldown active (" + ((cooldown + 59999) / 60000) + "m)");
            long remaining;
            while ((remaining = nextAllowedAt - System.currentTimeMillis()) > 0) {
                LOCK.wait(remaining);
                cooldown = cooldownUntil - System.currentTimeMillis();
                if (cooldown > 0)
                    throw new IllegalStateException("WOM cooldown active (" + ((cooldown + 59999) / 60000) + "m)");
            }
            // Reserve a gap even if a request fails or throws unexpectedly.
            nextAllowedAt = System.currentTimeMillis() + SPACING_MS;
            try {
                T result = request.call();
                consecutive429 = 0;
                nextAllowedAt = Math.max(nextAllowedAt, System.currentTimeMillis() + SPACING_MS);
                return result;
            } catch (Exception ex) {
                if (is429(ex)) rateLimited(0);
                throw ex;
            }
        }
    }

    /** Call when an HTTP 429 is observed; respects Retry-After in seconds. */
    public static void onRateLimited(String retryAfter) {
        synchronized (LOCK) {
            long ms = 0;
            if (retryAfter != null) {
                try { ms = TimeUnit.SECONDS.toMillis(Long.parseLong(retryAfter.trim())); }
                catch (NumberFormatException ignored) { /* HTTP-date or missing value */ }
            }
            cooldownUntil = Math.max(cooldownUntil, System.currentTimeMillis() + Math.max(0L, ms));
        }
    }

    private static void rateLimited(long retryAfterMs) {
        consecutive429 = Math.min(consecutive429 + 1, 6);
        long exponential = DEFAULT_429_MS * (1L << (consecutive429 - 1));
        long delay = Math.max(retryAfterMs, Math.min(MAX_429_MS, exponential));
        cooldownUntil = Math.max(cooldownUntil, System.currentTimeMillis() + delay);
    }

    private static boolean is429(Exception ex) {
        String message = ex.getMessage();
        return message != null && message.contains("429");
    }
}
