package io.opaa.indexing.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.api.types.TextRecognitionConnectionType;
import io.opaa.indexing.document.DocumentIngest;
import io.opaa.indexing.document.DocumentIngestResult;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.document.DocumentTextRecognitionStore;
import io.opaa.indexing.document.TextRecognitionIndexing;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.registry.StoredDocumentSourceAccess;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.llm.TextRecognitionEndpoint;
import io.opaa.llm.TextRecognitionModelRole.ActiveTextRecognition;
import io.opaa.organization.Organization;
import io.opaa.test.SourceTypes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/** The catch-up of waiting uploaded scans (ADR-0069). */
class TextRecognitionBacklogTest {

  private static final ActiveTextRecognition ACTIVE =
      new ActiveTextRecognition(
          new TextRecognitionEndpoint(
              true, TextRecognitionConnectionType.DOCLING_SERVE, "http://ocr:5001", null, null),
          "cfg-1");

  private final TextRecognitionIndexing textRecognition = mock(TextRecognitionIndexing.class);
  private final DocumentTextRecognitionStore store = mock(DocumentTextRecognitionStore.class);
  private final DocumentRepository documentRepository = mock(DocumentRepository.class);
  private final KnowledgeLibraryRepository libraryRepository =
      mock(KnowledgeLibraryRepository.class);
  private final DocumentIngestService ingestService = mock(DocumentIngestService.class);
  private final StoredDocumentSourceAccess sourceAccess = mock(StoredDocumentSourceAccess.class);
  private final KnowledgeLibrary library =
      KnowledgeLibrary.ownedByUser(Organization.DEFAULT_ID, "Uploads", null, UUID.randomUUID());
  private final UUID first = UUID.randomUUID();
  private final UUID second = UUID.randomUUID();
  @TempDir Path tempDir;
  private Path scan;
  private TextRecognitionBacklog backlog;

  @BeforeEach
  void setUp() throws IOException {
    scan = Files.write(tempDir.resolve("scan.pdf"), new byte[] {'%', 'P', 'D', 'F'});
    UUID libraryId = library.getId();
    when(textRecognition.active()).thenReturn(Optional.of(ACTIVE));
    when(store.pendingUploads(isNull(), anyInt(), eq("cfg-1"), anyInt()))
        .thenReturn(List.of(first, second));
    when(store.pendingUploads(eq(second), anyInt(), eq("cfg-1"), anyInt())).thenReturn(List.of());
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
    for (UUID id : List.of(first, second)) {
      Document document = mock(Document.class);
      when(document.getId()).thenReturn(id);
      when(document.getLibraryId()).thenReturn(libraryId);
      when(document.getFileName()).thenReturn(id + ".pdf");
      when(document.getFilePath()).thenReturn(id + ".pdf");
      when(document.getSourceType()).thenReturn(SourceTypes.UPLOAD);
      when(documentRepository.findById(id)).thenReturn(Optional.of(document));
    }
    when(sourceAccess.withLocalSourceFile(any(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              Predicate<Path> action = invocation.getArgument(2);
              return action.test(scan);
            });
    backlog =
        new TextRecognitionBacklog(
            textRecognition,
            store,
            documentRepository,
            libraryRepository,
            ingestService,
            sourceAccess,
            Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  void withTextRecognitionOffNothingIsSelected() {
    when(textRecognition.active()).thenReturn(Optional.empty());

    assertThat(backlog.catchUp()).isEqualTo(new TextRecognitionBacklog.PassResult(0, 0, 0, false));

    verifyNoInteractions(store, ingestService);
  }

  @Test
  void everyWaitingScanIsIngestedAgainFromItsStoredOriginal() throws IOException {
    when(ingestService.ingest(any(), any())).thenReturn(DocumentIngestResult.PROCESSED);

    TextRecognitionBacklog.PassResult result = backlog.catchUp();

    assertThat(result.recognized()).isEqualTo(2);
    ArgumentCaptor<DocumentIngest> ingests = ArgumentCaptor.forClass(DocumentIngest.class);
    verify(ingestService, times(2)).ingest(ingests.capture(), any());
    assertThat(ingests.getAllValues())
        .allSatisfy(
            ingest -> {
              assertThat(ingest.existingRow()).isTrue();
              assertThat(ingest.malwareScanned()).isTrue();
              assertThat(ingest.events().textRecognitionBudget()).isNotNull();
            });
  }

  @Test
  void aFailureOfTheServiceEndsThePassAtTheNextDocument() throws IOException {
    when(ingestService.ingest(any(), any()))
        .thenAnswer(
            invocation -> {
              DocumentIngest ingest = invocation.getArgument(0);
              ingest
                  .events()
                  .record(IndexingEventCategory.TEXT_RECOGNITION_FAILED, "gescheitert", "x");
              return DocumentIngestResult.NO_EXTRACTABLE_TEXT;
            });

    TextRecognitionBacklog.PassResult result = backlog.catchUp();

    assertThat(result.notRecognized()).isEqualTo(1);
    verify(ingestService, times(1)).ingest(any(), any());
  }

  @Test
  void aSpentBudgetEndsThePass() throws IOException {
    when(ingestService.ingest(any(), any()))
        .thenAnswer(
            invocation -> {
              DocumentIngest ingest = invocation.getArgument(0);
              var budget = ingest.events().textRecognitionBudget();
              budget.tryReserve(500, 500);
              budget.tryReserve(1, 500);
              return DocumentIngestResult.NO_EXTRACTABLE_TEXT;
            });

    TextRecognitionBacklog.PassResult result = backlog.catchUp();

    assertThat(result.budgetSpent()).isTrue();
    assertThat(result.pages()).isEqualTo(500);
    verify(ingestService, times(1)).ingest(any(), any());
  }

  @Test
  void aDocumentWithoutLibraryIsPassedOver() throws IOException {
    when(libraryRepository.findById(any())).thenReturn(Optional.empty());

    assertThat(backlog.catchUp().recognized()).isZero();

    verify(ingestService, never()).ingest(any(), any());
  }
}
