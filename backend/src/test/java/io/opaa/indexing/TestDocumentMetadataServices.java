package io.opaa.indexing;

import io.opaa.indexing.metadata.CoreMetadata;
import io.opaa.indexing.metadata.DocumentChunkMetadata;
import io.opaa.indexing.metadata.DocumentMetadataService;
import io.opaa.indexing.metadata.ModelExtractionOutcome;
import io.opaa.indexing.metadata.ModelMetadataExtractor;
import org.mockito.Mockito;

/**
 * A {@link DocumentMetadataService} stand-in for the mock-based {@code FileProcessingService}
 * tests: every extraction answers the empty shape instead of Mockito's {@code null}, so {@code
 * storeChunks} sees what it sees for a document without any schema values.
 */
public final class TestDocumentMetadataServices {

  private TestDocumentMetadataServices() {}

  public static DocumentMetadataService returningEmpty() {
    return Mockito.mock(
        DocumentMetadataService.class,
        invocation -> {
          Class<?> returnType = invocation.getMethod().getReturnType();
          if (returnType == CoreMetadata.class) {
            return CoreMetadata.EMPTY;
          }
          if (returnType == DocumentChunkMetadata.class) {
            return DocumentChunkMetadata.EMPTY;
          }
          return Mockito.RETURNS_DEFAULTS.answer(invocation);
        });
  }

  /**
   * A {@link ModelMetadataExtractor} stand-in that never calls a model: the step-2 counterpart of
   * {@link #returningEmpty()}, so a mock-based ingest test exercises exactly the path a library
   * with both switches off takes.
   */
  public static ModelMetadataExtractor notExtracting() {
    // Answer rather than a stub: a test whose ingest never reaches the model step would otherwise
    // fail Mockito's strict-stub check for an unused stubbing.
    return Mockito.mock(
        ModelMetadataExtractor.class,
        invocation ->
            invocation.getMethod().getReturnType() == ModelExtractionOutcome.class
                ? ModelExtractionOutcome.UNCHANGED
                : Mockito.RETURNS_DEFAULTS.answer(invocation));
  }
}
