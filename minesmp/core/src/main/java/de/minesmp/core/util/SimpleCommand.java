package de.minesmp.core.util;

import org.bukkit.command.CommandSender;

import java.util.List;

/** Befehl als Lambda fuer kleine Funktionen. */
public final class SimpleCommand extends BaseCommand {
    public interface Executor { void run(CommandSender s, String label, String[] args); }
    public interface Completer { List<String> complete(CommandSender s, String[] args); }

    private final Executor exec;
    private final Completer completer;

    public SimpleCommand(String name, String permission, String description, Executor exec, Completer completer, String... aliases) {
        super(name, permission, description, aliases);
        this.exec = exec;
        this.completer = completer;
    }

    public SimpleCommand(String name, String permission, String description, Executor exec, String... aliases) {
        this(name, permission, description, exec, (s, a) -> a.length == 1 ? null : List.of(), aliases);
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        exec.run(sender, label, args);
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        return completer.complete(sender, args);
    }
}
