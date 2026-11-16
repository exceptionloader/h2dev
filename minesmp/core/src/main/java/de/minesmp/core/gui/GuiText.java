package de.minesmp.core.gui;

import de.minesmp.core.MineSMP;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Titelzeile fuer Menues mit eigenem Fenster aus dem Ressourcenpaket (siehe tools/build_goldrush.py).
 * Das Fensterbild ist ein Schriftzeichen in der Schrift minesmp:gui; Abstands-Zeichen schieben den Schreibkopf,
 * die Schriften minesmp:gr0..gr4 sind die normale Schrift, versetzt auf die Kisten-Reihen 0-4.
 * Alle Positionen sind Fenster-Koordinaten (0 = linker Rand des Fensters). Die Masse stehen in goldrush-gui.yml.
 */
public final class GuiText {
    /** Zeile fuer {@link Builder#text}: die Titelzeile des Fensters (normale Schrift). 0-4 = Kisten-Reihe. */
    public static final int TITLE = -1;
    private static final int[] SHIFTS = {128, 64, 32, 16, 8, 4, 2, 1};
    /** Breite (Vorschub) der Zeichen der Standardschrift, gemessen an ascii.png von Minecraft 26.3. Alles andere: 6. */
    private static final Map<Character, Integer> ADVANCE = new HashMap<>();
    static {
        for (char ch : "!',.:;i|".toCharArray()) ADVANCE.put(ch, 2);
        for (char ch : "`l".toCharArray()) ADVANCE.put(ch, 3);
        for (char ch : " \"()*I[]t{}".toCharArray()) ADVANCE.put(ch, 4);
        for (char ch : "<>fk".toCharArray()) ADVANCE.put(ch, 5);
        for (char ch : "@~".toCharArray()) ADVANCE.put(ch, 7);
    }

    private final Key font;
    private final int width, pillX, barX, barPieces;
    private final Map<String, String> pages = new HashMap<>();
    private final Map<Integer, String> back = new HashMap<>(), forward = new HashMap<>();
    private final Map<Integer, Key> rowFonts = new HashMap<>();
    private final String barOn, barOff;

    public GuiText(MineSMP plugin, String resource) {
        YamlConfiguration y = new YamlConfiguration();
        try (var in = plugin.getResource(resource)) {
            if (in != null) y = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (java.io.IOException ignored) { }
        font = Key.key(y.getString("font", "minesmp:gui"));
        width = y.getInt("width", 176);
        pillX = y.getInt("pill-x", 111);
        var ps = y.getConfigurationSection("pages");
        if (ps != null) for (String k : ps.getKeys(false)) pages.put(k, ps.getString(k));
        for (int n : SHIFTS) {
            back.put(n, y.getString("shift-back." + n, ""));
            forward.put(n, y.getString("shift-forward." + n, ""));
        }
        var rf = y.getConfigurationSection("row-fonts");
        if (rf != null) for (String k : rf.getKeys(false)) rowFonts.put(Integer.parseInt(k), Key.key(rf.getString(k)));
        barOn = y.getString("bar.on", "");
        barOff = y.getString("bar.off", "");
        barX = y.getInt("bar.x", 12);
        barPieces = y.getInt("bar.pieces", 38);
    }

    public boolean ready() { return !pages.isEmpty(); }
    public int pillX() { return pillX; }

    /** Breite eines Textes in der Standardschrift (Pixel). */
    public static int width(String s, boolean bold) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) w += ADVANCE.getOrDefault(s.charAt(i), 6) + (bold ? 1 : 0);
        return w;
    }

    /** Nur Zeichen, die es in den versetzten Schriften gibt (ASCII ohne Steuerzeichen). */
    public static String ascii(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case 'ä' -> sb.append("ae"); case 'ö' -> sb.append("oe"); case 'ü' -> sb.append("ue");
                case 'Ä' -> sb.append("Ae"); case 'Ö' -> sb.append("Oe"); case 'Ü' -> sb.append("Ue"); case 'ß' -> sb.append("ss");
                default -> sb.append(ch >= 32 && ch < 127 ? ch : '?');
            }
        }
        return sb.toString();
    }

    public Builder page(String page) { return new Builder(page); }

    public final class Builder {
        private final TextComponent.Builder out = Component.text();
        private int x = 8;                      // der Titel beginnt bei x = 8

        private Builder(String page) {
            move(0);
            out.append(Component.text(pages.getOrDefault(page, "")).font(font).color(NamedTextColor.WHITE));
            x += width + 1;
        }

        private void move(int target) {
            int d = target - x;
            if (d == 0) return;
            StringBuilder sb = new StringBuilder();
            Map<Integer, String> set = d < 0 ? back : forward;
            int left = Math.abs(d);
            for (int n : SHIFTS) while (left >= n) { sb.append(set.get(n)); left -= n; }
            out.append(Component.text(sb.toString()).font(font));
            x = target;
        }

        /** Text mit linker Kante bei atX. row = TITLE (Titelzeile) oder 0-4 (Kisten-Reihe). */
        public Builder text(String s, int atX, int row, TextColor color, boolean bold) {
            if (s == null || s.isEmpty()) return this;
            Key f = row == TITLE ? null : rowFonts.get(row);
            if (row != TITLE) { if (f == null) return this; s = ascii(s); }
            move(atX);
            Component c = Component.text(s).color(color).decoration(TextDecoration.BOLD, bold);
            out.append(f == null ? c : c.font(f));
            x += width(s, bold);
            return this;
        }

        /** Text mittig um centerX (die Luecke nach dem letzten Zeichen zaehlt nicht mit). */
        public Builder centered(String s, int centerX, int row, TextColor color, boolean bold) {
            if (s == null || s.isEmpty()) return this;
            String t = row == TITLE ? s : ascii(s);
            return text(t, centerX - (width(t, bold) - 1) / 2, row, color, bold);
        }

        /** Text, dessen letztes Zeichen bei rightX endet. */
        public Builder right(String s, int rightX, int row, TextColor color, boolean bold) {
            if (s == null || s.isEmpty()) return this;
            String t = row == TITLE ? s : ascii(s);
            return text(t, rightX - (width(t, bold) - 1) + 1, row, color, bold);
        }

        /** Fortschrittsbalken (Stelle ist im Bild fest, siehe goldrush-gui.yml -> bar). */
        public Builder bar(double ratio) {
            int on = (int) Math.round(Math.max(0, Math.min(1, ratio)) * barPieces);
            if (ratio > 0 && on == 0) on = 1;
            move(barX);
            out.append(Component.text(barOn.repeat(on) + barOff.repeat(barPieces - on)).font(font).color(NamedTextColor.WHITE));
            x += barPieces * 4;
            return this;
        }

        public Component build() { return out.build(); }
    }
}
