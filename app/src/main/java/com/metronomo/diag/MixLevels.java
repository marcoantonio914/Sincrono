package com.metronomo.diag;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Níveis de volume por aparelho definidos pelo anfitrião (0..1). Aparelho sem nível definido = 1.0.
 * Seguro para várias threads. O JSON é montado à mão (este arquivo é Java puro, testável na JVM).
 */
public final class MixLevels {
    private final Map<String, Double> levels = new TreeMap<>();
    private long revision = 1;

    public static double clamp(double v) {
        if (Double.isNaN(v)) return 1.0;
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    public synchronized void set(String id, double level) {
        if (id == null || id.isEmpty() || id.length() > 80) return;
        double v = clamp(level);
        Double old = levels.get(id);
        if (old != null && Math.abs(old - v) < 1e-9) return;
        levels.put(id, v);
        revision++;
    }

    public synchronized double get(String id) {
        Double v = levels.get(id);
        return v == null ? 1.0 : v;
    }

    public synchronized long revision() {
        return revision;
    }

    public synchronized Map<String, Double> snapshot() {
        return new TreeMap<>(levels);
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else b.append(c);
        }
        return b.toString();
    }

    /** {"rev":N,"levels":{"id":0.8,...}} */
    public synchronized String toJson() {
        StringBuilder b = new StringBuilder("{\"rev\":").append(revision).append(",\"levels\":{");
        boolean first = true;
        for (Map.Entry<String, Double> e : levels.entrySet()) {
            if (!first) b.append(',');
            first = false;
            b.append('"').append(esc(e.getKey())).append("\":").append(String.format(Locale.ROOT, "%.3f", e.getValue()));
        }
        return b.append("}}").toString();
    }
}
