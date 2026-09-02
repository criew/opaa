package io.opaa.indexing.pipeline;

import io.opaa.indexing.ChunkingService;
import java.util.HashMap;
import java.util.Map;
import org.springframework.ai.document.Document;

/**
 * Builds the single, deduplicated leading chunk a page header/footer or slide-master text
 * contributes to a document - shared by the ODT, ODP and DOCX pipelines. Header/footer or
 * master-slide text repeats on every page/slide; a caller collects it once, up front, rather than
 * duplicating it into every chunk or dropping it entirely.
 */
public final class RepeatingHeaderChunk {

  private RepeatingHeaderChunk() {}

  /**
   * @param location the {@code location} metadata value this leading chunk carries (e.g. "Kopf-/
   *     Fußzeile", "Masterfolie")
   * @return {@code null} when {@code text} is blank, or when it contains no letter at all after
   *     stripping - a document field's cached value (e.g. a page number) can still slip past a
   *     caller's own field filtering, and a chunk of nothing but digits ("1") is noise, not content
   *     worth its own embedding
   */
  public static Document ofOrNull(String location, String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    String stripped = text.strip();
    if (stripped.codePoints().noneMatch(Character::isLetter)) {
      return null;
    }
    Map<String, Object> metadata = new HashMap<>();
    metadata.put(ChunkingService.LOCATION_METADATA_KEY, location);
    return new Document(HeadingSectionSplitter.capChunkLength(stripped), metadata);
  }
}
