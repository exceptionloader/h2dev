package dev.exceptionloader.reports.common.lookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.exceptionloader.reports.api.model.Edition;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlayerNamesTest {

    @Test
    void prefixedNameIsBedrock() {
        assertEquals(List.of(new NameCandidate("Steve", Edition.BEDROCK)), PlayerNames.candidates(".Steve", "."));
    }

    @Test
    void underscoresInGamertagsAreAlsoTriedAsSpaces() {
        assertEquals(
                List.of(new NameCandidate("Cool_Kid", Edition.BEDROCK), new NameCandidate("Cool Kid", Edition.BEDROCK)),
                PlayerNames.candidates(".Cool_Kid", "."));
    }

    @Test
    void unprefixedNameIsJavaWhenPrefixIsConfigured() {
        assertEquals(List.of(new NameCandidate("Notch", Edition.JAVA)), PlayerNames.candidates("Notch", "."));
    }

    @Test
    void withoutPrefixJavaIsTriedFirst() {
        List<NameCandidate> candidates = PlayerNames.candidates("Notch", "");
        assertEquals(Edition.JAVA, candidates.getFirst().edition());
        assertEquals(Edition.BEDROCK, candidates.getLast().edition());
    }

    @Test
    void invalidNamesHaveNoCandidates() {
        assertTrue(PlayerNames.candidates("", ".").isEmpty());
        assertTrue(PlayerNames.candidates("a".repeat(40), ".").isEmpty());
        assertTrue(PlayerNames.candidates("na me!", ".").isEmpty());
        assertTrue(PlayerNames.candidates(".", ".").isEmpty());
    }

    @Test
    void javaNameRules() {
        assertTrue(PlayerNames.isJavaName("Player_123"));
        assertFalse(PlayerNames.isJavaName("ab"));
        assertFalse(PlayerNames.isJavaName("SeventeenCharName"));
        assertFalse(PlayerNames.isJavaName(".Steve"));
    }
}
