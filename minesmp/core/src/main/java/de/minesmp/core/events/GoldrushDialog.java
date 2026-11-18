package de.minesmp.core.events;

import de.minesmp.core.MineSMP;
import de.minesmp.core.events.GoldrushService.Find;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Text;
import de.minesmp.core.util.TimeUtil;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.ShadowColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Grosses Goldrausch-Fenster (Minecraft-Dialog, scrollbar, unten "Schliessen") - aufgebaut wie das Goldrausch-Menue
 * von HugoSMP: Hauptgewinn Goldticket, Gratis-Schluessel je Kiste, Premium und Goldener Name, Status, Goldtickets,
 * Diese Erze zaehlen, Event wird moderiert, Was du finden kannst, Dein Goldrausch. Eigene Grafiken in MineSMP-Farben
 * (Bilder: Schrift minesmp:dlg, erzeugt von tools/build_goldrush.py). Ohne Ressourcenpaket dieselben Inhalte als Text.
 * Knoepfe unten oeffnen Shop, Meilensteine und Rangliste (Kisten-Menues aus {@link GoldrushMenu}).
 */
public final class GoldrushDialog {
    private static final int W = 330;
    private static final Key FONT = Key.key("minesmp", "dlg");
    private static YamlConfiguration glyphs;

    private GoldrushDialog() {}

