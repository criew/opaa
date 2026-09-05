package io.opaa.indexing.pipeline.mail;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.metadata.FormatMetadataField;
import io.opaa.indexing.pipeline.DiscoveredAttachment;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.DocumentProperties;
import io.opaa.indexing.pipeline.PassthroughMetadataKeysTestSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
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

/**
 * The EML/MSG pipeline (ingestion-pipelines.md Teil 3, Punkt 5): Kopfdaten land as chunk metadata
 * rather than chunk text, one chunk per message (or per thread segment). An attachment is never
 * recursively processed by this class (ADR-0022, Entscheidung 10) - it is reported via {@link
 * DocumentPipelineResult#discoveredAttachments()} instead, for the generalized attachment path
 * ({@code io.opaa.indexing.source.attachment.AttachmentIndexer}, driven by {@code
 * FileProcessingService}) to turn into its own {@code Document}. Recursion (Mail-in-Mail),
 * attachment-count/depth limits and format admission for an attachment therefore all live one level
 * up - see {@code AttachmentIndexerTest}/{@code FileProcessingServiceTest} for that coverage
 * instead.
 *
 * <p>EML fixtures are built at test time through mime4j's own writer ({@link DefaultMessageWriter})
 * - a real, spec-shaped MIME message rather than a hand-computed byte literal, mirroring how {@code
 * TabularDocumentPipelineTest} builds its XLSX fixtures through Apache POI rather than a static
 * binary file. The two {@code .msg} fixtures are real files instead (Apache POI offers no MSG
 * writer - see {@code test-documents/mail/NOTICE.md}).
 */
class MailDocumentPipelineTest {

  @TempDir Path tempDir;

  private final MailProperties defaultProperties = new MailProperties(0, 0, 0);

  /**
   * A fixed non-UTC zone (winter CET, UTC+1): the leading context line's Datum must render in this
   * zone, not UTC, or a message sent at German local time gets a silently wrong hour in embedding,
   * full-text index and cited Beleg alike.
   */
  private static final Clock TEST_CLOCK = Clock.fixed(Instant.EPOCH, ZoneId.of("Europe/Berlin"));

  /**
   * A generous but still meaningfully tight upper bound for one chunk under the 1000-token {@code
   * opaa.indexing.chunk-size} the round-mail tests configure - well under the ~19354 characters an
   * unbounded header block would produce, but well above an ordinary chunk, so it fails only on an
   * actually unbounded chunk, not on chunk-size noise.
   */
  private static final int MAX_CHUNK_CHARS_FOR_CONFIGURED_SIZE = 6000;

  private static ChunkingService defaultChunkingService() {
    return new ChunkingService(new IndexingProperties(1000, 100, 50, null, null, null, null, 1));
  }

  private MailDocumentPipeline pipeline(MailProperties properties) {
    return pipeline(properties, defaultChunkingService());
  }

  private MailDocumentPipeline pipeline(
      MailProperties properties, ChunkingService chunkingService) {
    return new MailDocumentPipeline(chunkingService, properties, TEST_CLOCK);
  }

  @Test
  void claimsExactlyEmlAndMsg() {
    MailDocumentPipeline pipeline = pipeline(defaultProperties);
    assertThat(pipeline.handledFormats()).containsExactlyInAnyOrder(".eml", ".msg");
    assertThat(pipeline.id()).isEqualTo("email");
    assertThat(pipeline.version()).isEqualTo((short) 5);
  }

  /**
   * regression guard for #1242: the Kopfdaten are schema values of the document, not chunk keys of
   * this pipeline - only the Fundort passes through, like every other pipeline.
   */
  @Test
  void passesThroughTheFundortAndNoMailSpecificKeyAtAll() {
    MailDocumentPipeline pipeline = pipeline(defaultProperties);
    assertThat(pipeline.passthroughMetadataKeys())
        .containsExactly(ChunkingService.LOCATION_METADATA_KEY);
  }

