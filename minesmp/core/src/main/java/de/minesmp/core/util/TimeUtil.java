package de.minesmp.core.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TimeUtil {
    private static final Pattern PART = Pattern.compile("(\\d+)\\s*(mo|w|d|h|m|s)");

    private TimeUtil() {}

    /** Parst "1d2h30m" in Millisekunden. -1 bei Fehler, 0 bei "perm"/"permanent". */
    public static long parseDuration(String in) {
        if (in == null) return -1;
        String s = in.toLowerCase(Locale.ROOT).trim();
        if (s.equals("perm") || s.equals("permanent") || s.equals("-1")) return 0;
        Matcher m = PART.matcher(s);
        long total = 0;
        int end = 0;
        boolean any = false;
        while (m.find()) {
            if (m.start() != end) return -1;
            end = m.end();
            any = true;
            long n = Long.parseLong(m.group(1));
            total += switch (m.group(2)) {
                case "mo" -> n * 30L * 86_400_000L;
                case "w" -> n * 7L * 86_400_000L;
                case "d" -> n * 86_400_000L;
                case "h" -> n * 3_600_000L;
                case "m" -> n * 60_000L;
                default -> n * 1_000L;
            };
        }
        if (!any || end != s.length()) return -1;
        return total;
    }

    public static String format(long millis) {
        if (millis <= 0) return "0s";
        long s = millis / 1000;
        long d = s / 86400; s %= 86400;
        long h = s / 3600; s %= 3600;
        long m = s / 60; s %= 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (s > 0 && d == 0) sb.append(s).append("s");
        return sb.toString().trim();
    }
}
