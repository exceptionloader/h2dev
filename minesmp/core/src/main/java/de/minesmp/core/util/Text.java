package de.minesmp.core.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Farb- und Formatierungshilfen. Unterstuetzt &-Farbcodes und &#RRGGBB. */
public final class Text {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.GERMANY));
    private static final DecimalFormat SHORT = new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.GERMANY));

    private Text() {}

    public static Component c(String s) {
        if (s == null) return Component.empty();
        return LEGACY.deserialize(s);
    }

    /** Fuer Item-Namen/Lore: Kursiv standardmaessig aus. */
    public static Component item(String s) {
        return c(s).decoration(TextDecoration.ITALIC, false);
    }

    public static List<Component> itemLore(List<String> lines) {
        List<Component> out = new ArrayList<>();
        for (String l : lines) out.add(item(l));
        return out;
    }

    public static String plain(Component c) {
        return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
    }

    public static String strip(String legacy) {
        return plain(c(legacy));
    }

    public static synchronized String money(double v) {
        return MONEY.format(v);
    }

    public static synchronized String compact(double v) {
        if (v >= 1_000_000_000) return SHORT.format(v / 1_000_000_000) + "Mrd";
        if (v >= 1_000_000) return SHORT.format(v / 1_000_000) + "Mio";
        if (v >= 10_000) return SHORT.format(v / 1_000) + "k";
        return MONEY.format(v);
    }

    /** Ersetzt {key}-Platzhalter: replace("Hallo {name}", "name", "Max"). */
    public static String replace(String s, String... kv) {
        if (s == null) return "";
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1] == null ? "" : kv[i + 1]);
        return s;
    }

    public static String progressBar(double ratio, int length, String full, String empty) {
        ratio = Math.max(0, Math.min(1, ratio));
        int filled = (int) Math.round(ratio * length);
        StringBuilder sb = new StringBuilder();
        sb.append(full);
        for (int i = 0; i < filled; i++) sb.append('|');
        sb.append(empty);
        for (int i = filled; i < length; i++) sb.append('|');
        return sb.toString();
    }

    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        s = s.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Parst Betraege wie 100, 1.5k, 2m, 1,5. Gibt -1 bei ungueltiger Eingabe zurueck. */
    public static double parseAmount(String in) {
        if (in == null || in.isBlank()) return -1;
        String s = in.trim().toLowerCase(Locale.ROOT).replace(",", ".");
        double mul = 1;
        if (s.endsWith("k")) { mul = 1_000; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("m")) { mul = 1_000_000; s = s.substring(0, s.length() - 1); }
        try {
            double v = Double.parseDouble(s) * mul;
            if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0) return -1;
            return Math.floor(v * 100) / 100.0;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
