package io.opaa.indexing;

import io.opaa.indexing.metadata.CoreMetadata;
import io.opaa.indexing.metadata.DocumentMetadataService;
import org.mockito.Mockito;

/**
 * A {@link DocumentMetadataService} stand-in for the mock-based {@code FileProcessingService}
 * tests: every extraction answers {@link CoreMetadata#EMPTY} instead of Mockito's {@code null}, so
 * {@code storeChunks} sees the same shape it sees for a document without core fields.
 */
public final class TestDocumentMetadataServices {

  private TestDocumentMetadataServices() {}

  public static DocumentMetadataService returningEmpty() {
    return Mockito.mock(
        DocumentMetadataService.class,
        invocation ->
            invocation.getMethod().getReturnType() == CoreMetadata.class
                ? CoreMetadata.EMPTY
                : Mockito.RETURNS_DEFAULTS.answer(invocation));
  }
}
