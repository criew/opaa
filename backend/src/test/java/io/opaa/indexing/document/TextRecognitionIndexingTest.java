package io.opaa.indexing.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.api.types.TextRecognitionConnectionType;
import io.opaa.format.DocumentFormatResult;
import io.opaa.format.file.pdf.PdfOcrDocumentFormat;
import io.opaa.format.file.pdf.PdfPages;
import io.opaa.format.file.pdf.RecognizedPdf;
import io.opaa.format.shared.HeadingSectionSplitterFixture;
import io.opaa.format.shared.ParserLimitProperties;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingEventSink;
import io.opaa.indexing.job.TextRecognitionRunBudget;
import io.opaa.llm.textrecognition.TextRecognitionClient.DoclingConversion;
import io.opaa.llm.textrecognition.TextRecognitionEndpoint;
import io.opaa.llm.textrecognition.TextRecognitionFailures;
import io.opaa.llm.textrecognition.TextRecognitionModelRole;
import io.opaa.llm.textrecognition.TextRecognitionModelRole.ActiveTextRecognition;
import io.opaa.llm.textrecognition.TextRecognitionProperties;
import io.opaa.observability.IndexingMetrics;
import io.opaa.sourceaccess.LoggedName;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Recognition, reuse, locks and limits of scanned PDFs in the indexing (ADR-0069). */
class TextRecognitionIndexingTest {

  private static final String CHECKSUM = "a".repeat(64);
  private static final String RECIPIENT = "cfg-docling";
  private static final ActiveTextRecognition DOCLING =
      new ActiveTextRecognition(
          new TextRecognitionEndpoint(
              true,
              TextRecognitionConnectionType.DOCLING_SERVE,
              "http://ocr:5001",
              null,
              "schluessel"),
          RECIPIENT);

  @TempDir Path tempDir;

