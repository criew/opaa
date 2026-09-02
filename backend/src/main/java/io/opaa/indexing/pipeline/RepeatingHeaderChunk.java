package io.opaa.indexing.pipeline;

import io.opaa.indexing.ChunkingService;
import java.util.HashMap;
import java.util.Map;
import org.springframework.ai.document.Document;

/**
 * Builds the single, deduplicated leading chunk a page header/footer or slide-master text
 * contributes to a document (docs/features/ingestion-pipelines.md, Teil 3 Punkt 2) - shared by the
 * ODT, ODP and DOCX pipelines. Header/footer or master-slide text repeats on every page/slide; a
 * caller collects it once, up front, rather than duplicating it into every chunk (the #1103
 * frontmatter-dilution problem) or dropping it entirely.
 */
public final class RepeatingHeaderChunk {

  private RepeatingHeaderChunk() {}

  /**
   * @param location the {@code location} metadata value this leading chunk carries (e.g. "Kopf-/
   *     Fußzeile", "Masterfolie")
   * @return {@code null} when {@code text} is blank - nothing to contribute
   */
  public static Document ofOrNull(String location, String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    Map<String, Object> metadata = new HashMap<>();
    metadata.put(ChunkingService.LOCATION_METADATA_KEY, location);
    return new Document(HeadingSectionSplitter.capChunkLength(text.strip()), metadata);
  }
}
