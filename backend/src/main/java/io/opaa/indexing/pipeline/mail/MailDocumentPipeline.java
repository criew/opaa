package io.opaa.indexing.pipeline.mail;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.pipeline.DiscoveredAttachment;
import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.DocumentProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

/**
 * The EML/MSG pipeline (ingestion-pipelines.md, Teil 3, Punkt 5): separates Kopfdaten, body and
 * attachment text, which Tika's native parse flattens into one block. Kopfdaten land as chunk
 * metadata ({@link ChunkMailMetadata}) and as German-labeled context lines prepended to the first
 * body text <b>before</b> chunking, so an unbounded {@code An} line is cut by the same splitter. A
 * blank body with an attachment still gets a header-only chunk; without one it stays {@code
 * NO_EXTRACTABLE_TEXT}.
 *
 * <p>This class never processes an attachment itself (ADR-0022, Entscheidung 10) - it reports each
 * via {@link DocumentPipelineResult#discoveredAttachments()}, and recursion and the count/depth
 * limits live on the shared attachment path.
 */
public class MailDocumentPipeline implements DocumentPipeline {

  private static final Logger log = LoggerFactory.getLogger(MailDocumentPipeline.class);

  static final String ID = "email";

  static final short VERSION = 4;

  /**
   * Renders {@link ParsedMailMessage#date()} in the leading context line, in {@link #clock}'s own
   * zone - never a fixed one: the originating header's offset does not survive parsing into an
   * {@link java.time.Instant}, and rendering in UTC anyway would put a silently wrong local time
   * into embedding, full-text index and the cited Beleg alike.
   */
  private static final DateTimeFormatter DATE_FORMATTER =
      DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY);

  private final ChunkingService chunkingService;
  private final MailProperties properties;
  private final Clock clock;

  public MailDocumentPipeline(
      ChunkingService chunkingService, MailProperties properties, Clock clock) {
    this.chunkingService = chunkingService;
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public short version() {
    return VERSION;
  }

  @Override
  public Set<String> handledFormats() {
    return Set.of(".eml", ".msg");
  }

  /**
   * Adds {@link ChunkMailMetadata}'s Kopfdaten keys to the default {@link
   * DocumentPipeline#passthroughMetadataKeys()} - set only on a message's own body chunks (see
   * {@link #bodyChunks}); an attachment no longer produces a chunk of this pipeline's own at all ,
   * so there is no longer a recursively-produced chunk to exclude here.
   */
  @Override
  public Set<String> passthroughMetadataKeys() {
    return Set.of(
        ChunkingService.LOCATION_METADATA_KEY,
        ChunkMailMetadata.MAIL_FROM_METADATA_KEY,
        ChunkMailMetadata.MAIL_TO_METADATA_KEY,
        ChunkMailMetadata.MAIL_SUBJECT_METADATA_KEY,
        ChunkMailMetadata.MAIL_DATE_METADATA_KEY);
  }

  /**
   * Honours {@link DocumentPipelineSource#attachmentIndex()} by handing it to the reader: the
   * extraction order is unchanged, but only the requested attachment is written to a temp file and
   * reported - the whole message's worth of temporary disk an "open this attachment" click used to
   * cost collapses to that one file.
   */
  @Override
  public DocumentPipelineResult run(DocumentPipelineSource source) {
    if (source.file() == null) {
      // Never reached through the registry - routing to this pipeline only ever happens for a
      // file whose detected content is message/rfc822 or application/vnd.ms-outlook, both of
      // which require bytes to detect in the first place. Defensive, mirrors
      // TabularDocumentPipeline's identical guard.
      return DocumentPipelineResult.noExtractableText();
    }

    try {
      if (Files.size(source.file()) > properties.maxMessageBytes()) {
        // Checked before either reader ever runs - both build the message's full DOM in memory
        // (see MailProperties's own Javadoc), so this is the actual memory bound, not
        // maxAttachmentBytes further down. FileProcessingService#processFile enforces no
        // per-file size limit of its own (only the library's total storage quota).
        log.warn(
            "Skipping {}: {} bytes exceeds the configured limit of {} bytes"
                + " (opaa.indexing.mail.max-message-bytes)",
            source.fileName(),
            Files.size(source.file()),
            properties.maxMessageBytes());
        return DocumentPipelineResult.parseFailed();
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read mail document " + source.fileName(), e);
    }

    ParsedMailMessage message;
    try {
      message =
          ".msg".equals(source.effectiveExtension())
              ? MsgReader.read(source.file(), properties, source.attachmentIndex())
              : EmlReader.read(source.file(), properties, source.attachmentIndex());
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read mail document " + source.fileName(), e);
    }

    try {
      return resultOf(message, source.fileName());
    } catch (RuntimeException e) {
      // The readers have already written every attachment to a temp file; a failure from here on
      // never reaches a result that could carry them, and DocumentPipelineRunner only deletes what
      // a result reports - so this is the last owner of those files.
      deleteTempFiles(message.attachments());
      throw e;
    }
  }

  private DocumentPipelineResult resultOf(ParsedMailMessage message, String fileName) {
    String headerContext = headerContextText(message);
    List<Document> chunks = bodyChunks(message, fileName, headerContext);
    List<DiscoveredAttachment> discovered = discoveredAttachments(message.attachments());

    if (chunks.isEmpty()) {
      // A message with neither body text nor Kopfdaten, but at least one attachment - the rare
      // corner bodyChunks' own header-only rescue cannot cover (no headerContext to rescue with).
      // discoveredAttachments still carries the attachment: DocumentPipelineResult reserves
      // CHUNKED-with-empty-chunks for the generalized attachment path, not this pipeline's own
      // contract, but noExtractableText() can and does still report an attachment for that path to
      // pick up (see that factory's own Javadoc).
      return DocumentPipelineResult.noExtractableText(discovered);
    }
    long contentByteSize =
        headerContext.getBytes(StandardCharsets.UTF_8).length
            + message.bodyText().getBytes(StandardCharsets.UTF_8).length;
    return DocumentPipelineResult.chunked(chunks, discovered, contentByteSize)
        .withProperties(properties(message));
  }

  /**
   * A wanted index no attachment position can ever equal - {@link #readProperties} only needs the
   * Kopfdaten, and does not run through {@link io.opaa.indexing.pipeline.DocumentPipelineRunner},
   * so any temp file it caused a reader to write would never be deleted by anyone.
   */
  private static final int MATERIALIZE_NO_ATTACHMENT = -1;

  /** Betreff as the title and the Date header as the document's own date (ADR-0024). */
  @Override
  public DocumentProperties readProperties(DocumentPipelineSource source) {
    if (source.file() == null) {
      return DocumentProperties.EMPTY;
    }
    try {
      if (Files.size(source.file()) > properties.maxMessageBytes()) {
        return DocumentProperties.EMPTY;
      }
      ParsedMailMessage message =
          ".msg".equals(source.effectiveExtension())
              ? MsgReader.read(source.file(), properties, MATERIALIZE_NO_ATTACHMENT)
              : EmlReader.read(source.file(), properties, MATERIALIZE_NO_ATTACHMENT);
      return properties(message);
    } catch (IOException | RuntimeException e) {
      log.warn("Could not read mail properties of {}", source.fileName(), e);
      return DocumentProperties.EMPTY;
    }
  }

  private DocumentProperties properties(ParsedMailMessage message) {
    return DocumentProperties.EMPTY
        .withTitle(message.subject())
        .withDocumentDate(
            message.date() == null ? null : message.date().atZone(clock.getZone()).toLocalDate());
  }

  /**
   * One chunk per message, or one per thread segment when {@link MailThreadSplitter} finds a quoted
   * reply chain, every segment carrying the same single set of Kopfdaten metadata. {@code
   * headerContext} is prepended to the first non-blank segment before {@link
   * ChunkingService#chunkDocuments} runs, never onto a later one; an over-long segment falls back
   * to the token splitter. A blank body yields a header-only chunk only if an attachment exists.
   */
  private List<Document> bodyChunks(
      ParsedMailMessage message, String fileName, String headerContext) {
    boolean headerPending = !headerContext.isEmpty();
    List<String> segments = MailThreadSplitter.split(message.bodyText());
    List<Document> chunks = new ArrayList<>(segments.size());
    for (int i = 0; i < segments.size(); i++) {
      String text = segments.get(i).strip();
      if (text.isBlank()) {
        continue;
      }
      if (headerPending) {
        text = headerContext + "\n\n" + text;
        headerPending = false;
      }
      List<Document> parts = chunkingService.chunkDocuments(fileName, List.of(new Document(text)));
      for (int j = 0; j < parts.size(); j++) {
        Map<String, Object> metadata = kopfdatenMetadata(message);
        String location = locationFor(i, segments.size(), j, parts.size());
        if (location != null) {
          metadata.put(ChunkingService.LOCATION_METADATA_KEY, location);
        }
        chunks.add(new Document(parts.get(j).getText(), metadata));
      }
    }
    if (headerPending && !message.attachments().isEmpty()) {
      List<Document> headerParts =
          chunkingService.chunkDocuments(fileName, List.of(new Document(headerContext)));
      for (int j = 0; j < headerParts.size(); j++) {
        Map<String, Object> metadata = kopfdatenMetadata(message);
        String location = locationFor(0, 1, j, headerParts.size());
        if (location != null) {
          metadata.put(ChunkingService.LOCATION_METADATA_KEY, location);
        }
        chunks.add(new Document(headerParts.get(j).getText(), metadata));
      }
    }
    return chunks;
  }

  private static Map<String, Object> kopfdatenMetadata(ParsedMailMessage message) {
    Map<String, Object> metadata = new HashMap<>();
    if (message.subject() != null) {
      metadata.put(ChunkMailMetadata.MAIL_SUBJECT_METADATA_KEY, message.subject());
    }
    if (message.from() != null) {
      metadata.put(ChunkMailMetadata.MAIL_FROM_METADATA_KEY, message.from());
    }
    if (message.to() != null) {
      metadata.put(ChunkMailMetadata.MAIL_TO_METADATA_KEY, message.to());
    }
    if (message.date() != null) {
      metadata.put(ChunkMailMetadata.MAIL_DATE_METADATA_KEY, renderMailDate(message.date()));
    }
    return metadata;
  }

  /**
   * Renders {@code date} for {@link ChunkMailMetadata#MAIL_DATE_METADATA_KEY}, truncated to whole
   * seconds first: {@link Instant#toString()} omits a zero fractional part, so two messages a
   * millisecond apart would otherwise produce strings that do not compare correctly as text once a
   * Zeitraum filter compares {@code mail_date}. Only {@link MsgReader} can deliver sub-second
   * precision at all.
   */
  static String renderMailDate(Instant date) {
    return date.truncatedTo(ChronoUnit.SECONDS).toString();
  }

  /**
   * Renders Von/Betreff/Datum/An as German-labeled context lines, one per present field. {@code An}
   * comes last, against its natural reading order, because it is the one unbounded field - leading
   * with it would strand Betreff, Datum and the body past a long recipient list. The block is
   * prepended once, to the first non-blank segment and <b>before</b> chunking, never repeated: the
   * same information is already in every chunk's metadata, and repeating it would dilute ranking.
   */
  private String headerContextText(ParsedMailMessage message) {
    StringBuilder text = new StringBuilder();
    appendHeaderLine(text, "Von", message.from());
    appendHeaderLine(text, "Betreff", message.subject());
    if (message.date() != null) {
      appendHeaderLine(
          text, "Datum", DATE_FORMATTER.withZone(clock.getZone()).format(message.date()));
    }
    appendHeaderLine(text, "An", message.to());
    return text.toString();
  }

  private static void appendHeaderLine(StringBuilder text, String label, String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    if (!text.isEmpty()) {
      text.append('\n');
    }
    text.append(label).append(": ").append(value);
  }

  /**
   * The Fundort for the {@code partIndex}-th (of {@code partCount}) further-split piece of the
   * {@code segmentIndex}-th (of {@code segmentCount}) thread segment, or {@code null} when neither
   * dimension needs disambiguating (the common case: one message, not further split).
   */
  private static String locationFor(
      int segmentIndex, int segmentCount, int partIndex, int partCount) {
    String segmentPart =
        segmentCount > 1 ? "Nachricht " + (segmentIndex + 1) + " von " + segmentCount : null;
    String subPart = partCount > 1 ? "Teil " + (partIndex + 1) + " von " + partCount : null;
    if (segmentPart == null) {
      return subPart;
    }
    return subPart == null ? segmentPart : segmentPart + " · " + subPart;
  }

  /**
   * Reports every attachment {@link EmlReader}/{@link MsgReader} already extracted as a {@link
   * DiscoveredAttachment} - unfiltered by format or size, since the generalized attachment path
   * makes that admission decision itself, exactly once. Both readers already cap the count at
   * {@link MailProperties#maxAttachmentsPerMessage()} in their own extraction loop.
   */
  private static List<DiscoveredAttachment> discoveredAttachments(
      List<ParsedMailAttachment> attachments) {
    if (attachments.isEmpty()) {
      return List.of();
    }
    List<DiscoveredAttachment> discovered = new ArrayList<>(attachments.size());
    for (ParsedMailAttachment attachment : attachments) {
      discovered.add(new DiscoveredAttachment(attachment.fileName(), attachment.tempFile(), null));
    }
    return discovered;
  }

  /**
   * Deletes what the readers already extracted, for the failure path that never reaches a result
   * reporting them - deletion failures are logged, never raised over the failure being handled.
   */
  private static void deleteTempFiles(List<ParsedMailAttachment> attachments) {
    for (ParsedMailAttachment attachment : attachments) {
      if (attachment.tempFile() == null) {
        continue;
      }
      try {
        Files.deleteIfExists(attachment.tempFile());
      } catch (IOException | RuntimeException e) {
        log.warn("Failed to delete extracted mail attachment {}", attachment.tempFile(), e);
      }
    }
  }
}
