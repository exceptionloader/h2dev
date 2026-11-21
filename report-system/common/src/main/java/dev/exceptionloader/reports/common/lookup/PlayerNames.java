package dev.exceptionloader.reports.common.lookup;

import dev.exceptionloader.reports.api.model.Edition;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;

/**
 * Rules for telling Java and Bedrock names apart.
 *
 * <p>Floodgate puts a configurable prefix in front of every Bedrock name, so a prefixed name is a
 * Bedrock gamertag. Without a prefix only names that are invalid on Java can be identified as
 * Bedrock; valid Java names are tried as Java first and as Bedrock second.
 */
public final class PlayerNames {

    private static final Pattern JAVA_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern GAMERTAG = Pattern.compile("[A-Za-z0-9_ ]{1,16}");
    private static final int MAX_INPUT_LENGTH = 32;

    private PlayerNames() {
    }

    public static boolean isJavaName(@NotNull String name) {
        return JAVA_NAME.matcher(name).matches();
    }

    /**
     * @param input         name as typed by the user
     * @param bedrockPrefix Floodgate username prefix, may be empty
     * @return lookup candidates in the order they should be tried, empty if the name is invalid
     */
    public static @NotNull List<NameCandidate> candidates(@NotNull String input, @NotNull String bedrockPrefix) {
        String name = input.strip();
        if (name.isEmpty() || name.length() > MAX_INPUT_LENGTH) {
            return List.of();
        }
        if (!bedrockPrefix.isEmpty() && name.startsWith(bedrockPrefix)) {
            String gamertag = name.substring(bedrockPrefix.length());
            return isGamertag(gamertag) ? bedrock(gamertag) : List.of();
        }
        if (isJavaName(name)) {
            if (!bedrockPrefix.isEmpty()) {
                return List.of(new NameCandidate(name, Edition.JAVA));
            }
            List<NameCandidate> candidates = new ArrayList<>(3);
            candidates.add(new NameCandidate(name, Edition.JAVA));
            candidates.addAll(bedrock(name));
            return List.copyOf(candidates);
        }
        if (bedrockPrefix.isEmpty() && isGamertag(name)) {
            return bedrock(name);
        }
        return List.of();
    }

    /*
     * Floodgate replaces spaces in gamertags with underscores, the API only knows the original
     * gamertag. Names with underscores are therefore tried both ways.
     */
    private static List<NameCandidate> bedrock(String gamertag) {
        NameCandidate asTyped = new NameCandidate(gamertag, Edition.BEDROCK);
        if (gamertag.indexOf('_') < 0) {
            return List.of(asTyped);
        }
        return List.of(asTyped, new NameCandidate(gamertag.replace('_', ' '), Edition.BEDROCK));
    }

    private static boolean isGamertag(String name) {
        return GAMERTAG.matcher(name).matches() && !name.isBlank();
    }
}