  private final TextRecognitionModelRole role = mock(TextRecognitionModelRole.class);
  private final DocumentTextRecognitionStore store = mock(DocumentTextRecognitionStore.class);
  private final PdfPages pdfPages = mock(PdfPages.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final UUID documentId = UUID.randomUUID();
  private final List<String> protocol = new ArrayList<>();
  private Path scan;
  private TextRecognitionIndexing indexing;

  @BeforeEach
  void setUp() throws IOException {
    scan = Files.write(tempDir.resolve("scan.pdf"), new byte[] {'%', 'P', 'D', 'F'});
    when(role.limits()).thenReturn(new TextRecognitionProperties(true, 5, null, 1, 10, null, null));
    when(role.active()).thenReturn(Optional.of(DOCLING));
    when(store.find(documentId)).thenReturn(Optional.empty());
    when(pdfPages.count(any())).thenReturn(2);
    indexing =
        new TextRecognitionIndexing(
            role,
            store,
            new PdfOcrDocumentFormat(HeadingSectionSplitterFixture.DEFAULT),
            pdfPages,
            new IndexingMetrics(meters),
            Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  void aScanIsReadByTheServiceStoredAndCut() throws IOException {
    when(role.convertWithDocling(eq(DOCLING), any(), any()))
        .thenReturn(new DoclingConversion(fixture(), false));

    Optional<ScannedDocumentRecognition.Recognized> recognized =
        indexing.recognize(candidate(false, sink(null)));

    assertThat(recognized).isPresent();
    assertThat(recognized.get().result().outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    assertThat(recognized.get().pipeline().id()).isEqualTo(PdfOcrDocumentFormat.ID);
    verify(store)
        .saveRecognition(
            eq(documentId),
            eq(CHECKSUM),
            eq("DOCLING_SERVE"),
            eq(RECIPIENT),
            eq((int) PdfOcrDocumentFormat.VERSION),
            eq(2),
            anyString(),
            any());
    assertThat(protocol)
        .containsExactly(
            "TEXT_RECOGNIZED Per Texterkennung indiziert (docling-serve, Empfänger cfg-docling,"
                + " 2 Seiten)");
    assertThat(
            meters
                .counter(
                    "opaa.ocr.documents",
                    "result",
                    "recognized",
                    "connection_type",
                    "DOCLING_SERVE")
                .count())
        .isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 401, 403, 429, 500, 503})
  void aFailureOfTheServiceNeverLocksTheScan(int status) {
    when(role.convertWithDocling(eq(DOCLING), any(), any()))
        .thenThrow(TextRecognitionFailures.unavailable(status));

    assertThat(indexing.recognize(candidate(false, sink(null)))).isEmpty();

    verify(store, never()).block(any(), any(), any(), any(), anyInt(), any(), any(), any());
    verify(store, never())
        .saveRecognition(any(), any(), any(), any(), anyInt(), anyInt(), any(), any());
    assertThat(protocol).singleElement().asString().startsWith("TEXT_RECOGNITION_FAILED ");
    assertThat(protocol.getFirst()).endsWith("der nächste Lauf versucht es erneut");
  }

  @Test
  void aTimeoutNeverLocksTheScan() {
    when(role.convertWithDocling(eq(DOCLING), any(), any()))
        .thenThrow(TextRecognitionFailures.timedOut());

    assertThat(indexing.recognize(candidate(false, sink(null)))).isEmpty();

    verify(store, never()).block(any(), any(), any(), any(), anyInt(), any(), any(), any());
    assertThat(protocol)
        .containsExactly(
            "TEXT_RECOGNITION_FAILED Texterkennung gescheitert: Zeitgrenze überschritten"
                + " (Empfänger cfg-docling); der nächste Lauf versucht es erneut");
  }

  @Test
  void theFailureReasonsNameTheClassNotTheDocument() {
    assertThat(TextRecognitionIndexing.failureReason(TextRecognitionFailures.unavailable(401)))
        .isEqualTo("Zugang abgelehnt (HTTP 401)");
    assertThat(TextRecognitionIndexing.failureReason(TextRecognitionFailures.unavailable(429)))
        .isEqualTo("Dienst überlastet (HTTP 429)");
    assertThat(TextRecognitionIndexing.failureReason(TextRecognitionFailures.unavailable(502)))
        .isEqualTo("Fehler des Dienstes (HTTP 502)");
    assertThat(TextRecognitionIndexing.failureReason(TextRecognitionFailures.unavailable(0)))
        .isEqualTo("Dienst nicht erreichbar");
  }

  @Test
  void aRefusalOfTheDocumentLocksIt() {
    when(role.convertWithDocling(eq(DOCLING), any(), any()))
        .thenThrow(TextRecognitionFailures.rejected(422));

    assertThat(indexing.recognize(candidate(false, sink(null)))).isEmpty();

    verify(store)
        .block(
            eq(documentId),
            eq(CHECKSUM),
            eq("DOCLING_SERVE"),
            eq(RECIPIENT),
            eq((int) PdfOcrDocumentFormat.VERSION),
            eq(2),
            eq("Der Dienst hat das Dokument abgelehnt (HTTP 422)"),
            any());
    assertThat(protocol)
        .containsExactly(
            "TEXT_RECOGNITION_BLOCKED Texterkennung gesperrt: Der Dienst hat das Dokument"
                + " abgelehnt (HTTP 422)");
  }

  @Test
  void aScanAboveThePageLimitIsLockedWithoutCallingTheService() throws IOException {
    when(pdfPages.count(any())).thenReturn(6);

    assertThat(indexing.recognize(candidate(false, sink(null)))).isEmpty();

    verify(role, never()).convertWithDocling(any(), any(), any());
    verify(store)
        .block(
            eq(documentId),
            eq(CHECKSUM),
            eq("DOCLING_SERVE"),
            eq(RECIPIENT),
            anyInt(),
            eq(6),
            eq("Seitengrenze überschritten (6 Seiten, erlaubt sind 5)"),
            any());
  }

  @Test
  void aLockedScanIsNotSentAgainUntilTheLockIsReleased() {
    when(store.find(documentId))
        .thenReturn(
            Optional.of(
                new DocumentTextRecognitionStore.Entry(
                    documentId,
                    CHECKSUM,
                    "DOCLING_SERVE",
                    RECIPIENT,
                    PdfOcrDocumentFormat.VERSION,
                    6,
                    null,
                    "Seitengrenze überschritten",
                    Instant.EPOCH)));

    assertThat(indexing.recognize(candidate(false, sink(null)))).isEmpty();

    verifyNoInteractions(pdfPages);
    verify(role, never()).convertWithDocling(any(), any(), any());
    assertThat(protocol)
        .containsExactly(
            "TEXT_RECOGNITION_BLOCKED Texterkennung gesperrt: Seitengrenze überschritten");
  }

  @Test
  void aLockOfAnotherRecipientDoesNotHold() throws IOException {
    when(store.find(documentId))
        .thenReturn(
            Optional.of(
                new DocumentTextRecognitionStore.Entry(
                    documentId,
                    CHECKSUM,
                    "DOCLING_SERVE",
                    "cfg-alt",
                    PdfOcrDocumentFormat.VERSION,
                    2,
                    null,
                    "Der Dienst hat das Dokument abgelehnt (HTTP 422)",
                    Instant.EPOCH)));
    when(role.convertWithDocling(eq(DOCLING), any(), any()))
        .thenReturn(new DoclingConversion(fixture(), false));

    assertThat(indexing.recognize(candidate(false, sink(null)))).isPresent();
  }

  @Test
  void aReindexCutsTheStoredRecognitionAndNeverCallsTheService() throws IOException {
    storedRecognition("cfg-alt");

    Optional<ScannedDocumentRecognition.Recognized> recognized =
        indexing.recognize(candidate(true, sink(null)));

    assertThat(recognized).isPresent();
    verify(role, never()).convertWithDocling(any(), any(), any());
    verifyNoInteractions(pdfPages);
    assertThat(protocol)
        .singleElement()
        .asString()
        .startsWith("TEXT_RECOGNIZED Aus dem gespeicherten");
  }

  @Test
  void aSwitchedOffRoleStillIndexesTheStoredRecognitionButReadsNothingNew() throws IOException {
    when(role.active()).thenReturn(Optional.empty());
    assertThat(indexing.recognize(candidate(false, sink(null)))).isEmpty();
    verifyNoInteractions(pdfPages);

    storedRecognition(RECIPIENT);
    assertThat(indexing.recognize(candidate(false, sink(null)))).isPresent();
    verify(role, never()).convertWithDocling(any(), any(), any());
  }

  @Test
  void aReindexWithoutStoredRecognitionKeepsTheRejection() {
    assertThat(indexing.recognize(candidate(true, sink(null)))).isEmpty();

    verifyNoInteractions(pdfPages);
    verify(role, never()).convertWithDocling(any(), any(), any());
  }

  @Test
  void aSpentBudgetDefersTheScanToTheNextRun() {
    TextRecognitionRunBudget budget = new TextRecognitionRunBudget();
    budget.tryReserve(9, 10);

    assertThat(indexing.recognize(candidate(false, sink(budget)))).isEmpty();

    assertThat(budget.deferred()).isEqualTo(1);
    verify(role, never()).convertWithDocling(any(), any(), any());
    verify(store, never()).block(any(), any(), any(), any(), anyInt(), any(), any(), any());
    assertThat(protocol).singleElement().asString().startsWith("TEXT_RECOGNITION_PENDING ");
  }

  @Test
  void aVisionModelReadsPageByPage() throws IOException {
    ActiveTextRecognition vision =
        new ActiveTextRecognition(
            new TextRecognitionEndpoint(
                true,
                TextRecognitionConnectionType.OPENAI_VISION,
                "http://vision:8000/v1",
                "vision-modell",
                null),
            "cfg-vision");
    when(role.active()).thenReturn(Optional.of(vision));
    doAnswer(
            invocation -> {
              PdfPages.PageImageConsumer consumer = invocation.getArgument(1);
              consumer.accept(1, new byte[] {1});
              consumer.accept(2, new byte[] {2});
              return null;
            })
        .when(pdfPages)
        .render(any(), any());
    when(role.recognizePage(eq(vision), any(), eq("image/png"), any()))
        .thenReturn("# Bescheid\n\nErste Seite.", "Zweite Seite.");

    Optional<ScannedDocumentRecognition.Recognized> recognized =
        indexing.recognize(candidate(false, sink(null)));

    assertThat(recognized).isPresent();
    assertThat(recognized.get().result().chunks().getFirst().getText())
        .isEqualTo("Bescheid\n\nErste Seite.\n\nZweite Seite.");
    verify(store)
        .saveRecognition(
            eq(documentId),
            eq(CHECKSUM),
            eq("OPENAI_VISION"),
            eq("cfg-vision"),
            anyInt(),
            eq(2),
            anyString(),
            any());
  }

  @Test
  void withoutAProtocolTheScanIsStillRecognised() throws IOException {
    when(role.convertWithDocling(eq(DOCLING), any(), any()))
        .thenReturn(new DoclingConversion(fixture(), false));

    assertThat(indexing.recognize(candidate(false, null))).isPresent();
    verify(store, never()).block(any(), any(), any(), any(), anyInt(), any(), any(), any());
  }

  @Test
  void aPartialConversionIsNeitherStoredNorLockedAndTriedAgain() throws IOException {
    when(role.convertWithDocling(eq(DOCLING), any(), any()))
        .thenReturn(new DoclingConversion(fixture(), true));

    assertThat(indexing.recognize(candidate(false, sink(null)))).isEmpty();

    verify(store, never())
        .saveRecognition(any(), any(), any(), any(), anyInt(), anyInt(), any(), any());
    verify(store, never()).block(any(), any(), any(), any(), anyInt(), any(), any(), any());
    assertThat(protocol)
        .containsExactly(
            "TEXT_RECOGNITION_FAILED Texterkennung gescheitert: docling-serve hat das Dokument nur"
                + " teilweise umgewandelt, nichts gespeichert (Empfänger cfg-docling); der nächste"
                + " Lauf versucht es erneut");
    assertThat(
            meters
                .counter(
                    "opaa.ocr.documents", "result", "partial", "connection_type", "DOCLING_SERVE")
                .count())
        .isEqualTo(1);
  }

  @Test
  void aRecognitionWithoutTextIsStoredNotLocked() throws IOException {
    ActiveTextRecognition vision = vision();
    when(role.active()).thenReturn(Optional.of(vision));
    renderTwoPages();
    when(role.recognizePage(eq(vision), any(), eq("image/png"), any())).thenReturn("", " ");

    assertThat(indexing.recognize(candidate(false, sink(null)))).isEmpty();

    verify(store, never()).block(any(), any(), any(), any(), anyInt(), any(), any(), any());
    verify(store)
        .saveRecognition(
            eq(documentId),
            eq(CHECKSUM),
            eq("OPENAI_VISION"),
            eq("cfg-vision"),
            anyInt(),
            eq(2),
            anyString(),
            any());
    assertThat(protocol)
        .singleElement()
        .asString()
        .startsWith("TEXT_RECOGNIZED Texterkennung ohne erkannten Text (Vision-Modell");
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 404, 422})
  void aVisionModelRefusingTheRequestLocksNoScan(int status) throws IOException {
    ActiveTextRecognition vision = vision();
    when(role.active()).thenReturn(Optional.of(vision));
    renderTwoPages();
    when(role.recognizePage(eq(vision), any(), eq("image/png"), any()))
        .thenThrow(TextRecognitionFailures.rejected(status));

    assertThat(indexing.recognize(candidate(false, sink(null)))).isEmpty();

    verify(store, never()).block(any(), any(), any(), any(), anyInt(), any(), any(), any());
    assertThat(protocol)
        .containsExactly(
            "TEXT_RECOGNITION_FAILED Texterkennung gescheitert: Anfrage abgelehnt (HTTP "
                + status
                + "), Modell und Einstellungen prüfen (Empfänger cfg-vision); der nächste Lauf"
                + " versucht es erneut");
  }

  @Test
  void aPageImageTooLargeForTheVisionModelLocksTheScan() throws IOException {
    ActiveTextRecognition vision = vision();
    when(role.active()).thenReturn(Optional.of(vision));
    renderTwoPages();
    when(role.recognizePage(eq(vision), any(), eq("image/png"), any()))
        .thenThrow(TextRecognitionFailures.rejected(413));

    assertThat(indexing.recognize(candidate(false, sink(null)))).isEmpty();

    verify(store)
        .block(
            eq(documentId),
            eq(CHECKSUM),
            eq("OPENAI_VISION"),
            eq("cfg-vision"),
            anyInt(),
            eq(2),
            eq("Der Dienst hat das Dokument abgelehnt (HTTP 413)"),
            any());
  }

  // regression guard for #3637: an oversized page is locked, neither allocated nor retried
  @Test
  void aPageTooLargeToRenderLocksTheScanInsteadOfFailingIt() throws IOException {
    ActiveTextRecognition vision = vision();
    when(role.active()).thenReturn(Optional.of(vision));
    Path oversized = tempDir.resolve("riesig.pdf");
    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage(PDRectangle.A4));
      doc.addPage(new PDPage(new PDRectangle(14_400, 14_400)));
      doc.save(oversized.toFile());
    }
    TextRecognitionIndexing realPages =
        new TextRecognitionIndexing(
            role,
            store,
            new PdfOcrDocumentFormat(HeadingSectionSplitterFixture.DEFAULT),
            new PdfPages(ParserLimitProperties.DEFAULTS),
            new IndexingMetrics(meters),
            Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC));
    when(role.recognizePage(eq(vision), any(), eq("image/png"), any())).thenReturn("Erste Seite.");

