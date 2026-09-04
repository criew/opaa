package io.opaa.indexing.metadata;

import java.util.Optional;

/**
 * What {@link CoreMetadataExtractor} read for one document. An empty {@link Optional} is the
 * regular "the sources declare nothing usable" outcome, never an error.
 */
public record ExtractedCoreMetadata(
    Optional<String> title, Optional<String> documentTypeCode, Optional<ExtractedDate> date) {

  public static final ExtractedCoreMetadata EMPTY =
      new ExtractedCoreMetadata(Optional.empty(), Optional.empty(), Optional.empty());
}