    private static YamlConfiguration glyphs(MineSMP plugin) {
        if (glyphs == null) {
            glyphs = new YamlConfiguration();
            try (var in = plugin.getResource("goldrush-gui.yml")) {
                if (in != null) glyphs = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (java.io.IOException ignored) { }
        }
        return glyphs;
    }

    /** Bedrock (Floodgate) kennt keine Dialoge. */
    public static boolean supported(Player p) {
        return p.getUniqueId().getMostSignificantBits() != 0;
    }

    public static void open(GoldrushService gs, Player p) {
        MineSMP plugin = gs.plugin();
        boolean pack = plugin.pack() != null && plugin.pack().has(p) && glyphs(plugin).isConfigurationSection("dialog.images");
        List<DialogBody> body = new ArrayList<>();
        var cfg = gs.cfg();
        int days = cfg.getInt("ticket.days", 14);

        // ---- Hauptgewinn
        image(body, pack, "banner", "&6&l✦ HAUPTGEWINN: GOLDTICKET ✦");
        text(body, "&7Ein Schlag ins richtige Erz und du raeumst &f" + days + " Tage &7lang jeden Tag ab.\n&6🎁 Jeden Tag ein Gratis-Schluessel aus jeder Kiste");
        image(body, pack, "cards", "&aVote &8| &9Rare &8| &5Epic &8| &6Legendary &7- jeden Tag gratis");
        image(body, pack, "boxes", "&b" + cfg.getInt("ticket.premium-days", 30) + " Tage Premium &7(oder als Geschenk) &8| &6Goldener Name &7in Chat und Tab");
        int perDay = 0;
        ConfigurationSection dk = cfg.getConfigurationSection("ticket.daily-keys");
        if (dk != null) for (String k : dk.getKeys(false)) perDay += Math.max(0, dk.getInt(k));
        text(body, "&d♦ &7Bis zu &f" + (perDay * days) + " Gratis-Schluessel&7, dazu Premium und goldener Name");
        image(body, pack, "slogan", "&6↗ &7Bau Erze ab. &6&lJedes Erz kann es sein!");

        // ---- Status
        image(body, pack, "h_status", "&b&lSTATUS");
        if (gs.active())
            text(body, "&a● Goldrausch laeuft &7- noch &f" + TimeUtil.format(gs.left() - gs.left() % 60_000)
                    + "\n&7Goldene Stunde: &e" + gs.goldenHourText() + " &8| &7Server-Ziel: &b" + Text.money(gs.totalOres()) + " Erze");
        else
            text(body, gs.everRan() ? "&7● Der Goldrausch ist beendet. &7Ergebnis: &bRangliste" : "&e● Noch nicht gestartet\n&7Der Goldrausch startet bald. Bis dahin zaehlt noch nichts.");

        // ---- Goldtickets
        image(body, pack, "h_tickets", "&6&lGOLDTICKETS");
        StringBuilder t = new StringBuilder("&6" + gs.ticketsTotal() + " &7gefunden gesamt   &8|   &6" + gs.ticketsToday() + "&7/" + cfg.getInt("ticket.max-per-day", 1) + " heute gefunden");
        List<String> finders = gs.finders();
        if (finders.isEmpty()) t.append("\n&8Noch niemand hat ein Goldticket gefunden. Vielleicht bist du der Erste!");
        for (int i = 0; i < Math.min(3, finders.size()); i++) {
            String[] nt = finders.get(i).split(":");
            long when = 0;
            try { when = Long.parseLong(nt.length > 1 ? nt[1] : "0"); } catch (NumberFormatException ignored) { }
            long ago = Math.max(60_000, System.currentTimeMillis() - when);
            t.append("\n&6✦ ").append(nt[0]).append(" &8vor ").append(TimeUtil.format(ago - ago % 60_000));
        }
        if (gs.hasTicket(p))
            t.append("\n&a✔ Dein Goldticket gilt noch ").append(GoldrushService.shortSpan(gs.ticketUntil(p) - System.currentTimeMillis()))
                    .append(gs.dailyReady(p) ? " &e- Gratis-Schluessel bereit!" : " &7- heute schon abgeholt");
        text(body, t.toString());

        // ---- Diese Erze zaehlen
        image(body, pack, "h_ores", "&b&lDIESE ERZE ZAEHLEN");
        StringBuilder ores = new StringBuilder();
        int n = 0;
        for (Material m : GoldrushService.ORE_ORDER) {
            if (!gs.isOre(m)) continue;
            String icon = pack ? glyphs(plugin).getString("dialog.ores." + m.name().toLowerCase(java.util.Locale.ROOT), "") : "";
            ores.append(n % 3 == 0 ? (n == 0 ? "" : "\n") : "    ");
            ores.append("&f").append(icon.isEmpty() ? "" : "\u0000ICON" + icon + "\u0000 ").append(GoldrushService.oreName(m));
            n++;
        }
        body.add(DialogBody.plainMessage(withIcons(ores.toString()), W));
        text(body, "&8Tiefenschiefer-Erze daneben zaehlen genauso. In jedem Erz kann ein Fund stecken,\n&8egal wann du spielst. Nicht: Netherquarzerz, Nethergolderz und gesetzte Erze.");
        image(body, pack, "moderated", "&c&lEVENT WIRD MODERIERT\n&7Makros, X-Ray und andere Cheats sind verboten und werden mit einem Bann bestraft.");

        // ---- Was du finden kannst
        image(body, pack, "h_finds", "&6&lWAS DU FINDEN KANNST");
        for (Find f : new Find[]{Find.NUGGET, Find.BAR, Find.STARTOOL, Find.SPAWNER, Find.TICKET}) {
            ItemStack it;
            String name, desc;
            switch (f) {
                case NUGGET -> { it = plugin.customItems().create("goldnugget"); name = "Goldnugget"; desc = gs.moneyText(f) + " direkt aufs Konto"; }
                case BAR -> { it = plugin.customItems().create("goldbarren"); name = "Goldbarren"; desc = gs.moneyText(f) + " direkt aufs Konto"; }
                case STARTOOL -> { it = plugin.customItems().create("sternenpicke"); name = "Star-Tool"; desc = "Zufaelliges Star-Tool, nur fuer dich"; }
                case SPAWNER -> { it = new ItemStack(Material.SPAWNER); name = "Spawner"; desc = "Zufaelliger Spawner: " + spawnerNames(gs); }
                default -> { it = plugin.customItems().create("goldticket"); name = "Goldticket"; desc = "Hauptgewinn, alles dazu ganz oben"; }
            }
            if (it == null) it = new ItemStack(Material.GOLD_NUGGET);
            String odds = GoldrushMenu.odds(gs.chance(f));
            Component d = Text.c((f == Find.TICKET ? "&6&l" : "&f&l") + name + "   &8" + odds + " je Erz" + (gs.found(p, f) > 0 ? " &a(" + gs.found(p, f) + "x gefunden)" : "") + "\n&7" + desc);
            body.add(DialogBody.item(it).description(DialogBody.plainMessage(d, W - 30)).showTooltip(true).showDecorations(false).build());
        }

        // ---- Dein Goldrausch
        image(body, pack, "h_mine", "&b&lDEIN GOLDRAUSCH");
        int rank = gs.rank(p);
        text(body, "&b" + Text.money(gs.ores(p)) + " &7Erze gesamt   &8|   &6" + gs.finds(p) + " &7Funde   &8|   &a" + plugin.eco().format(gs.won(p)) + " &7Geld gesamt"
                + "\n&e" + plugin.eco().format(gs.wonToday(p)) + " &7heute   &8|   &7Glueck &a" + (gs.active() ? GoldrushService.factor(gs.luck(p)) : "-")
                + "   &8|   &7Platz &6" + (rank == 0 ? "-" : "#" + rank) + "   &8|   &6" + Text.money(gs.dust(p)) + " &7Goldstaub");

        // ---- Knoepfe
        List<ActionButton> buttons = new ArrayList<>();
        if (gs.dailyReady(p)) buttons.add(button("&6🎁 Gratis-Schluessel abholen", "Heutige Schluessel deines Goldtickets", pl -> { gs.claimDaily(pl); open(gs, pl); }));
        buttons.add(button("&6Goldstaub-Shop", "Items fuer Goldstaub", pl -> new GoldrushMenu(gs, pl, GoldrushMenu.Tab.SHOP).open(pl)));
        buttons.add(button("&bMeilensteine", "Belohnungen fuer abgebaute Erze", pl -> new GoldrushMenu(gs, pl, GoldrushMenu.Tab.ZIELE).open(pl)));
        buttons.add(button("&eRangliste", "Die Top 10 gewinnen am Ende", pl -> new GoldrushMenu(gs, pl, GoldrushMenu.Tab.TOP).open(pl)));
        ActionButton close = ActionButton.builder(Text.c("&7✕ Schliessen")).width(150).build();
        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Text.c("<g:#FFF3B0:#FFC83D>&lGOLDRAUSCH</g>")).canCloseWithEscape(true).pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE).body(body).build())
                .type(DialogType.multiAction(buttons).columns(Math.min(4, buttons.size())).exitAction(close).build()));
        p.showDialog(dialog);
    }

    private static ActionButton button(String label, String tip, Consumer<Player> run) {
        return ActionButton.builder(Text.c(label)).tooltip(Text.c("&7" + tip)).width(110)
                .action(DialogAction.customClick((view, audience) -> {
                    if (audience instanceof Player pl) Bukkit.getScheduler().runTask(MineSMP.get(), () -> { if (pl.isOnline()) run.accept(pl); });
                }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(15)).build())).build();
    }

    private static String spawnerNames(GoldrushService gs) {
        List<String> out = new ArrayList<>();
        for (String type : gs.cfg().getStringList("finds.spawner.types")) {
            ItemStack it = gs.plugin().spawners() == null ? null : gs.plugin().spawners().item(type, 1);
            if (it != null) out.add(Text.strip(Items.name(it)).replace("-Spawner", "").replace(" Spawner", ""));
        }
        return String.join(", ", out);
    }

    private static void text(List<DialogBody> body, String s) {
        body.add(DialogBody.plainMessage(Text.c(s), W));
    }

    /** Bild aus dem Ressourcenpaket (ragt nach unten, danach Leerzeilen) oder Ersatztext ohne Paket. */
    private static void image(List<DialogBody> body, boolean pack, String name, String fallback) {
        String chars = pack ? glyphs(MineSMP.get()).getString("dialog.images." + name + ".text", "") : "";
        if (chars.isEmpty()) { text(body, fallback); return; }
        int lines = Math.max(1, glyphs(MineSMP.get()).getInt("dialog.images." + name + ".lines", 1));
        Component c = Component.text(chars).font(FONT).shadowColor(ShadowColor.none()).color(net.kyori.adventure.text.format.NamedTextColor.WHITE)
                .append(Component.text("\n".repeat(lines - 1) + " "));
        body.add(DialogBody.plainMessage(c, W));
    }

    /** Ersetzt Platzhalter \0ICON<z>\0 durch das Erz-Symbol in der Schrift minesmp:dlg. */
    private static Component withIcons(String s) {
        var out = Component.text();
        String[] parts = s.split("\u0000");
        for (String part : parts) {
            if (part.startsWith("ICON")) out.append(Component.text(part.substring(4)).font(FONT).shadowColor(ShadowColor.none()).color(net.kyori.adventure.text.format.NamedTextColor.WHITE));
            else out.append(Text.c(part));
        }
        return out.build();
    }
}