    Optional<ScannedDocumentRecognition.Recognized> recognized =
        assertTimeoutPreemptively(
            Duration.ofSeconds(20),
            () ->
                realPages.recognize(
                    new ScannedDocumentRecognition.Candidate(
                        documentId,
                        CHECKSUM,
                        oversized,
                        "ordner/riesig.pdf",
                        LoggedName.OPEN,
                        sink(null),
                        false)));

    assertThat(recognized).isEmpty();
    verify(role).recognizePage(eq(vision), any(), eq("image/png"), any());
    verify(store)
        .block(
            eq(documentId),
            eq(CHECKSUM),
            eq("OPENAI_VISION"),
            eq("cfg-vision"),
            anyInt(),
            eq(2),
            eq(
                "Seite 2 überschreitet die Grenzen der Bilddarstellung (Seitenmaß, Bildgröße oder"
                    + " Rechenzeit)"),
            any());
    assertThat(protocol).singleElement().asString().startsWith("TEXT_RECOGNITION_BLOCKED ");
  }

  @Test
  void anUnexpectedErrorIsAFailureForANewAttemptNotALock() {
    when(role.convertWithDocling(eq(DOCLING), any(), any()))
        .thenThrow(new IllegalArgumentException("kaputt"));

    assertThat(indexing.recognize(candidate(false, sink(null)))).isEmpty();

    verify(store, never()).block(any(), any(), any(), any(), anyInt(), any(), any(), any());
    assertThat(protocol)
        .containsExactly(
            "TEXT_RECOGNITION_FAILED Texterkennung gescheitert: Unerwarteter Fehler der"
                + " Texterkennung (Empfänger cfg-docling); der nächste Lauf versucht es erneut");
  }

  @Test
  void anUnreadableWorkingFileIsAFailureNotASwitchedOffRole() throws IOException {
    Files.delete(scan);

    assertThat(indexing.recognize(candidate(false, sink(null)))).isEmpty();

    verify(store, never()).block(any(), any(), any(), any(), anyInt(), any(), any(), any());
    assertThat(protocol)
        .containsExactly(
            "TEXT_RECOGNITION_FAILED Texterkennung gescheitert: Arbeitsdatei nicht lesbar"
                + " (Empfänger cfg-docling); der nächste Lauf versucht es erneut");
  }

  @Test
  void theServiceCallGetsWhatIsLeftOfTheDocumentsDeadline() throws IOException {
    when(role.convertWithDocling(eq(DOCLING), any(), any()))
        .thenReturn(new DoclingConversion(fixture(), false));

    indexing.recognize(candidate(false, sink(null)));

    verify(role).convertWithDocling(eq(DOCLING), any(), eq(java.time.Duration.ofMinutes(45)));
  }

  @Test
  void anInterruptedWaitForASlotGivesTheBudgetBack() throws Exception {
    TextRecognitionRunBudget budget = new TextRecognitionRunBudget();
    java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch leave = new java.util.concurrent.CountDownLatch(1);
    when(role.convertWithDocling(eq(DOCLING), any(), any()))
        .thenAnswer(
            invocation -> {
              inside.countDown();
              leave.await();
              return new DoclingConversion(fixture(), false);
            });
    Thread holder = new Thread(() -> indexing.recognize(candidate(false, null)));
    holder.start();
    inside.await();

    Thread.currentThread().interrupt();
    Optional<ScannedDocumentRecognition.Recognized> waited =
        indexing.recognize(candidate(false, sink(budget)));
    boolean interrupted = Thread.interrupted();
    leave.countDown();
    holder.join();

    assertThat(waited).isEmpty();
    assertThat(interrupted).isTrue();
    assertThat(budget.pagesSpent()).isZero();
  }

  private static ActiveTextRecognition vision() {
    return new ActiveTextRecognition(
        new TextRecognitionEndpoint(
            true,
            TextRecognitionConnectionType.OPENAI_VISION,
            "http://vision:8000/v1",
            "vision-modell",
            null),
        "cfg-vision");
  }

  private void renderTwoPages() throws IOException {
    doAnswer(
            invocation -> {
              PdfPages.PageImageConsumer consumer = invocation.getArgument(1);
              consumer.accept(1, new byte[] {1});
              consumer.accept(2, new byte[] {2});
              return null;
            })
        .when(pdfPages)
        .render(any(), any());
  }

  private void storedRecognition(String configurationId) throws IOException {
    when(store.find(documentId))
        .thenReturn(
            Optional.of(
                new DocumentTextRecognitionStore.Entry(
                    documentId,
                    CHECKSUM,
                    "DOCLING_SERVE",
                    configurationId,
                    PdfOcrDocumentFormat.VERSION,
                    1,
                    RecognizedPdf.docling(configurationId, fixture()).toJson(),
                    null,
                    null)));
  }

  private ScannedDocumentRecognition.Candidate candidate(
      boolean reindex, IndexingEventSink events) {
    return new ScannedDocumentRecognition.Candidate(
        documentId, CHECKSUM, scan, "ordner/scan.pdf", LoggedName.OPEN, events, reindex);
  }

  private IndexingEventSink sink(TextRecognitionRunBudget budget) {
    return new IndexingEventSink() {
      @Override
      public void record(IndexingEventCategory category, String message, String reference) {
        protocol.add(category + " " + message);
      }

      @Override
      public TextRecognitionRunBudget textRecognitionBudget() {
        return budget;
      }
    };
  }

  private static JsonNode fixture() throws IOException {
    try (InputStream in =
        TextRecognitionIndexingTest.class.getResourceAsStream(
            "/text-recognition/docling-ocr-test.json")) {
      return JsonMapper.builder()
          .build()
          .readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }
}
