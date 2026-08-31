package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Docker-free: loads the shipped example comparison and checks its declared shape. */
class VariantComparisonDatasetTest {

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
}
