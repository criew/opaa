package io.opaa.indexing.pipeline.mail;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.DocumentService;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineRegistry;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.PassthroughMetadataKeysTestSupport;
import io.opaa.indexing.pipeline.TikaFallbackPipeline;
import io.opaa.indexing.pipeline.tabular.TabularDocumentPipeline;
import io.opaa.indexing.pipeline.tabular.TabularProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.message.BodyPartBuilder;
import org.apache.james.mime4j.message.DefaultMessageWriter;
import org.apache.james.mime4j.message.MultipartBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The EML/MSG pipeline (#1060, ingestion-pipelines.md Teil 3, Punkt 5): Kopfdaten land as chunk
 * metadata rather than chunk text, one chunk per message (or per thread segment), and an attachment
 * runs through the pipeline of its own type - recursively, including EML-in-EML.
 *
 * <p>EML fixtures are built at test time through mime4j's own writer ({@link DefaultMessageWriter})
 * - a real, spec-shaped MIME message rather than a hand-computed byte literal, mirroring how {@link
 * TabularDocumentPipelineTest} builds its XLSX fixtures through Apache POI rather than a static
 * binary file. The two {@code .msg} fixtures are real files instead (Apache POI offers no MSG
 * writer - see {@code test-documents/mail/NOTICE.md}).
 */
class MailDocumentPipelineTest {

  @TempDir Path tempDir;

  private final MailProperties defaultProperties = new MailProperties(0, 0, 0, 0);

  private static ChunkingService defaultChunkingService() {
    return new ChunkingService(
        new IndexingProperties(1000, 100, 50, null, null, List.of(), null, null, null, 1));
  }

  private MailDocumentPipeline pipeline(MailProperties properties) {
    return pipeline(properties, defaultChunkingService());
  }

  /**
   * Mirrors the production circular-bean resolution ({@link IndexingConfiguration}'s own comment on
   * why {@link MailDocumentPipeline} takes an {@link ObjectProvider}): the registry needs the
   * pipeline instance under test to route a nested EML/MSG attachment back into it, but the
   * pipeline needs the registry - so the registry is only assembled (into this holder) once the
   * pipeline itself already exists, and {@code getObject()} reads it lazily, after that assembly,
   * exactly the way a real attachment routing call would.
   *
   * @param extraPipelines additional pipelines registered alongside the fallback and tabular ones -
   *     e.g. a {@link FakePipeline} that throws, for the "a sub-pipeline failure costs only the
   *     attachment" regression (#1101 review, finding 4a)
   */
  private MailDocumentPipeline pipeline(
      MailProperties properties,
      ChunkingService chunkingService,
      DocumentPipeline... extraPipelines) {
    DocumentPipelineRegistry[] registryHolder = new DocumentPipelineRegistry[1];
    ObjectProvider<DocumentPipelineRegistry> provider =
        new ObjectProvider<>() {
          @Override
          public DocumentPipelineRegistry getObject() {
            return registryHolder[0];
          }

          @Override
          public DocumentPipelineRegistry getIfAvailable() {
            return registryHolder[0];
          }

          @Override
          public DocumentPipelineRegistry getIfUnique() {
            return registryHolder[0];
          }
        };
    MailDocumentPipeline mailPipeline =
        new MailDocumentPipeline(provider, chunkingService, properties);

    TikaFallbackPipeline fallback =
        new TikaFallbackPipeline(new DocumentService(), defaultChunkingService());
    TabularDocumentPipeline tabular =
        new TabularDocumentPipeline(new TabularProperties(0, 0, 0, 0));
    List<DocumentPipeline> pipelines = new java.util.ArrayList<>();
    pipelines.add(fallback);
    pipelines.add(tabular);
    pipelines.add(mailPipeline);
    pipelines.addAll(List.of(extraPipelines));
    registryHolder[0] = new DocumentPipelineRegistry(pipelines, fallback);
    return mailPipeline;
  }

  /** A stand-in for a sub-pipeline that throws, mirroring {@code DocumentPipelineRegistryTest}. */
  private record FakePipeline(
      String id, short version, java.util.Set<String> handledFormats, RuntimeException toThrow)
      implements DocumentPipeline {

    @Override
    public DocumentPipelineResult run(DocumentPipelineSource source) {
      throw toThrow;
    }
  }

  @Test
  void claimsExactlyEmlAndMsg() {
    MailDocumentPipeline pipeline = pipeline(defaultProperties);
    assertThat(pipeline.handledFormats()).containsExactlyInAnyOrder(".eml", ".msg");
    assertThat(pipeline.id()).isEqualTo("email");
    assertThat(pipeline.version()).isEqualTo((short) 1);
  }

  @Test
  void passesThroughLocationAndAllFourMailKopfdatenKeys() {
    // #1107 neutrality guard: the exact key set FileProcessingService#storeChunks hardcoded before
    // this pipeline declared its own passthrough keys (#1101's four mail_* lines, plus location).
    MailDocumentPipeline pipeline = pipeline(defaultProperties);
    assertThat(pipeline.passthroughMetadataKeys())
        .containsExactlyInAnyOrder(
            ChunkingService.LOCATION_METADATA_KEY,
            ChunkMailMetadata.MAIL_FROM_METADATA_KEY,
            ChunkMailMetadata.MAIL_TO_METADATA_KEY,
            ChunkMailMetadata.MAIL_SUBJECT_METADATA_KEY,
            ChunkMailMetadata.MAIL_DATE_METADATA_KEY);
  }

  // --- EML: headers as metadata, not text -----------------------------------------------------

  @Test
  void headersLandAsChunkMetadataNeverAsChunkText() throws Exception {
    Path file = writeEml(simpleEmlBytes());

    DocumentPipelineResult result =
        pipeline(defaultProperties).run(DocumentPipelineSource.ofFile(file, "anfrage.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    Document chunk = result.chunks().getFirst();
    assertThat(chunk.getText())
        .contains("Bitte pruefen Sie den Antrag.")
        .doesNotContain("Max Mustermann")
        .doesNotContain("Anfrage Bauantrag");
    assertThat(chunk.getMetadata().get(ChunkMailMetadata.MAIL_SUBJECT_METADATA_KEY))
        .isEqualTo("Anfrage Bauantrag");
    assertThat(chunk.getMetadata().get(ChunkMailMetadata.MAIL_FROM_METADATA_KEY))
        .isEqualTo("Max Mustermann <max@example.org>");
    assertThat(chunk.getMetadata().get(ChunkMailMetadata.MAIL_TO_METADATA_KEY))
        .isEqualTo("Erika Musterfrau <erika@example.org>");
    assertThat(chunk.getMetadata()).containsKey(ChunkMailMetadata.MAIL_DATE_METADATA_KEY);
    // Single message, no thread split: no "Nachricht n von N" location needed.
    assertThat(chunk.getMetadata()).doesNotContainKey(ChunkingService.LOCATION_METADATA_KEY);
    // A produced key that is part of the registry-wide passthrough union must be declared - only a
    // union key can ever ride along onto the persisted chunk.
    Set<String> actualKeysInUnion =
        result.chunks().stream()
            .flatMap(c -> c.getMetadata().keySet().stream())
            .filter(PassthroughMetadataKeysTestSupport.REGISTRY_UNION::contains)
            .collect(toSet());
    assertThat(pipeline(defaultProperties).passthroughMetadataKeys())
        .containsAll(actualKeysInUnion);
  }

  @Test
  void aBlankBodyWithNoAttachmentsHasNoExtractableText() throws Exception {
    Message message =
        newMessageBuilder("Leer", "a@example.org", "b@example.org")
            .setBody("", StandardCharsets.UTF_8)
            .build();
    Path file = writeEml(DefaultMessageWriter.asBytes(message));

    DocumentPipelineResult result =
        pipeline(defaultProperties).run(DocumentPipelineSource.ofFile(file, "leer.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_EXTRACTABLE_TEXT);
  }

  // --- EML: attachments run through their own pipeline --------------------------------------

  @Test
  void aPdfAttachmentIsRoutedThroughItsOwnPipelineWithATraceableLocation() throws Exception {
    byte[] pdfBytes = readTestResource("test-document.pdf");
    Message message =
        newMessageBuilder("Anfrage mit Anlage", "max@example.org", "erika@example.org")
            .setBody(
                MultipartBuilder.create("mixed")
                    .addTextPart("Anbei der Antrag.", StandardCharsets.UTF_8)
                    .addBodyPart(
                        BodyPartBuilder.create()
                            .setBody(pdfBytes, "application/pdf")
                            .setContentDisposition("attachment", "antrag.pdf"))
                    .build())
            .build();
    Path file = writeEml(DefaultMessageWriter.asBytes(message));

    DocumentPipelineResult result =
        pipeline(defaultProperties).run(DocumentPipelineSource.ofFile(file, "mit-anlage.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    // The mail body chunk plus at least one chunk from the PDF's own pipeline.
    assertThat(result.chunks()).hasSizeGreaterThanOrEqualTo(2);
    Document bodyChunk = result.chunks().getFirst();
    assertThat(bodyChunk.getText()).contains("Anbei der Antrag.");
    Document attachmentChunk = result.chunks().get(1);
    assertThat(attachmentChunk.getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .asString()
        .startsWith("Anhang: antrag.pdf");
    // The attachment's own chunk carries no mail Kopfdaten - those belong to the message, not to
    // an attachment routed through a different pipeline entirely.
    assertThat(attachmentChunk.getMetadata())
        .doesNotContainKey(ChunkMailMetadata.MAIL_SUBJECT_METADATA_KEY);
  }

  @Test
  void anUnsupportedAttachmentFormatIsSkippedNotFailed() throws Exception {
    Message message =
        newMessageBuilder("Mit unbekanntem Anhang", "max@example.org", "erika@example.org")
            .setBody(
                MultipartBuilder.create("mixed")
                    .addTextPart("Siehe Anhang.", StandardCharsets.UTF_8)
                    .addBodyPart(
                        BodyPartBuilder.create()
                            .setBody(
                                "unbekannter Binaerinhalt".getBytes(StandardCharsets.UTF_8),
                                "application/x-unknown")
                            .setContentDisposition("attachment", "programm.exe"))
                    .build())
            .build();
    Path file = writeEml(DefaultMessageWriter.asBytes(message));

    DocumentPipelineResult result =
        pipeline(defaultProperties).run(DocumentPipelineSource.ofFile(file, "unbekannt.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText()).contains("Siehe Anhang.");
  }

  // --- EML: nested EML-in-EML ------------------------------------------------------------------

  @Test
  void aNestedEmlAttachmentIsParsedRecursivelyWithItsOwnHeaders() throws Exception {
    Message forwarded =
        newMessageBuilder("Urspruengliche Anfrage", "buerger@example.org", "amt@example.org")
            .setBody("Ich beantrage eine Baugenehmigung.", StandardCharsets.UTF_8)
            .build();
    Message outer =
        newMessageBuilder("WG: Urspruengliche Anfrage", "amt@example.org", "kollege@example.org")
            .setBody(
                MultipartBuilder.create("mixed")
                    .addTextPart("Zur Kenntnis.", StandardCharsets.UTF_8)
                    .addBodyPart(
                        BodyPartBuilder.create()
                            .setBody(forwarded)
                            .setContentDisposition("attachment", "weitergeleitet.eml"))
                    .build())
            .build();
    Path file = writeEml(DefaultMessageWriter.asBytes(outer));

    DocumentPipelineResult result =
        pipeline(new MailProperties(5, 0, 0, 0))
            .run(DocumentPipelineSource.ofFile(file, "weiterleitung.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().get(0).getText()).contains("Zur Kenntnis.");
    Document nestedChunk = result.chunks().get(1);
    assertThat(nestedChunk.getText()).contains("Ich beantrage eine Baugenehmigung.");
    assertThat(nestedChunk.getMetadata().get(ChunkMailMetadata.MAIL_SUBJECT_METADATA_KEY))
        .isEqualTo("Urspruengliche Anfrage");
    assertThat(nestedChunk.getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .asString()
        .startsWith("Anhang: weitergeleitet.eml");
  }

  @Test
  void nestedEmlAttachmentsBeyondTheConfiguredDepthAreSkipped() throws Exception {
    Message innermost =
        newMessageBuilder("Ebene 2", "a@example.org", "b@example.org")
            .setBody("Tiefste Ebene.", StandardCharsets.UTF_8)
            .build();
    Message middle =
        newMessageBuilder("Ebene 1", "a@example.org", "b@example.org")
            .setBody(
                MultipartBuilder.create("mixed")
                    .addTextPart("Mittlere Ebene.", StandardCharsets.UTF_8)
                    .addBodyPart(
                        BodyPartBuilder.create()
                            .setBody(innermost)
                            .setContentDisposition("attachment", "ebene2.eml"))
                    .build())
            .build();
    Message outer =
        newMessageBuilder("Ebene 0", "a@example.org", "b@example.org")
            .setBody(
                MultipartBuilder.create("mixed")
                    .addTextPart("Oberste Ebene.", StandardCharsets.UTF_8)
                    .addBodyPart(
                        BodyPartBuilder.create()
                            .setBody(middle)
                            .setContentDisposition("attachment", "ebene1.eml"))
                    .build())
            .build();
    Path file = writeEml(DefaultMessageWriter.asBytes(outer));

    // Depth 1: the outer message and its direct attachment (Ebene 1) are read, but Ebene 1's own
    // nested attachment (Ebene 2) is one level too deep and is skipped.
    DocumentPipelineResult result =
        pipeline(new MailProperties(1, 0, 0, 0))
            .run(DocumentPipelineSource.ofFile(file, "tief.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    List<String> texts = result.chunks().stream().map(Document::getText).toList();
    assertThat(texts).anyMatch(t -> t.contains("Oberste Ebene."));
    assertThat(texts).anyMatch(t -> t.contains("Mittlere Ebene."));
    assertThat(texts).noneMatch(t -> t.contains("Tiefste Ebene."));
  }

  @Test
  void attachmentsBeyondTheConfiguredCountPerMessageAreSkipped() throws Exception {
    MultipartBuilder multipart = MultipartBuilder.create("mixed");
    multipart.addTextPart("Drei Anhaenge, aber nur zwei erlaubt.", StandardCharsets.UTF_8);
    for (int i = 1; i <= 3; i++) {
      multipart.addBodyPart(
          BodyPartBuilder.create()
              .setBody(("Inhalt " + i).getBytes(StandardCharsets.UTF_8), "text/csv")
              .setContentDisposition("attachment", "datei" + i + ".csv"));
    }
    Message message =
        newMessageBuilder("Viele Anhaenge", "a@example.org", "b@example.org")
            .setBody(multipart.build())
            .build();
    Path file = writeEml(DefaultMessageWriter.asBytes(message));

    DocumentPipelineResult result =
        pipeline(new MailProperties(0, 2, 0, 0))
            .run(DocumentPipelineSource.ofFile(file, "viele.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    // Body chunk plus exactly two of the three CSV attachments (each a single-row chunk).
    assertThat(result.chunks()).hasSize(3);
  }

  @Test
  void anAttachmentExceedingTheSizeLimitIsSkippedButTheBodyStillIndexes() throws Exception {
    byte[] pdfBytes = readTestResource("test-document.pdf");
    Message message =
        newMessageBuilder("Grosser Anhang", "a@example.org", "b@example.org")
            .setBody(
                MultipartBuilder.create("mixed")
                    .addTextPart("Der Anhang ist zu gross.", StandardCharsets.UTF_8)
                    .addBodyPart(
                        BodyPartBuilder.create()
                            .setBody(pdfBytes, "application/pdf")
                            .setContentDisposition("attachment", "gross.pdf"))
                    .build())
            .build();
    Path file = writeEml(DefaultMessageWriter.asBytes(message));

    DocumentPipelineResult result =
        pipeline(new MailProperties(0, 0, 10, 0)) // 10 bytes - smaller than the PDF fixture
            .run(DocumentPipelineSource.ofFile(file, "gross.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText()).contains("Der Anhang ist zu gross.");
  }

  // --- EML: thread splitting --------------------------------------------------------------------

  @Test
  void aQuotedReplyThreadSplitsIntoOneChunkPerMessageInTheThread() throws Exception {
    String body =
        "Passt, danke.\n\nAm 03.01.2024 um 10:15 schrieb Erika Musterfrau <erika@example.org>:\n"
            + "Bitte um Rueckmeldung bis Freitag.";
    Message message =
        newMessageBuilder("Terminabstimmung", "max@example.org", "erika@example.org")
            .setBody(body, StandardCharsets.UTF_8)
            .build();
    Path file = writeEml(DefaultMessageWriter.asBytes(message));

    DocumentPipelineResult result =
        pipeline(defaultProperties).run(DocumentPipelineSource.ofFile(file, "thread.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().get(0).getText()).isEqualTo("Passt, danke.");
    assertThat(result.chunks().get(0).getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("Nachricht 1 von 2");
    assertThat(result.chunks().get(1).getText()).contains("Bitte um Rueckmeldung bis Freitag.");
    assertThat(
            result.chunks().get(1).getMetadata().get(ChunkMailMetadata.MAIL_SUBJECT_METADATA_KEY))
        .isEqualTo("Terminabstimmung");
  }

  @Test
  void aSegmentTooLongForOneChunkFallsBackToTokenSplittingWithoutFailingTheDocument()
      throws Exception {
    // #1101 review, finding 2: a long body with no recognizable quote separator must not become
    // one unboundedly large chunk - a tiny configured chunk-size here stands in for "a real
    // newsletter exceeding the embedding model's own token limit" without needing a multi-MB
    // fixture.
    String longBody = "Wort ".repeat(400).strip();
    Message message =
        newMessageBuilder("Langer Rundbrief", "amt@example.org", "verteiler@example.org")
            .setBody(longBody, StandardCharsets.UTF_8)
            .build();
    Path file = writeEml(DefaultMessageWriter.asBytes(message));
    ChunkingService tinyChunking =
        new ChunkingService(
            new IndexingProperties(20, 5, 50, null, null, List.of(), null, null, null, 1));

    DocumentPipelineResult result =
        pipeline(defaultProperties, tinyChunking)
            .run(DocumentPipelineSource.ofFile(file, "rundbrief.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSizeGreaterThan(1);
    // Every further-split piece still carries the message's own Kopfdaten and a disambiguating
    // "Teil j von M" Fundort.
    assertThat(result.chunks())
        .allSatisfy(
            chunk -> {
              assertThat(chunk.getMetadata().get(ChunkMailMetadata.MAIL_SUBJECT_METADATA_KEY))
                  .isEqualTo("Langer Rundbrief");
              assertThat(chunk.getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
                  .asString()
                  .startsWith("Teil ");
            });
  }

  @Test
  void anOrdinaryShortMessageStaysExactlyOneChunkRegardlessOfTheTokenSplitterFallback()
      throws Exception {
    // The fallback in the previous test must be a no-op for the common case - an ordinary message
    // well under the configured chunk-size still becomes exactly one chunk.
    Path file = writeEml(simpleEmlBytes());

    DocumentPipelineResult result =
        pipeline(defaultProperties).run(DocumentPipelineSource.ofFile(file, "anfrage.eml"));

    assertThat(result.chunks()).hasSize(1);
  }

  // --- Message-size cap (#1101 review, finding 3b) --------------------------------------------

  @Test
  void aMessageFileExceedingTheConfiguredSizeLimitIsSkippedBeforeParsing() throws Exception {
    Path file = writeEml(simpleEmlBytes());
    long fileSize = Files.size(file);

    DocumentPipelineResult result =
        pipeline(new MailProperties(0, 0, 0, fileSize - 1))
            .run(DocumentPipelineSource.ofFile(file, "zu-gross.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_CONTENT);
  }

  @Test
  void aMessageFileUnderTheConfiguredSizeLimitIsParsedNormally() throws Exception {
    Path file = writeEml(simpleEmlBytes());
    long fileSize = Files.size(file);

    DocumentPipelineResult result =
        pipeline(new MailProperties(0, 0, 0, fileSize + 1))
            .run(DocumentPipelineSource.ofFile(file, "passt.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
  }

  // --- Attachment robustness (#1101 review, finding 4) ----------------------------------------

  @Test
  void aFailingSubPipelineSkipsOnlyThatAttachmentNotTheWholeMessage() throws Exception {
    Message message =
        newMessageBuilder("Mit defektem Anhang", "a@example.org", "b@example.org")
            .setBody(
                MultipartBuilder.create("mixed")
                    .addTextPart("Der Anhang ist kaputt.", StandardCharsets.UTF_8)
                    .addBodyPart(
                        BodyPartBuilder.create()
                            .setBody("Inhalt".getBytes(StandardCharsets.UTF_8), "text/csv")
                            .setContentDisposition("attachment", "kaputt.csv"))
                    .build())
            .build();
    Path file = writeEml(DefaultMessageWriter.asBytes(message));
    // Claims .csv itself, in a registry built without the shared helper's own tabular pipeline
    // (which would otherwise also claim .csv and collide) - see pipelineWithFailingCsvPipeline.
    FakePipeline throwingOnCsv =
        new FakePipeline(
            "broken",
            (short) 1,
            java.util.Set.of(".csv"),
            new IllegalStateException("simulated sub-pipeline failure"));

    DocumentPipelineResult result = pipelineWithFailingCsvPipeline(file, throwingOnCsv);

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText()).contains("Der Anhang ist kaputt.");
  }

  /**
   * Builds a registry with {@code throwingOnCsv} as the sole claimant of {@code .csv} (no tabular
   * pipeline, which would otherwise collide) and runs {@code file} through it - the "a sub-pipeline
   * failure costs only the attachment" regression (#1101 review, finding 4a) needs a pipeline that
   * actually throws, which neither the fallback nor the tabular pipeline ever does for well-formed
   * input.
   */
  private DocumentPipelineResult pipelineWithFailingCsvPipeline(
      Path file, FakePipeline throwingOnCsv) {
    DocumentPipelineRegistry[] registryHolder = new DocumentPipelineRegistry[1];
    ObjectProvider<DocumentPipelineRegistry> provider =
        new ObjectProvider<>() {
          @Override
          public DocumentPipelineRegistry getObject() {
            return registryHolder[0];
          }

          @Override
          public DocumentPipelineRegistry getIfAvailable() {
            return registryHolder[0];
          }

          @Override
          public DocumentPipelineRegistry getIfUnique() {
            return registryHolder[0];
          }
        };
    MailDocumentPipeline mailPipeline =
        new MailDocumentPipeline(provider, defaultChunkingService(), defaultProperties);
    TikaFallbackPipeline fallback =
        new TikaFallbackPipeline(new DocumentService(), defaultChunkingService());
    registryHolder[0] =
        new DocumentPipelineRegistry(List.of(fallback, mailPipeline, throwingOnCsv), fallback);
    return mailPipeline.run(DocumentPipelineSource.ofFile(file, file.getFileName().toString()));
  }

  @Test
  void anAttachmentWithAnUnsafeFileNameIsSkippedNotFailed() throws Exception {
    // #1101 review, finding 4c: a colon is invalid in a Windows temp-file suffix and used to throw
    // InvalidPathException, failing the whole message.
    Message message =
        newMessageBuilder("Mit unsicherem Dateinamen", "a@example.org", "b@example.org")
            .setBody(
                MultipartBuilder.create("mixed")
                    .addTextPart("Siehe Anhang.", StandardCharsets.UTF_8)
                    .addBodyPart(
                        BodyPartBuilder.create()
                            .setBody("Inhalt".getBytes(StandardCharsets.UTF_8), "text/csv")
                            .setContentDisposition("attachment", "bericht.q1:2024"))
                    .build())
            .build();
    Path file = writeEml(DefaultMessageWriter.asBytes(message));

    DocumentPipelineResult result =
        pipeline(defaultProperties).run(DocumentPipelineSource.ofFile(file, "unsicher.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText()).contains("Siehe Anhang.");
  }

  // --- MSG: real fixtures --------------------------------------------------------------------

  @Test
  void aSimpleMsgFixtureYieldsSubjectFromToAndBodyAsMetadataAndText() throws Exception {
    Path file = testResourceCopy("mail/simple_test_msg.msg", "simple.msg");

    DocumentPipelineResult result =
        pipeline(defaultProperties).run(DocumentPipelineSource.ofFile(file, "simple.msg"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    Document chunk = result.chunks().getFirst();
    assertThat(chunk.getText()).contains("This is a test message.");
    assertThat(chunk.getMetadata().get(ChunkMailMetadata.MAIL_SUBJECT_METADATA_KEY))
        .isEqualTo("test message");
    assertThat(chunk.getMetadata().get(ChunkMailMetadata.MAIL_FROM_METADATA_KEY))
        .isEqualTo("Travis Ferguson");
    assertThat(chunk.getMetadata().get(ChunkMailMetadata.MAIL_TO_METADATA_KEY))
        .isEqualTo("travis@overwrittenstack.com");
  }

  @Test
  void aMsgFixtureWithAPdfAttachmentIndexesTheAttachmentAndSkipsTheEmbeddedMessageAttachment()
      throws Exception {
    Path file = testResourceCopy("mail/attachment_msg_pdf.msg", "attachment.msg");

    DocumentPipelineResult result =
        pipeline(defaultProperties).run(DocumentPipelineSource.ofFile(file, "attachment.msg"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    Document bodyChunk = result.chunks().getFirst();
    assertThat(bodyChunk.getText()).contains("Test email with 1 msg attachment, 1 pdf");
    // One real (PDF) attachment plus the body chunk - the fixture's second attachment chunk is an
    // embedded Outlook item, which MsgReader documents skipping rather than failing on, so it
    // contributes no chunk of its own.
    assertThat(result.chunks())
        .anySatisfy(
            chunk ->
                assertThat(chunk.getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
                    .asString()
                    .startsWith("Anhang: smbprn"));
  }

  // --- helpers -----------------------------------------------------------------------------

  private static Message.Builder newMessageBuilder(String subject, String from, String to)
      throws Exception {
    return Message.Builder.of()
        .setSubject(subject)
        .setFrom(from)
        .setTo(to)
        .setDate(Date.from(Instant.parse("2024-01-03T09:15:00Z")));
  }

  private static byte[] simpleEmlBytes() throws Exception {
    Message message =
        newMessageBuilder(
                "Anfrage Bauantrag",
                "Max Mustermann <max@example.org>",
                "Erika Musterfrau <erika@example.org>")
            .setBody("Bitte pruefen Sie den Antrag.", StandardCharsets.UTF_8)
            .build();
    return DefaultMessageWriter.asBytes(message);
  }

  private Path writeEml(byte[] bytes) throws IOException {
    Path file = tempDir.resolve("message-" + System.nanoTime() + ".eml");
    Files.write(file, bytes);
    return file;
  }

  private static byte[] readTestResource(String name) throws IOException {
    try (var in =
        MailDocumentPipelineTest.class
            .getClassLoader()
            .getResourceAsStream("test-documents/" + name)) {
      assertThat(in).as("Test resource %s must exist", name).isNotNull();
      return in.readAllBytes();
    }
  }

  private Path testResourceCopy(String resourceName, String targetName) throws IOException {
    Path file = tempDir.resolve(targetName);
    try (var in =
        MailDocumentPipelineTest.class
            .getClassLoader()
            .getResourceAsStream("test-documents/" + resourceName)) {
      assertThat(in).as("Test resource %s must exist", resourceName).isNotNull();
      Files.copy(in, file);
    }
    return file;
  }
}
