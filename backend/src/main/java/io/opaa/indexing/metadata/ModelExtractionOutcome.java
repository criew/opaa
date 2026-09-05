package io.opaa.indexing.metadata;

import java.util.List;

/**
 * What the model step left behind for the caller: the keywords now stored at the document - they
 * reach the full-text index and the Kontextpräfix, nothing else - and the document's chunk metadata
 * after accepted values were applied, {@code null} when no value was accepted.
 */
public record ModelExtractionOutcome(List<String> keywords, DocumentChunkMetadata chunkMetadata) {

  public static final ModelExtractionOutcome UNCHANGED =
      new ModelExtractionOutcome(List.of(), null);

  /** The keywords joined for the full-text supplement, or {@code null} when there are none. */
  public String fullTextSupplement() {
    return keywords.isEmpty() ? null : String.join(" ", keywords);
  }
}
