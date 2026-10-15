package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class PromptChecksumTest {

  private static final String WORDING = PromptChecksumTest.class.getName() + "$Wording";

  private static final String REWORDED = PromptChecksumTest.class.getName() + "$Reworded";

  @SuppressWarnings("unused")
  static final class Wording {
    private static final String PROMPT =
        """
        Du beantwortest Fragen.
        """;

    static String render(int limit) {
      return PROMPT + "Höchstens " + limit + " Sätze.";
    }
  }

  @SuppressWarnings("unused")
  static final class Reworded {
    private static final String PROMPT =
        """
        Du beantwortest Fragen knapp.
        """;

    static String render(int limit) {
      return PROMPT + "Höchstens " + limit + " Sätze.";
    }
  }

  @Test
  void readsTextBlocksAndTheTemplatesOfRuntimeConcatenation() {
    assertThat(PromptChecksum.stringConstants(WORDING))
        .contains("Du beantwortest Fragen.\n")
        .anySatisfy(constant -> assertThat(constant).contains("Höchstens").contains("Sätze."));
  }

  @Test
  void aChangedWordingChangesTheChecksum() {
    assertThat(PromptChecksum.of(List.of(WORDING)))
        .matches("[0-9a-f]{64}")
        .isEqualTo(PromptChecksum.of(List.of(WORDING)))
        .isNotEqualTo(PromptChecksum.of(List.of(REWORDED)));
  }

  @Test
  void theOrderOfTheClassesDoesNotMatter() {
    assertThat(PromptChecksum.of(List.of(WORDING, REWORDED)))
        .isEqualTo(PromptChecksum.of(List.of(REWORDED, WORDING)));
  }

  @Test
  void aClassNotOnTheClasspathFails() {
    assertThatThrownBy(() -> PromptChecksum.of(List.of("io.opaa.compliance.Missing")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("io.opaa.compliance.Missing");
  }
}
