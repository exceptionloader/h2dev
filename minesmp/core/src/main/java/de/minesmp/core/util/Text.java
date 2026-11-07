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

    private static final java.util.regex.Pattern GRAD = java.util.regex.Pattern.compile("<g((?::#[0-9a-fA-F]{6}){2,})>(.*?)</g>");

    public static Component c(String s) {
        if (s == null) return Component.empty();
        return LEGACY.deserialize(gradients(s));
    }

    /** Fuer alte APIs (z.B. Bukkit-BossBar): Farbcodes als Section-Zeichen. */
    public static String legacy(String s) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(c(s));
    }

    /** Ersetzt <g:#55FFFF:#4F7CFF>Text</g> durch einzelne Hex-Farben pro Zeichen. Fuehrende &l/&o usw. bleiben erhalten. */
    public static String gradients(String s) {
        if (s.indexOf("<g:") < 0) return s;
        java.util.regex.Matcher m = GRAD.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String[] hex = m.group(1).substring(2).split(":#");
            String body = m.group(2), flags = "";
            while (body.length() >= 2 && body.charAt(0) == '&' && "lmnok".indexOf(Character.toLowerCase(body.charAt(1))) >= 0) {
                flags += body.substring(0, 2);
                body = body.substring(2);
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(gradient(body, 0, flags, hex)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Farbverlauf ueber die Zeichen; offset (0..1) verschiebt den Verlauf (fuer Animationen, zyklisch). */
    public static String gradient(String text, double offset, String flags, String... hex) {
        int[] cps = text.codePoints().toArray();
        int[][] cols = new int[hex.length][];
        for (int i = 0; i < hex.length; i++) {
            int v = Integer.parseInt(hex[i].replace("#", ""), 16);
            cols[i] = new int[]{(v >> 16) & 255, (v >> 8) & 255, v & 255};
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cps.length; i++) {
            if (Character.isWhitespace(cps[i])) { sb.appendCodePoint(cps[i]); continue; }
            double t = cps.length <= 1 ? 0 : (double) i / (cps.length - 1);
            if (offset != 0) { t = (t + offset) % 1.0; t = t < 0.5 ? t * 2 : (1 - t) * 2; } // hin und zurueck, damit die Animation nahtlos laeuft
            double pos = t * (cols.length - 1);
            int a = Math.min(cols.length - 2, (int) Math.floor(pos));
            double f = pos - a;
            int r = (int) Math.round(cols[a][0] + (cols[a + 1][0] - cols[a][0]) * f);
            int g = (int) Math.round(cols[a][1] + (cols[a + 1][1] - cols[a][1]) * f);
            int b = (int) Math.round(cols[a][2] + (cols[a + 1][2] - cols[a][2]) * f);
            sb.append(String.format("&#%02X%02X%02X", r, g, b)).append(flags).appendCodePoint(cps[i]);
        }
        return sb.toString();
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
