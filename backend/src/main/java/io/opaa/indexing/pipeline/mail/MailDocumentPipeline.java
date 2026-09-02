package io.opaa.indexing.pipeline.mail;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.SupportedDocumentFormats;
import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineRegistry;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The EML/MSG pipeline (docs/features/ingestion-pipelines.md, Teil 3, Punkt 5): separates
 * Kopfdaten, body and attachment text, which Tika's native parse otherwise flattens into one block.
 *
 * <p>Kopfdaten land both as chunk metadata (see {@link ChunkMailMetadata} - unread today, a
 * deliberate vorhaltung for future filtering, #1130 Befund 1) and as German-labeled context lines
 * prepended to the message's first body chunk (see {@link #headerContextText}), so a sender name,
 * an address or a Betreff reaches embedding and full-text search. One chunk per message, or one per
 * quoted-reply segment (see {@link MailThreadSplitter}); a segment still too long for a single
 * chunk falls back to {@link ChunkingService}'s ordinary token splitter. An attachment runs
 * recursively through the pipeline of its own type via {@link DocumentPipelineRegistry} - an
 * attachment that is itself an EML/MSG (a forward) reaches this class again, one recursion level
 * deeper, bounded by {@link MailProperties#maxAttachmentDepth()}. Every chunk this pipeline
 * produces, including an attachment's own, is attributed to this pipeline's {@link #id()}/{@link
 * #version()} - a version-selective re-index of a nested attachment's own pipeline is therefore not
 * reachable except by reprocessing the whole mail.
 *
 * <p>{@code registryProvider} is an {@link ObjectProvider} rather than a plain constructor
 * dependency to break the circular bean graph this pipeline's own recursion creates: {@link
 * DocumentPipelineRegistry} is built from every registered {@link DocumentPipeline}, including this
 * one.
 */
public class MailDocumentPipeline implements DocumentPipeline {

  private static final Logger log = LoggerFactory.getLogger(MailDocumentPipeline.class);

  static final String ID = "email";
  static final short VERSION = 2;

  /**
   * Renders {@link ParsedMailMessage#date()} in the leading context line - fixed to UTC since the
   * originating header's own zone offset does not survive parsing into an {@link
   * java.time.Instant}.
   */
  private static final DateTimeFormatter DATE_FORMATTER =
      DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY).withZone(ZoneOffset.UTC);

  /**
   * How many levels of mail-in-mail attachment recursion the current thread is at - {@code null}
   * outside of any {@link #run} call. Threaded via {@link ThreadLocal} rather than a method
   * parameter because {@link #run} implements the fixed {@link DocumentPipeline} interface: an
   * attachment that routes back into this class re-enters {@link #run} through {@link
   * DocumentPipelineRegistry#pipelineFor}, synchronously on the same thread, with no channel of its
   * own to carry a depth counter.
   */
  private static final ThreadLocal<Integer> RECURSION_DEPTH = new ThreadLocal<>();

  private final ObjectProvider<DocumentPipelineRegistry> registryProvider;
  private final ChunkingService chunkingService;
  private final MailProperties properties;

  public MailDocumentPipeline(
      ObjectProvider<DocumentPipelineRegistry> registryProvider,
      ChunkingService chunkingService,
      MailProperties properties) {
    this.registryProvider = registryProvider;
    this.chunkingService = chunkingService;
    this.properties = properties;
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
   * {@link #bodyChunks}), never on an attachment's recursively produced chunks.
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
        return DocumentPipelineResult.noContent();
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read mail document " + source.fileName(), e);
    }

    boolean topLevel = RECURSION_DEPTH.get() == null;
    int depth = topLevel ? 0 : RECURSION_DEPTH.get();
    if (topLevel) {
      RECURSION_DEPTH.set(0);
    }
    try {
      ParsedMailMessage message;
      try {
        message =
            ".msg".equals(resolveExtension(source))
                ? MsgReader.read(source.file(), properties)
                : EmlReader.read(source.file(), properties);
      } catch (IOException e) {
        throw new UncheckedIOException("Could not read mail document " + source.fileName(), e);
      }

      List<Document> chunks = new ArrayList<>(bodyChunks(message, source.fileName()));
      chunks.addAll(processAttachments(message.attachments(), source.fileName(), depth));

      if (chunks.isEmpty()) {
        return DocumentPipelineResult.noExtractableText();
      }
      return DocumentPipelineResult.chunked(chunks);
    } finally {
      if (topLevel) {
        RECURSION_DEPTH.remove();
      }
    }
  }

  private static String resolveExtension(DocumentPipelineSource source) {
    if (source.detectedExtension() != null) {
      return source.detectedExtension();
    }
    String fileName = source.fileName() == null ? "" : source.fileName().toLowerCase(Locale.ROOT);
    return fileName.endsWith(".msg") ? ".msg" : ".eml";
  }

  /**
   * One chunk per message, or one per thread segment when {@link MailThreadSplitter} finds a quoted
   * reply chain - every segment carries the same, single set of Kopfdaten metadata: a quoted prior
   * message's own header lines are free text inside the client's quoting convention, not reliably
   * parseable back into structured From/To/Date/Subject the way the outer MIME envelope's headers
   * are. The rendered {@link #headerContextText} block, by contrast, is prepended to the text of
   * only the very first produced chunk (see {@link #headerContextText}'s own Javadoc for why).
   *
   * <p><b>A segment too long for one chunk falls back to {@link ChunkingService}'s ordinary token
   * splitter</b> (#1101 review, finding 2): a long newsletter or a forwarded chain with no
   * recognizable quote separator would otherwise become a single, unboundedly large chunk - past
   * the embedding model's own token limit, failing the whole document. {@link
   * ChunkingService#chunkDocuments} already returns a single, unmodified chunk for text under its
   * configured {@code opaa.indexing.chunk-size}, so an ordinary message is unaffected; only a
   * segment that actually exceeds it is further cut, exactly the fallback role token-chunking plays
   * project-wide once structure runs out (ingestion-pipelines.md, Teil 2, "Der Grundsatz").
   */
  private List<Document> bodyChunks(ParsedMailMessage message, String fileName) {
    String headerContext = headerContextText(message);
    List<String> segments = MailThreadSplitter.split(message.bodyText());
    List<Document> chunks = new ArrayList<>(segments.size());
    for (int i = 0; i < segments.size(); i++) {
      String text = segments.get(i).strip();
      if (text.isBlank()) {
        continue;
      }
      List<Document> parts = chunkingService.chunkDocuments(fileName, List.of(new Document(text)));
      for (int j = 0; j < parts.size(); j++) {
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
          metadata.put(ChunkMailMetadata.MAIL_DATE_METADATA_KEY, message.date().toString());
        }
        String location = locationFor(i, segments.size(), j, parts.size());
        if (location != null) {
          metadata.put(ChunkingService.LOCATION_METADATA_KEY, location);
        }
        String chunkText = parts.get(j).getText();
        if (chunks.isEmpty() && !headerContext.isEmpty()) {
          chunkText = headerContext + "\n\n" + chunkText;
        }
        chunks.add(new Document(chunkText, metadata));
      }
    }
    return chunks;
  }

  /**
   * Renders Von/An/Betreff/Datum as German-labeled context lines, one line per present field, no
   * line at all for an absent one - empty string when the message carries none of the four.
   *
   * <p><b>Prepended once, to the first produced body chunk only</b> (#1130 Befund 1), never
   * repeated onto a later thread segment or further-split part: {@link #bodyChunks} already
   * duplicates this same information into every chunk's <em>metadata</em> (a quoted-reply thread or
   * a long newsletter can produce many chunks from one message), and doing the same to the chunk
   * text would dilute embedding and full-text ranking with an identical block repeated across the
   * whole document - the same Verwässerungsproblem {@code RepeatingHeaderChunk} avoids for a page
   * header repeating across a document's chunks.
   */
  private String headerContextText(ParsedMailMessage message) {
    StringBuilder text = new StringBuilder();
    appendHeaderLine(text, "Von", message.from());
    appendHeaderLine(text, "An", message.to());
    appendHeaderLine(text, "Betreff", message.subject());
    if (message.date() != null) {
      appendHeaderLine(text, "Datum", DATE_FORMATTER.format(message.date()));
    }
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
   * Routes every attachment {@link EmlReader}/{@link MsgReader} already extracted (both cap the
   * count at {@link MailProperties#maxAttachmentsPerMessage()} themselves, in their own extraction
   * loop) through {@link DocumentPipelineRegistry}. Beyond {@link
   * MailProperties#maxAttachmentDepth()}, an attachment is skipped and logged rather than recursed
   * into - a message attaching itself (directly or through a cycle) must not recurse without bound.
   * Every temp file {@code attachments} carries is deleted here regardless of outcome.
   */
  private List<Document> processAttachments(
      List<ParsedMailAttachment> attachments, String parentFileName, int depth) {
    List<Document> chunks = new ArrayList<>();
    if (attachments.isEmpty()) {
      return chunks;
    }
    if (depth >= properties.maxAttachmentDepth()) {
      log.warn(
          "Maximum mail attachment depth ({}) reached for {}, skipping {} attachment(s)"
              + " (opaa.indexing.mail.max-attachment-depth)",
          properties.maxAttachmentDepth(),
          parentFileName,
          attachments.size());
      deleteAll(attachments);
      return chunks;
    }

    for (ParsedMailAttachment attachment : attachments) {
      try {
        chunks.addAll(processAttachment(attachment, depth));
      } finally {
        deleteQuietly(attachment.tempFile());
      }
    }
    return chunks;
  }

  private List<Document> processAttachment(ParsedMailAttachment attachment, int depth) {
    String fileName = attachment.fileName();
    String detectedMimeType;
    try {
      detectedMimeType = SupportedDocumentFormats.detectMediaType(attachment.tempFile());
    } catch (IOException e) {
      log.warn("Could not detect the format of mail attachment {}, skipping", fileName, e);
      return List.of();
    }
    SupportedDocumentFormats.ContentDecision decision =
        SupportedDocumentFormats.decideForFileName(fileName, detectedMimeType);
    if (!decision.supported()) {
      // Unsupported attachment format: skipped, not FAILED for the whole message
      // (ingestion-pipelines.md, Teil 3, Punkt 5).
      log.info("Skipping unsupported mail attachment format: {}", fileName);
      return List.of();
    }

    DocumentPipelineRegistry registry = registryProvider.getObject();
    DocumentPipelineRegistry.Routed routed =
        registry.routedPipelineFor(attachment.tempFile(), fileName);
    RECURSION_DEPTH.set(depth + 1);
    try {
      DocumentPipelineResult result =
          routed
              .pipeline()
              .run(
                  DocumentPipelineSource.ofFile(
                      attachment.tempFile(), fileName, routed.detectedExtension()));
      if (result.outcome() != DocumentPipelineResult.Outcome.CHUNKED) {
        log.info(
            "No usable content extracted from mail attachment {} by pipeline {}",
            fileName,
            routed.pipeline().id());
        return List.of();
      }
      return result.chunks().stream()
          .map(chunk -> withAttachmentLocation(chunk, fileName))
          .toList();
    } catch (RuntimeException e) {
      // #1101 review, finding 4a: a sub-pipeline failure (a corrupted nested EML, a malformed
      // XLSX) costs only this one attachment, never the whole message - the same "skip, do not
      // fail the message" contract this pipeline already applies to an unsupported format.
      log.warn(
          "Skipping mail attachment {}: pipeline {} failed to process it",
          fileName,
          routed.pipeline().id(),
          e);
      return List.of();
    } finally {
      RECURSION_DEPTH.set(depth);
    }
  }

  /**
   * Prefixes {@code chunk}'s own Fundort (if it has one) with "Anhang: {@code fileName}" - so a
   * citation into an attachment chunk still names the message it came from, following the
   * Anlagenweg's existing rule that an attachment's provenance stays traceable
   * (docs/features/knowledge-sources.md).
   */
  private static Document withAttachmentLocation(Document chunk, String fileName) {
    Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
    Object existing = metadata.get(ChunkingService.LOCATION_METADATA_KEY);
    String location =
        existing != null ? "Anhang: " + fileName + " · " + existing : "Anhang: " + fileName;
    metadata.put(ChunkingService.LOCATION_METADATA_KEY, location);
    return new Document(chunk.getText(), metadata);
  }

  private static void deleteAll(List<ParsedMailAttachment> attachments) {
    for (ParsedMailAttachment attachment : attachments) {
      deleteQuietly(attachment.tempFile());
    }
  }

  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      log.warn("Failed to delete temp file: {}", file, e);
    }
  }
}