  /**
   * ADR-0024: Betreff is the title, the Date header the document's own date - as a calendar day in
   * the pipeline's clock zone (a 23:30 UTC mail is the next day in Europe/Berlin), the same zone
   * the context line renders in.
   */
  @Test
  void readsBetreffAndDateHeaderAsDocumentPropertiesInTheClockZone() throws Exception {
    Message message =
        newMessageBuilder(
                "Anfrage Bauantrag",
                "Max Mustermann <max@example.org>",
                "Erika Musterfrau <erika@example.org>")
            .setDate(Date.from(Instant.parse("2024-01-03T23:30:00Z")))
            .setBody("Bitte pruefen Sie den Antrag.", StandardCharsets.UTF_8)
            .build();
    Path file = writeEml(DefaultMessageWriter.asBytes(message));
    DocumentPipelineSource source = DocumentPipelineSource.ofFile(file, "anfrage.eml");

    io.opaa.indexing.pipeline.DocumentProperties properties =
        pipeline(defaultProperties).readProperties(source);

    assertThat(properties.title()).isEqualTo("Anfrage Bauantrag");
    assertThat(properties.documentDate()).isEqualTo(java.time.LocalDate.of(2024, 1, 4));
    assertThat(properties.firstHeading()).isNull();
    // #1242: the Kopfdaten as format field values - the Absender reduced to its bare address.
    assertThat(properties.formatFields())
        .containsEntry(FormatMetadataField.MAIL_SENDER.key(), "max@example.org")
        .containsEntry(
            FormatMetadataField.MAIL_RECIPIENTS.key(), "Erika Musterfrau <erika@example.org>")
        .containsEntry(FormatMetadataField.MAIL_SUBJECT.key(), "Anfrage Bauantrag");
    assertThat(pipeline(defaultProperties).run(source).properties()).isEqualTo(properties);
  }

  @Test
  void readPropertiesRespectsTheMessageSizeLimitAndSurvivesAnUnreadableFile() throws Exception {
    Path file = writeEml(simpleEmlBytes());
    long fileSize = Files.size(file);

    assertThat(
            pipeline(new MailProperties(0, 0, fileSize - 1))
                .readProperties(DocumentPipelineSource.ofFile(file, "zu-gross.eml")))
        .isEqualTo(io.opaa.indexing.pipeline.DocumentProperties.EMPTY);
    assertThat(
            pipeline(defaultProperties)
                .readProperties(
                    DocumentPipelineSource.ofFile(tempDir.resolve("fehlt.eml"), "fehlt.eml")))
        .isEqualTo(io.opaa.indexing.pipeline.DocumentProperties.EMPTY);
  }

  // --- EML: headers as metadata AND as context lines in the chunk text --------

