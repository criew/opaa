package io.opaa.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.opaa.common.Feature;
import io.opaa.common.TestFeatureFlags;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * With the metadata feature off (ADR-0044) the run of pending schema changes advances nothing: the
 * changes stay pending, exactly as stored, until the feature is on again.
 */
class LibraryMetadataSchemaChangeSwitchedOffTest {

  @Test
  void aChargeHasNothingToDoAndReadsNoChange() {
    LibraryMetadataSchemaChangeRepository changeRepository =
        mock(LibraryMetadataSchemaChangeRepository.class);
    DocumentRepository documentRepository = mock(DocumentRepository.class);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    LibraryMetadataSchemaChangeService service =
        new LibraryMetadataSchemaChangeService(
            changeRepository,
            mock(LibraryMetadataFieldRepository.class),
            mock(LibraryMetadataFieldValueRepository.class),
            mock(DocumentMetadataValueRepository.class),
            documentRepository,
            mock(DocumentMetadataService.class),
            mock(DocumentMetadataCorrectionService.class),
            mock(DocumentTypeVocabularyRepository.class),
            mock(ApplicationEventPublisher.class),
            jdbcTemplate,
            mock(PlatformTransactionManager.class),
            TestFeatureFlags.without(Feature.METADATA));
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(UUID.randomUUID(), "Bibliothek", null, UUID.randomUUID());

    LibraryMetadataSchemaRunResult result = service.runBatch(library, 10, null);

    assertThat(result.processedDocuments()).isZero();
    assertThat(result.complete()).isTrue();
    verifyNoInteractions(changeRepository, documentRepository, jdbcTemplate);
  }
}
