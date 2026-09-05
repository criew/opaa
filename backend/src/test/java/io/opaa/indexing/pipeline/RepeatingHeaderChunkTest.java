package io.opaa.indexing.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.ChunkingService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class RepeatingHeaderChunkTest {

  @Test
  void buildsAChunkCarryingTheGivenLocation() {
    Document chunk = RepeatingHeaderChunk.ofOrNull("Kopf-/Fußzeile", "Stadt Musterstadt");

    assertThat(chunk).isNotNull();
    assertThat(chunk.getText()).isEqualTo("Stadt Musterstadt");
    assertThat(chunk.getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("Kopf-/Fußzeile");
  }

  @Test
  void nullTextYieldsNoChunk() {
    assertThat(RepeatingHeaderChunk.ofOrNull("Kopf-/Fußzeile", null)).isNull();
  }

  @Test
  void blankTextYieldsNoChunk() {
    assertThat(RepeatingHeaderChunk.ofOrNull("Kopf-/Fußzeile", "   ")).isNull();
  }

  @Test
  void textWithoutAnySingleLetterYieldsNoChunk() {
    // regression guard for #1145: a field's cached value (e.g. a page
    // number) can slip past a caller's own field filtering; a chunk of nothing but digits is noise.
    assertThat(RepeatingHeaderChunk.ofOrNull("Kopf-/Fußzeile", "1")).isNull();
    assertThat(RepeatingHeaderChunk.ofOrNull("Kopf-/Fußzeile", "12 / 34")).isNull();
  }

  @Test
  void textWithAtLeastOneLetterYieldsAChunk() {
    Document chunk = RepeatingHeaderChunk.ofOrNull("Kopf-/Fußzeile", "Seite 1 von 12");

    assertThat(chunk).isNotNull();
    assertThat(chunk.getText()).isEqualTo("Seite 1 von 12");
  }
}
