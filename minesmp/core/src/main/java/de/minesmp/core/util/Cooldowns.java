package de.minesmp.core.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Einfache, speicherbasierte Cooldowns pro Spieler und Schluessel. */
public final class Cooldowns {
    private final Map<String, Long> until = new ConcurrentHashMap<>();

    /** Restzeit in ms, 0 wenn frei. */
    public long remaining(UUID id, String key) {
        Long t = until.get(id + ":" + key);
        if (t == null) return 0;
        long left = t - System.currentTimeMillis();
        if (left <= 0) { until.remove(id + ":" + key); return 0; }
        return left;
    }

    public void set(UUID id, String key, long millis) {
        if (millis <= 0) return;
        until.put(id + ":" + key, System.currentTimeMillis() + millis);
    }

    /** Prueft und setzt in einem Schritt. true = erlaubt. */
    public boolean tryUse(UUID id, String key, long millis) {
        if (remaining(id, key) > 0) return false;
        set(id, key, millis);
        return true;
    }

    public void clear(UUID id) {
        String p = id + ":";
        until.keySet().removeIf(k -> k.startsWith(p));
    }
}
