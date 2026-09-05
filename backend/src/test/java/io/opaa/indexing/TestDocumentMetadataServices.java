package io.opaa.indexing;

import io.opaa.indexing.metadata.CoreMetadata;
import io.opaa.indexing.metadata.DocumentChunkMetadata;
import io.opaa.indexing.metadata.DocumentMetadataService;
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
}
