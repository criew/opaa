package io.opaa.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DatePrecision;
import io.opaa.api.types.MetadataOrigin;
import io.opaa.common.Feature;
import io.opaa.common.FeatureFlags;
import io.opaa.common.TestFeatureFlags;
import io.opaa.format.DocumentFormatRegistry;
import io.opaa.format.DocumentProperties;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.test.SourceTypes;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The two read and write paths of {@link DocumentMetadataService} the metadata feature switch
 * (ADR-0044) decides: the ingest's title-only extraction and the core fields a Beleg reads. With
 * the feature off, only the title is written and read; every other stored row stays as it is.
 */
@ExtendWith(MockitoExtension.class)
class DocumentMetadataServiceFeatureTest {

  @Mock private DocumentMetadataValueRepository valueRepository;
  @Mock private DocumentTypeVocabularyRepository vocabularyRepository;
  @Mock private DocumentRepository documentRepository;
  @Mock private ChunkMetadataStore chunkMetadataStore;
  @Mock private LibraryMetadataFieldRepository libraryFieldRepository;
  @Mock private DocumentKeywordRepository keywordRepository;
  @Mock private LibraryMetadataFieldValueRepository libraryValueRepository;
  @Mock private KnowledgeLibraryRepository libraryRepository;

  private Document document;

  @BeforeEach
  void setUp() {
    document =
        new Document(
            "2026-03-12_Dienstanweisung_IT-Nutzung.pdf",
            "/akten/2026-03-12_Dienstanweisung_IT-Nutzung.pdf",
            "application/pdf",
            10L,
            SourceTypes.FILESYSTEM);
    lenient().when(vocabularyRepository.snapshot()).thenReturn(TestVocabularies.delivered());
  }

  @Test
  void theTitleExtractionWritesOnlyTheTitleAndHandsTheDocumentBackToTheBackfill() {
    DocumentMetadataValue storedType =
        DocumentMetadataValue.deterministic(document.getId(), CoreMetadataField.DOCUMENT_TYPE, 1)
            .assignVocabularyCode("PROTOKOLL");
    DocumentMetadataValue newTitle =
        DocumentMetadataValue.deterministic(document.getId(), CoreMetadataField.TITLE, 1)
            .assignText("Dienstanweisung zur IT-Nutzung");
    when(valueRepository.findByDocumentId(document.getId()))
        .thenReturn(List.of(storedType), List.of(storedType, newTitle));

    DocumentChunkMetadata result =
        service(TestFeatureFlags.without(Feature.METADATA))
            .applyTitleExtraction(document, document.getFileName(), pdfProperties());

    ArgumentCaptor<DocumentMetadataValue> saved =
        ArgumentCaptor.forClass(DocumentMetadataValue.class);
    verify(valueRepository).save(saved.capture());
    assertThat(saved.getAllValues())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.getFieldKey()).isEqualTo(CoreMetadataField.TITLE.key());
              assertThat(row.getTextValue()).isEqualTo("Dienstanweisung zur IT-Nutzung");
            });
    verify(valueRepository, never()).delete(any());
    verify(documentRepository).clearMetadataExtractionVersion(document.getId());
    verify(documentRepository, never()).updateMetadataExtractionVersion(any(), anyInt());
    verify(keywordRepository, never()).findByDocumentIdOrderByKeywordAsc(any());
    assertThat(result.values()).isEmpty();
    assertThat(result.contextPrefixValues()).isEmpty();
    assertThat(result.contextTitle()).isEqualTo("Dienstanweisung zur IT-Nutzung");
  }

  @Test
  void theTitleExtractionKeepsAManualTitle() {
    DocumentMetadataValue manualTitle =
        DocumentMetadataValue.manual(document.getId(), CoreMetadataField.TITLE, UUID.randomUUID())
            .assignText("Von Hand");
    when(valueRepository.findByDocumentId(document.getId())).thenReturn(List.of(manualTitle));

    DocumentChunkMetadata result =
        service(TestFeatureFlags.without(Feature.METADATA))
            .applyTitleExtraction(document, document.getFileName(), pdfProperties());

    verify(valueRepository, never()).save(any());
    assertThat(result.contextTitle()).isEqualTo("Von Hand");
  }

  @Test
  void switchedOffTheBelegReadsOnlyTheTitle() {
    when(valueRepository.findByDocumentIdIn(Set.of(document.getId()))).thenReturn(storedCoreRows());

    Map<UUID, CoreMetadata> core =
        service(TestFeatureFlags.without(Feature.METADATA))
            .coreMetadataFor(Set.of(document.getId()));

    assertThat(core.get(document.getId()))
        .isEqualTo(
            new CoreMetadata(
                "Dienstanweisung",
                MetadataOrigin.DETERMINISTIC,
                null,
                null,
                null,
                null,
                null,
                null));
  }

  @Test
  void switchedOnTheBelegReadsEveryCoreField() {
    when(valueRepository.findByDocumentIdIn(Set.of(document.getId()))).thenReturn(storedCoreRows());

    Map<UUID, CoreMetadata> core =
        service(TestFeatureFlags.allOn()).coreMetadataFor(Set.of(document.getId()));

    assertThat(core.get(document.getId()).title()).isEqualTo("Dienstanweisung");
    assertThat(core.get(document.getId()).documentTypeCode()).isEqualTo("PROTOKOLL");
    assertThat(core.get(document.getId()).documentDate()).isEqualTo(LocalDate.of(2026, 3, 12));
  }

  private List<DocumentMetadataValue> storedCoreRows() {
    return List.of(
        DocumentMetadataValue.deterministic(document.getId(), CoreMetadataField.TITLE, 1)
            .assignText("Dienstanweisung"),
        DocumentMetadataValue.deterministic(document.getId(), CoreMetadataField.DOCUMENT_TYPE, 1)
            .assignVocabularyCode("PROTOKOLL"),
        DocumentMetadataValue.deterministic(document.getId(), CoreMetadataField.DOCUMENT_DATE, 1)
            .assignDate(LocalDate.of(2026, 3, 12), DatePrecision.DAY));
  }

  private static DocumentProperties pdfProperties() {
    return new DocumentProperties(
        "Dienstanweisung zur IT-Nutzung",
        null,
        LocalDate.of(2025, 6, 30),
        null,
        null,
        null,
        null,
        "pdf",
        false,
        Map.of(),
        Map.of());
  }

  private DocumentMetadataService service(FeatureFlags flags) {
    return new DocumentMetadataService(
        valueRepository,
        vocabularyRepository,
        documentRepository,
        mock(DocumentFormatRegistry.class),
        chunkMetadataStore,
        libraryFieldRepository,
        keywordRepository,
        libraryValueRepository,
        libraryRepository,
        mock(PlatformTransactionManager.class),
        flags);
  }
}
