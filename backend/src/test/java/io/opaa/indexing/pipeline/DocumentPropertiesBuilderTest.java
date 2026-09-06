package io.opaa.indexing.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The builder is the only construction path a pipeline uses; it must fill exactly the field it
 * names and normalize the same way every other path does.
 */
class DocumentPropertiesBuilderTest {

  @Test
  void everyNamedFieldReachesItsOwnComponent() {
    DocumentProperties properties =
        DocumentProperties.builder()
            .title("Gebuehrensatzung")
            .createdAt(LocalDate.of(2026, 3, 12))
            .modifiedAt(LocalDate.of(2026, 4, 1))
            .documentDate(LocalDate.of(2026, 2, 28))
            .firstHeading("Praeambel")
            .titleLine("Satzung der Stadt Musterstadt")
            .formatExtension(".ODT")
            .syntheticName(true)
            .frontmatter(Map.of("Titel", "Gebuehrensatzung"))
            .build();

    assertThat(properties.title()).isEqualTo("Gebuehrensatzung");
    assertThat(properties.createdAt()).isEqualTo(LocalDate.of(2026, 3, 12));
    assertThat(properties.modifiedAt()).isEqualTo(LocalDate.of(2026, 4, 1));
    assertThat(properties.documentDate()).isEqualTo(LocalDate.of(2026, 2, 28));
    assertThat(properties.firstHeading()).isEqualTo("Praeambel");
    assertThat(properties.titleLine()).isEqualTo("Satzung der Stadt Musterstadt");
    assertThat(properties.formatExtension()).isEqualTo(".odt");
    assertThat(properties.syntheticName()).isTrue();
    assertThat(properties.frontmatter()).containsExactly(Map.entry("titel", "Gebuehrensatzung"));
  }

  @Test
  void anUnsetFieldStaysAbsent() {
    DocumentProperties properties = DocumentProperties.builder().title("Nur ein Titel").build();

    assertThat(properties).isEqualTo(DocumentProperties.EMPTY.withTitle("Nur ein Titel"));
  }

  @Test
  void theBuilderNormalizesLikeEveryOtherConstructionPath() {
    DocumentProperties properties =
        DocumentProperties.builder()
            .title("   ")
            .titleLine("Erste Zeile\nZweite Zeile")
            .frontmatter(Map.of("Stand", "  2026-03-12  "))
            .build();

    assertThat(properties.title()).isNull();
    assertThat(properties.titleLine()).isEqualTo("Erste Zeile");
    assertThat(properties.frontmatter()).containsExactly(Map.entry("stand", "2026-03-12"));
  }

  @Test
  void toBuilderRoundTripsEveryFieldUnchanged() {
    DocumentProperties original =
        DocumentProperties.builder()
            .title("Gebuehrensatzung")
            .createdAt(LocalDate.of(2026, 3, 12))
            .modifiedAt(LocalDate.of(2026, 4, 1))
            .documentDate(LocalDate.of(2026, 2, 28))
            .firstHeading("Praeambel")
            .titleLine("Satzung der Stadt Musterstadt")
            .formatExtension(".odt")
            .syntheticName(true)
            .frontmatter(Map.of("titel", "Gebuehrensatzung"))
            .build();

    assertThat(original.toBuilder().build()).isEqualTo(original);
  }
}