  @Test
  void headersLandAsFormatFieldValuesAndAsContextLinesInTheChunkText() throws Exception {
    Path file = writeEml(simpleEmlBytes());

    DocumentPipelineResult result =
        pipeline(defaultProperties).run(DocumentPipelineSource.ofFile(file, "anfrage.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    Document chunk = result.chunks().getFirst();
    assertThat(chunk.getText())
        .isEqualTo(
            "Von: Max Mustermann <max@example.org>\n"
                + "Betreff: Anfrage Bauantrag\n"
                + "Datum: 03.01.2024 10:15\n"
                + "An: Erika Musterfrau <erika@example.org>\n"
                + "\n"
                + "Bitte pruefen Sie den Antrag.");
    assertThat(result.properties().formatFields())
        .containsEntry(FormatMetadataField.MAIL_SUBJECT.key(), "Anfrage Bauantrag")
        .containsEntry(FormatMetadataField.MAIL_SENDER.key(), "max@example.org")
        .containsEntry(
            FormatMetadataField.MAIL_RECIPIENTS.key(), "Erika Musterfrau <erika@example.org>");
    // #1242: no mail-specific chunk key survives - the Kopfdaten hang on the document now.
    assertThat(chunk.getMetadata().keySet()).doesNotContain("mail_from", "mail_subject");
    // Single message, no thread split: no "Nachricht n von N" location needed.
    assertThat(chunk.getMetadata()).doesNotContainKey(ChunkingService.LOCATION_METADATA_KEY);
    assertThat(result.discoveredAttachments()).isEmpty();
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

  /**
   * A message carrying no Kopfdaten at all (no From/To/Subject/Date) gets no context block and no
   * leading blank line - the guard against an empty-but-present header prefix.
   */
  @Test
  void aMessageWithoutAnyKopfdatenGetsNoContextBlock() throws Exception {
    Message message =
        Message.Builder.of()
            .setBody("Nur Fliesstext, keine Kopfdaten.", StandardCharsets.UTF_8)
            .build();
    Path file = writeEml(DefaultMessageWriter.asBytes(message));

    DocumentPipelineResult result =
        pipeline(defaultProperties).run(DocumentPipelineSource.ofFile(file, "ohne-kopf.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks().getFirst().getText()).isEqualTo("Nur Fliesstext, keine Kopfdaten.");
  }

  @Test
  void aBlankBodyWithNoAttachmentsAndNoKopfdatenAtAllHasNoExtractableText() throws Exception {
    Message message = Message.Builder.of().setBody("", StandardCharsets.UTF_8).build();
    Path file = writeEml(DefaultMessageWriter.asBytes(message));

    DocumentPipelineResult result =
        pipeline(defaultProperties).run(DocumentPipelineSource.ofFile(file, "leer.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_EXTRACTABLE_TEXT);
    assertThat(result.discoveredAttachments()).isEmpty();
  }

  /**
   * A blank body must not drop its Kopfdaten when the message carries an attachment - the common
   * "Anbei der Bescheid" mail - or the attachment would be indexed while sender and Betreff are
   * lost entirely.
   */
  @Test
  void aBlankBodyWithAnAttachmentGetsAHeaderOnlyChunk() throws Exception {
    Message message =
        newMessageBuilder("Leer", "a@example.org", "b@example.org")
            .setBody(
                MultipartBuilder.create("mixed")
                    .addTextPart("", StandardCharsets.UTF_8)
                    .addBodyPart(
                        BodyPartBuilder.create()
                            .setBody("Inhalt".getBytes(StandardCharsets.UTF_8), "text/csv")
                            .setContentDisposition("attachment", "bescheid.csv"))
                    .build())
            .build();
    Path file = writeEml(DefaultMessageWriter.asBytes(message));

    DocumentPipelineResult result =
        pipeline(defaultProperties).run(DocumentPipelineSource.ofFile(file, "leer.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    Document headerChunk = result.chunks().getFirst();
    assertThat(headerChunk.getText())
        .isEqualTo(
            "Von: a@example.org\n"
                + "Betreff: Leer\n"
                + "Datum: 03.01.2024 10:15\n"
                + "An: b@example.org");
    assertThat(result.properties().formatFields())
        .containsEntry(FormatMetadataField.MAIL_SUBJECT.key(), "Leer");
    assertThat(headerChunk.getMetadata()).doesNotContainKey(ChunkingService.LOCATION_METADATA_KEY);
    assertThat(result.discoveredAttachments()).hasSize(1);
    assertThat(result.discoveredAttachments().getFirst().fileName()).isEqualTo("bescheid.csv");
  }

  /**
   * Without any attachment, a blank body carries nothing of its own - its Kopfdaten are then
   * template text like a repeating page header, not evidence of content, and must not rescue the
   * document from {@code NO_EXTRACTABLE_TEXT} (mirrors {@code DocxDocumentPipeline}'s
   * "header/footer text never rescues this outcome").
   */
  @Test
  void aBlankBodyWithKopfdatenButNoAttachmentHasNoExtractableText() throws Exception {
    Message message =
        newMessageBuilder("Leer", "a@example.org", "b@example.org")
            .setBody("", StandardCharsets.UTF_8)
            .build();
    Path file = writeEml(DefaultMessageWriter.asBytes(message));

    DocumentPipelineResult result =
        pipeline(defaultProperties).run(DocumentPipelineSource.ofFile(file, "leer.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_EXTRACTABLE_TEXT);
  }

  /**
   * a message with neither body text nor Kopfdaten, but at least one attachment, still reports that
   * attachment - {@code DocumentPipelineResult}'s own contract reserves {@code
   * CHUNKED}-with-empty-chunks for the generalized attachment path, not this pipeline, but {@code
   * noExtractableText(List)} still carries the attachment for that path to pick up.
   */
  @Test
  void aBlankBodyWithNoKopfdatenButAnAttachmentStillReportsIt() throws Exception {
    Message message =
        Message.Builder.of()
            .setBody(
                MultipartBuilder.create("mixed")
                    .addTextPart("", StandardCharsets.UTF_8)
                    .addBodyPart(
                        BodyPartBuilder.create()
                            .setBody("Inhalt".getBytes(StandardCharsets.UTF_8), "text/csv")
                            .setContentDisposition("attachment", "anlage.csv"))
                    .build())
            .build();
    Path file = writeEml(DefaultMessageWriter.asBytes(message));

    DocumentPipelineResult result =
        pipeline(defaultProperties).run(DocumentPipelineSource.ofFile(file, "leer.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_EXTRACTABLE_TEXT);
    assertThat(result.chunks()).isEmpty();
    assertThat(result.discoveredAttachments()).hasSize(1);
    assertThat(result.discoveredAttachments().getFirst().fileName()).isEqualTo("anlage.csv");
  }

  /**
   * The cross case measured directly against this branch: a blank body, a PDF attachment, and
   * hundreds of recipients - the header-only chunk must run through the same token splitter as
   * every other header-bearing chunk, not bypass it - unsplit, this block produces one
   * 19354-character chunk.
   */
  @Test
  void aBlankBodyWithAnAttachmentAndHundredsOfRecipientsSplitsTheHeaderOnlyChunk()
      throws Exception {
    String[] recipients = new String[500];
    for (int i = 0; i < recipients.length; i++) {
      recipients[i] = "Empfaenger " + (i + 1) + " <empfaenger" + (i + 1) + "@amt.de>";
    }
    byte[] pdfBytes = readTestResource("test-document.pdf");
    Message message =
        Message.Builder.of()
            .setSubject("Grosser Verteiler")
            .setFrom("amt@example.org")
            .setTo(recipients)
            .setDate(Date.from(Instant.parse("2024-01-03T09:15:00Z")))
            .setBody(
                MultipartBuilder.create("mixed")
                    .addTextPart("", StandardCharsets.UTF_8)
                    .addBodyPart(
                        BodyPartBuilder.create()
                            .setBody(pdfBytes, "application/pdf")
                            .setContentDisposition("attachment", "bescheid.pdf"))
                    .build())
            .build();
    Path file = writeEml(DefaultMessageWriter.asBytes(message));
    ChunkingService realisticChunking =
        new ChunkingService(new IndexingProperties(1000, 100, 50, null, null, null, null, 1));

    DocumentPipelineResult result =
        pipeline(defaultProperties, realisticChunking)
            .run(DocumentPipelineSource.ofFile(file, "verteiler.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    // The whole message is header block: every chunk is a piece of it (#1242 - the Kopfdaten are
    // no longer a chunk key one could filter the pieces by).
    List<Document> headerChunks = result.chunks();
    assertThat(headerChunks).hasSizeGreaterThan(1);
    assertThat(headerChunks)
        .allSatisfy(
            chunk ->
                assertThat(chunk.getText().length())
                    .isLessThanOrEqualTo(MAX_CHUNK_CHARS_FOR_CONFIGURED_SIZE));
    assertThat(headerChunks.getFirst().getText()).startsWith("Von: amt@example.org");
    assertThat(result.discoveredAttachments()).hasSize(1);
  }

  // --- EML: attachments are reported, not processed inline --------------------------

  @Test
  void aPdfAttachmentIsReportedAsDiscoveredNotProcessedInline() throws Exception {
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
    // Only the mail body's own chunk - the attachment is no longer merged in.
    assertThat(result.chunks()).hasSize(1);
    Document bodyChunk = result.chunks().getFirst();
    assertThat(bodyChunk.getText()).contains("Anbei der Antrag.");
    assertThat(result.discoveredAttachments()).hasSize(1);
    DiscoveredAttachment attachment = result.discoveredAttachments().getFirst();
    assertThat(attachment.fileName()).isEqualTo("antrag.pdf");
    assertThat(Files.exists(attachment.tempFile())).isTrue();
    assertThat(Files.readAllBytes(attachment.tempFile())).isEqualTo(pdfBytes);
  }

  @Test
  void anAttachmentOfAnUnsupportedFormatIsStillReportedHere() throws Exception {
    // format admission is no longer this pipeline's job - AttachmentIndexer decides that
    // once, for every attachment path, instead of this class pre-filtering with duplicate logic.
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
    assertThat(result.discoveredAttachments()).hasSize(1);
    assertThat(result.discoveredAttachments().getFirst().fileName()).isEqualTo("programm.exe");
  }

  // --- EML: a nested EML-in-EML attachment is reported, not recursed into here --------

  @Test
  void aNestedEmlAttachmentIsReportedAsDiscoveredNotRecursedIntoHere() throws Exception {
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
        pipeline(new MailProperties(0, 0, 0))
            .run(DocumentPipelineSource.ofFile(file, "weiterleitung.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText()).contains("Zur Kenntnis.");
    // Mail-in-Mail recursion is the generalized attachment path's job now: running the
    // reported attachment's own bytes back through this same pipeline is exactly what
    // FileProcessingService#processUrlFile does once AttachmentIndexer routes it there.
    assertThat(result.discoveredAttachments()).hasSize(1);
    DiscoveredAttachment nested = result.discoveredAttachments().getFirst();
    assertThat(nested.fileName()).isEqualTo("weitergeleitet.eml");
    DocumentPipelineResult nestedResult =
        pipeline(new MailProperties(0, 0, 0))
            .run(DocumentPipelineSource.ofFile(nested.tempFile(), nested.fileName()));
    assertThat(nestedResult.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(nestedResult.chunks().getFirst().getText())
        .contains("Ich beantrage eine Baugenehmigung.");
    assertThat(nestedResult.properties().formatFields())
        .containsEntry(FormatMetadataField.MAIL_SUBJECT.key(), "Urspruengliche Anfrage");
  }

  @Test
  void attachmentsBeyondTheConfiguredCountPerMessageAreNeverExtractedAtAll() throws Exception {
    // The per-message extraction cap stays EmlReader's own job (unchanged here) - only the
    // recursion-depth cap moved to the generalized attachment path (ADR-0022, Entscheidung 6).
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
        pipeline(new MailProperties(2, 0, 0)).run(DocumentPipelineSource.ofFile(file, "viele.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.discoveredAttachments()).hasSize(2);
  }

  @Test
  void anAttachmentExceedingTheSizeLimitIsNeverExtractedButTheBodyStillIndexes() throws Exception {
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
        pipeline(new MailProperties(0, 10, 0)) // 10 bytes - smaller than the PDF fixture
            .run(DocumentPipelineSource.ofFile(file, "gross.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText()).contains("Der Anhang ist zu gross.");
    assertThat(result.discoveredAttachments()).isEmpty();
  }

  // --- EML: an unbounded header block is bounded by the token splitter -------

  /**
   * {@link ParsedMailMessage#to()} is unbounded - a round mail to hundreds of recipients must not
   * grow the first chunk past the configured chunk-size. Prepending the context block before {@link
   * ChunkingService#chunkDocuments} runs, rather than after, lets the same splitter cut it like any
   * other text.
   */
  @Test
  void aRoundMailWithHundredsOfRecipientsDoesNotGrowOneChunkUnboundedly() throws Exception {
    String[] recipients = new String[200];
    for (int i = 0; i < recipients.length; i++) {
      recipients[i] = "Empfaenger " + (i + 1) + " <empfaenger" + (i + 1) + "@amt.de>";
    }
    Message message =
        Message.Builder.of()
            .setSubject("Rundschreiben")
            .setFrom("amt@example.org")
            .setTo(recipients)
            .setDate(Date.from(Instant.parse("2024-01-03T09:15:00Z")))
            .setBody("Bitte beachten Sie die neue Regelung.", StandardCharsets.UTF_8)
            .build();
    Path file = writeEml(DefaultMessageWriter.asBytes(message));
    ChunkingService realisticChunking =
        new ChunkingService(new IndexingProperties(1000, 100, 50, null, null, null, null, 1));

    DocumentPipelineResult result =
        pipeline(defaultProperties, realisticChunking)
            .run(DocumentPipelineSource.ofFile(file, "rundschreiben.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    // A bound against the configured chunk-size, not merely against
    // the unbounded recipient list itself - a weaker bound let a several-thousand-character chunk
    // through undetected.
    assertThat(result.chunks()).hasSizeGreaterThan(1);
    assertThat(result.chunks())
        .allSatisfy(
            chunk ->
                assertThat(chunk.getText().length())
                    .isLessThanOrEqualTo(MAX_CHUNK_CHARS_FOR_CONFIGURED_SIZE));
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
    // The context block lands only on the first chunk - not repeated onto the
    // second thread segment, the Verwässerungsproblem a repeated page header shows.
    assertThat(result.chunks().get(0).getText()).endsWith("\n\nPasst, danke.");
    assertThat(result.chunks().get(0).getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("Nachricht 1 von 2");
    assertThat(result.chunks().get(1).getText())
        .doesNotContain("Von:")
        .contains("Bitte um Rueckmeldung bis Freitag.");
    assertThat(result.properties().formatFields())
        .containsEntry(FormatMetadataField.MAIL_SUBJECT.key(), "Terminabstimmung");
  }

  @Test
  void aSegmentTooLongForOneChunkFallsBackToTokenSplittingWithoutFailingTheDocument()
      throws Exception {
    // a long body with no recognizable quote separator must not become
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
        new ChunkingService(new IndexingProperties(20, 5, 50, null, null, null, null, 1));

    DocumentPipelineResult result =
        pipeline(defaultProperties, tinyChunking)
            .run(DocumentPipelineSource.ofFile(file, "rundbrief.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSizeGreaterThan(1);
    // Every further-split piece carries a disambiguating "Teil j von M" Fundort; the Kopfdaten
    // hang on the document and reach every chunk from there (#1242).
    assertThat(result.properties().formatFields())
        .containsEntry(FormatMetadataField.MAIL_SUBJECT.key(), "Langer Rundbrief");
    assertThat(result.chunks())
        .allSatisfy(
            chunk ->
                assertThat(chunk.getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
                    .asString()
                    .startsWith("Teil "));
    // The context block itself is subject to the same token splitter - it lands only in the
    // leading part, never repeated onto a later further-split piece.
    assertThat(result.chunks().getFirst().getText()).startsWith("Von: amt@example.org");
    assertThat(result.chunks().subList(1, result.chunks().size()))
        .allSatisfy(chunk -> assertThat(chunk.getText()).doesNotContain("Von:", "Betreff:"));
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

  // --- Message-size cap --------------------------------------------

  @Test
  void aMessageFileExceedingTheConfiguredSizeLimitIsSkippedBeforeParsing() throws Exception {
    Path file = writeEml(simpleEmlBytes());
    long fileSize = Files.size(file);

    DocumentPipelineResult result =
        pipeline(new MailProperties(0, 0, fileSize - 1))
            .run(DocumentPipelineSource.ofFile(file, "zu-gross.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.PARSE_FAILED);
  }

  @Test
  void aMessageFileUnderTheConfiguredSizeLimitIsParsedNormally() throws Exception {
    Path file = writeEml(simpleEmlBytes());
    long fileSize = Files.size(file);

    DocumentPipelineResult result =
        pipeline(new MailProperties(0, 0, fileSize + 1))
            .run(DocumentPipelineSource.ofFile(file, "passt.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
  }

  @Test
  void anAttachmentWithAnUnsafeFileNameIsStillNotExtracted() throws Exception {
    // a colon is invalid in a Windows temp-file suffix - EmlReader's own
    // extraction (unchanged here) still declines to create a temp file for it, so it never
    // reaches discoveredAttachments at all, but must not fail the whole message either.
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
    assertThat(result.properties().formatFields())
        .containsEntry(FormatMetadataField.MAIL_SUBJECT.key(), "test message")
        .containsEntry(FormatMetadataField.MAIL_RECIPIENTS.key(), "travis@overwrittenstack.com")
        // A display name without an address is no Absender: the field carries a checkable
        // identifier or nothing (#1242).
        .doesNotContainKey(FormatMetadataField.MAIL_SENDER.key());
  }

  @Test
  void aMsgFixtureWithAPdfAttachmentReportsIt() throws Exception {
    Path file = testResourceCopy("mail/attachment_msg_pdf.msg", "attachment.msg");

    DocumentPipelineResult result =
        pipeline(defaultProperties).run(DocumentPipelineSource.ofFile(file, "attachment.msg"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    Document bodyChunk = result.chunks().getFirst();
    assertThat(bodyChunk.getText()).contains("Test email with 1 msg attachment, 1 pdf");
    // The fixture's second attachment is an embedded Outlook item, which MsgReader documents
    // skipping rather than extracting - only the real PDF attachment is ever reported here.
    assertThat(result.discoveredAttachments())
        .anySatisfy(attachment -> assertThat(attachment.fileName()).startsWith("smbprn"));
  }

  // --- selective extraction materializes one attachment, not the whole message --------

  /**
   * an "Im Dokument oeffnen" click re-extracts one attachment without paying for every attachment
   * of the message in temporary files. With {@code attachmentIndex} set, exactly one temp file is
   * written - proven by counting this reader's own {@code opaa-mail-} files while the result is
   * still alive, before {@code DocumentPipelineRunner} deletes anything.
   */
  @Test
  void aFilteredRunWritesATempFileForTheRequestedAttachmentOnly() throws Exception {
    Path file =
        writeEml(
            DefaultMessageWriter.asBytes(
                messageWithAttachments(
                    List.of("eins.txt", "zwei.txt", "drei.txt"),
                    List.of("Erster.", "Zweiter.", "Dritter."))));

    Set<Path> before = readerTempFiles();
    DocumentPipelineResult result =
        pipeline(defaultProperties)
            .run(DocumentPipelineSource.ofFile(file, "viele-anlagen.eml").withAttachmentIndex(1));
    Set<Path> written = newReaderTempFiles(before, List.of("Erster.", "Zweiter.", "Dritter."));

    try {
      assertThat(written).hasSize(1);
      assertThat(result.discoveredAttachments()).hasSize(1);
      DiscoveredAttachment attachment = result.discoveredAttachments().getFirst();
      assertThat(attachment.fileName()).isEqualTo("zwei.txt");
      assertThat(Files.readAllBytes(attachment.tempFile()))
          .isEqualTo("Zweiter.".getBytes(StandardCharsets.UTF_8));
    } finally {
      deleteAll(written);
    }
  }

  /** An unfiltered run is unchanged: every attachment is still materialized and reported. */
  @Test
  void anUnfilteredRunStillWritesATempFileForEveryAttachment() throws Exception {
    Path file =
        writeEml(
            DefaultMessageWriter.asBytes(
                messageWithAttachments(
                    List.of("eins.txt", "zwei.txt", "drei.txt"),
                    List.of("Erster.", "Zweiter.", "Dritter."))));

    Set<Path> before = readerTempFiles();
    DocumentPipelineResult result =
        pipeline(defaultProperties).run(DocumentPipelineSource.ofFile(file, "viele-anlagen.eml"));
    Set<Path> written = newReaderTempFiles(before, List.of("Erster.", "Zweiter.", "Dritter."));

    try {
      assertThat(written).hasSize(3);
      assertThat(result.discoveredAttachments()).hasSize(3);
      assertThat(result.discoveredAttachments().stream().map(DiscoveredAttachment::fileName))
          .containsExactly("eins.txt", "zwei.txt", "drei.txt");
    } finally {
      deleteAll(written);
    }
  }

  /**
   * the extraction position is what an attachment row's own {@code file_path} stores - the list
   * position in {@code discoveredAttachments} - so a filtered run must number attachments exactly
   * as an unfiltered one does. An attachment the unfiltered run would not report at all (here: one
   * over {@code max-attachment-bytes}) therefore consumes no position, and position 1 is the third
   * part of this message, not the second.
   */
  @Test
  void aFilteredRunNumbersAttachmentsLikeAnUnfilteredOneIncludingSkippedOnes() throws Exception {
    String oversized = "x".repeat(200);
    Path file =
        writeEml(
            DefaultMessageWriter.asBytes(
                messageWithAttachments(
                    List.of("klein.txt", "zu-gross.txt", "auch-klein.txt"),
                    List.of("Erster.", oversized, "Dritter."))));
    MailProperties tightAttachmentLimit = new MailProperties(0, 100, 0);

    Set<Path> before = readerTempFiles();
    DocumentPipelineResult result =
        pipeline(tightAttachmentLimit)
            .run(DocumentPipelineSource.ofFile(file, "mit-zu-grosser.eml").withAttachmentIndex(1));
    Set<Path> written = newReaderTempFiles(before, List.of("Erster.", oversized, "Dritter."));

    try {
      assertThat(result.discoveredAttachments()).hasSize(1);
      assertThat(result.discoveredAttachments().getFirst().fileName()).isEqualTo("auch-klein.txt");
      assertThat(written).hasSize(1);
    } finally {
      deleteAll(written);
    }
  }

  /**
   * {@link MsgReader} counts positions in its own, separate loop, so the rule that a skipped
   * attachment consumes no position is nailed down for MSG as well - this fixture's embedded
   * Outlook item is skipped entirely (POI offers no writer for it), so the PDF behind it sits at
   * position 0, not 1.
   */
  @Test
  void aFilteredMsgRunSkipsTheEmbeddedItemWithoutConsumingItsPosition() throws Exception {
    Path file = testResourceCopy("mail/attachment_msg_pdf.msg", "gefiltert.msg");

    DocumentPipelineResult atZero =
        pipeline(defaultProperties)
            .run(DocumentPipelineSource.ofFile(file, "gefiltert.msg").withAttachmentIndex(0));
    try {
      assertThat(atZero.discoveredAttachments()).hasSize(1);
      assertThat(atZero.discoveredAttachments().getFirst().fileName()).startsWith("smbprn");
    } finally {
      deleteAll(
          atZero.discoveredAttachments().stream()
              .map(DiscoveredAttachment::tempFile)
              .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new)));
    }

    DocumentPipelineResult atOne =
        pipeline(defaultProperties)
            .run(DocumentPipelineSource.ofFile(file, "gefiltert.msg").withAttachmentIndex(1));
    try {
      assertThat(atOne.discoveredAttachments()).isEmpty();
    } finally {
      deleteAll(
          atOne.discoveredAttachments().stream()
              .map(DiscoveredAttachment::tempFile)
              .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new)));
    }
  }

  /**
   * {@code readProperties} needs only the Kopfdaten and never runs through {@code
   * DocumentPipelineRunner}, so any temp file it caused a reader to write would leak - it therefore
   * materializes no attachment at all.
   */
  @Test
  void readingPropertiesAloneWritesNoAttachmentTempFile() throws Exception {
    Path file =
        writeEml(
            DefaultMessageWriter.asBytes(
                messageWithAttachments(
                    List.of("eins.txt", "zwei.txt"), List.of("Erster.", "Zweiter."))));

    Set<Path> before = readerTempFiles();
    DocumentProperties properties =
        pipeline(defaultProperties)
            .readProperties(DocumentPipelineSource.ofFile(file, "mit-anlagen.eml"));
    Set<Path> written = newReaderTempFiles(before, List.of("Erster.", "Zweiter."));

    try {
      assertThat(properties.title()).isEqualTo("Mit Anlagen");
      assertThat(written).isEmpty();
    } finally {
      deleteAll(written);
    }
  }

  /** A position no attachment occupies reports nothing, rather than some other attachment. */
  @Test
  void aFilteredRunReportsNothingWhenNoAttachmentOccupiesThatPosition() throws Exception {
    Path file =
        writeEml(
            DefaultMessageWriter.asBytes(
                messageWithAttachments(List.of("eins.txt"), List.of("Erster."))));

    Set<Path> before = readerTempFiles();
    DocumentPipelineResult result =
        pipeline(defaultProperties)
            .run(DocumentPipelineSource.ofFile(file, "eine-anlage.eml").withAttachmentIndex(3));
    Set<Path> written = newReaderTempFiles(before, List.of("Erster."));

    try {
      assertThat(result.discoveredAttachments()).isEmpty();
      assertThat(written).isEmpty();
    } finally {
      deleteAll(written);
    }
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

  private static Message messageWithAttachments(List<String> fileNames, List<String> contents)
      throws Exception {
    MultipartBuilder body =
        MultipartBuilder.create("mixed").addTextPart("Anbei die Anlagen.", StandardCharsets.UTF_8);
    for (int i = 0; i < fileNames.size(); i++) {
      body.addBodyPart(
          BodyPartBuilder.create()
              .setBody(contents.get(i).getBytes(StandardCharsets.UTF_8), "text/plain")
              .setContentDisposition("attachment", fileNames.get(i)));
    }
    return newMessageBuilder("Mit Anlagen", "max@example.org", "erika@example.org")
        .setBody(body.build())
        .build();
  }

  /**
   * The temp files {@code MailAttachmentIo} creates live in the JVM's own shared temp directory, so
   * a run's own files are identified by diffing that directory around the call.
   */
  private static Set<Path> readerTempFiles() throws IOException {
    try (var entries = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
      return entries
          .filter(path -> path.getFileName().toString().startsWith("opaa-mail-"))
          .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));
    }
  }

  /**
   * The files the call between the two snapshots wrote, restricted to those carrying one of this
   * fixture's own attachment texts - Gradle runs this suite with {@code maxParallelForks = 2}, and
   * the other worker writes its own {@code opaa-mail-} files into the very same directory.
   */
  private static Set<Path> newReaderTempFiles(Set<Path> before, Collection<String> ownContents)
      throws IOException {
    Set<Path> after = readerTempFiles();
    after.removeAll(before);
    Set<Path> own = new java.util.HashSet<>();
    for (Path candidate : after) {
      String content;
      try {
        content = Files.readString(candidate, StandardCharsets.UTF_8);
      } catch (IOException e) {
        // A file the other worker wrote and deleted (or wrote as binary) between the snapshots.
        continue;
      }
      if (ownContents.contains(content)) {
        own.add(candidate);
      }
    }
    return own;
  }

  private static void deleteAll(Set<Path> files) throws IOException {
    for (Path file : files) {
      Files.deleteIfExists(file);
    }
  }
}
