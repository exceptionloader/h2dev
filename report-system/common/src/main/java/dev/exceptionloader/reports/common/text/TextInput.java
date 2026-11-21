package dev.exceptionloader.reports.common.text;

import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Normalizes free text typed by players before it is stored or shown to other players.
 */
public final class TextInput {

    private static final Pattern LEGACY_FORMATTING = Pattern.compile("(?i)[§&][0-9a-fk-orx]");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private TextInput() {
    }

    /**
     * Removes formatting codes and control characters and collapses whitespace.
     *
     * @param raw user input
     * @return cleaned text, empty if nothing is left
     */
    public static @NotNull String clean(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        String text = LEGACY_FORMATTING.matcher(raw).replaceAll("");
        text = CONTROL.matcher(text).replaceAll(" ");
        text = text.replace('§', ' ');
        return WHITESPACE.matcher(text).replaceAll(" ").strip();
    }

    /**
     * Shortens a text to {@code maxLength} characters, appending an ellipsis if it was cut.
     */
    public static @NotNull String abbreviate(@NotNull String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 1)).stripTrailing() + "…";
    }
}
