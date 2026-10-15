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

  private static final String TOOL = PromptChecksumTest.class.getName() + "$Tool";

  private static final String RETOOLED = PromptChecksumTest.class.getName() + "$Retooled";

  @interface Described {
    String value();

    String[] examples() default {};
  }

  @SuppressWarnings("unused")
  static final class Tool {
    @Described(value = "Durchsucht die Wissensbasis.", examples = "Bauantrag")
    String search(@Described("Die Suchfrage.") String question) {
      return question;
    }
  }

  @SuppressWarnings("unused")
  static final class Retooled {
    @Described(value = "Durchsucht die Wissensbasis gründlich.", examples = "Bauantrag")
    String search(@Described("Die Suchfrage.") String question) {
      return question;
    }
  }

  @Test
  void readsTheStringValuesOfAnnotationsOnMethodsAndParameters() {
    assertThat(PromptChecksum.stringConstants(TOOL))
        .contains("Durchsucht die Wissensbasis.", "Bauantrag", "Die Suchfrage.");
  }

  @Test
  void aChangedAnnotationValueChangesTheChecksum() {
    assertThat(PromptChecksum.of(List.of(TOOL))).isNotEqualTo(PromptChecksum.of(List.of(RETOOLED)));
  }

  @Test
  void readsTheDefaultTheChangelogGivesAColumn() {
    assertThat(PromptChecksum.databaseDefault("external_access_settings.server_instructions"))
        .startsWith("Bei Fragen zu Verwaltungsvorgängen dieser Behörde zuerst „search“ aufrufen.")
        .endsWith("Nichts erfinden, was die Treffer nicht hergeben.");
  }

  @Test
  void aColumnWithoutDefaultFails() {
    assertThatThrownBy(() -> PromptChecksum.databaseDefault("external_access_settings.version"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("external_access_settings.version");
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
