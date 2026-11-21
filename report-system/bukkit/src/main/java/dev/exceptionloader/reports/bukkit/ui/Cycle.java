package dev.exceptionloader.reports.bukkit.ui;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Steps through a list of filter options, wrapping around at both ends.
 */
public final class Cycle {

    private Cycle() {
    }

    public static <T> T step(List<T> options, @Nullable T current, boolean forward) {
        if (options.isEmpty()) {
            throw new IllegalArgumentException("no options");
        }
        int index = options.indexOf(current);
        if (index < 0) {
            return options.getFirst();
        }
        return options.get(Math.floorMod(index + (forward ? 1 : -1), options.size()));
    }
}
