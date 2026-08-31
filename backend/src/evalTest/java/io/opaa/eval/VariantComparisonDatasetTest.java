package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Docker-free: loads the shipped example comparison and checks its declared shape. */
class VariantComparisonDatasetTest {

  @TempDir Path tempDir;

  @Test
  void loadsTheShippedComicCharactersSelectionMechanicsComparison() throws Exception {
    Path file =
        RepoPaths.evalDir()
            .resolve("variants")
            .resolve("comic-characters-selection-mechanics.json");

    VariantComparison comparison = VariantComparisonDataset.load(file);

    assertThat(comparison.name()).isEqualTo("comic-characters-selection-mechanics");
    assertThat(comparison.domain()).isEqualTo("comic-characters");
    assertThat(comparison.referenceVariant()).isEqualTo("production");
    assertThat(comparison.variants())
        .extracting(PipelineVariant::name)
        .contains("production", "mmr-0.7");
    assertThat(comparison.reference().queryOverrides())
        .isEqualTo(PipelineVariant.QueryOverrides.NONE);
  }

  /**
   * Issue #1041 review, Befund 2: a typo in an override field name (here {@code mmrLamda} instead
   * of {@code mmrLambda}) must fail the load instead of being silently dropped — a dropped field
   * would run the variant unchanged against production and let it appear as a legitimate Δ0.000
   * comparison point. Guards the deliberate absence of {@code @JsonIgnoreProperties(ignoreUnknown =
   * true)} on {@link PipelineVariant.QueryOverrides}.
   */
  @Test
  void rejectsAnUnknownFieldInQueryOverrides() throws Exception {
    Path file = tempDir.resolve("typo.json");
    Files.writeString(
        file,
        """
        {
          "name": "typo-comparison",
          "description": "desc",
          "domain": "comic-characters",
          "referenceVariant": "production",
          "variants": [
            { "name": "production", "description": "desc", "requiresReindex": false, "queryOverrides": {} },
            { "name": "typo", "description": "desc", "requiresReindex": false, "queryOverrides": { "mmrLamda": 0.7 } }
          ]
        }
        """);

    assertThatThrownBy(() -> VariantComparisonDataset.load(file))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("mmrLamda");
  }

  /** Same guard, one level up: an unknown top-level field on a variant must also fail the load. */
  @Test
  void rejectsAnUnknownFieldOnAVariant() throws Exception {
    Path file = tempDir.resolve("typo-variant.json");
    Files.writeString(
        file,
        """
        {
          "name": "typo-comparison",
          "description": "desc",
          "domain": "comic-characters",
          "referenceVariant": "production",
          "variants": [
            { "name": "production", "descriptoin": "typo", "requiresReindex": false, "queryOverrides": {} }
          ]
        }
        """);

    assertThatThrownBy(() -> VariantComparisonDataset.load(file))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("descriptoin");
  }
}
