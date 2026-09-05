package io.opaa.indexing.metadata;

import java.util.List;

/**
 * What the model step left behind for the caller: the keywords now stored at the document - they
 * become a segment of the Kontextpräfix and reach embedding and full-text index through it - and
 * the document's chunk metadata after accepted values were applied, {@code null} when nothing was
 * accepted.
 */
public record ModelExtractionOutcome(List<String> keywords, DocumentChunkMetadata chunkMetadata) {

  public static final ModelExtractionOutcome UNCHANGED =
      new ModelExtractionOutcome(List.of(), null);
}
