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
 * The EML/MSG pipeline (docs/features/ingestion-pipelines.md, Teil 3, Punkt 5): separates
 * Kopfdaten, body and attachment text, which Tika's native parse otherwise flattens into one block.
 *
 * <p>Kopfdaten land both as chunk metadata (see {@link ChunkMailMetadata} - unread today, a
 * deliberate vorhaltung for future filtering, #1130 Befund 1) and as German-labeled context lines
 * prepended to the message's first body text, <b>before</b> that text reaches {@link
 * ChunkingService#chunkDocuments} (see {@link #bodyChunks}) - so a sender name, an address or a
 * Betreff reaches embedding and full-text search, and an unbounded {@code To} line (a round mail to
 * hundreds of recipients) is always cut to the configured chunk size by the same splitter instead
 * of growing one chunk past it - true of every path that produces a header-bearing chunk, including
 * the header-only one below (#1130 Befund 1, review round 3). A message whose body never produces a
 * chunk at all but carries at least one attachment still gets a header-only chunk carrying its
 * Kopfdaten - otherwise the common "see attached" mail would index its attachment but lose its own
 * sender and Betreff entirely. A blank body with <em>no</em> attachment stays {@code
 * NO_EXTRACTABLE_TEXT}: nothing in it carries content of its own, and its Kopfdaten are then
 * exactly as much template text as a repeating page header (#1130 Befund 1, review round 3,
 * decision 3; mirrors {@code DocxDocumentPipeline}'s "header/footer text never rescues this
 * outcome"). One chunk per message, or one per quoted-reply segment (see {@link
 * MailThreadSplitter}); a segment still too long for a single chunk falls back to {@link
 * ChunkingService}'s ordinary token splitter.
 *
 * <p><b>An attachment is no longer processed by this class (ADR-0022, Entscheidung 10, #1183).</b>
 * {@link EmlReader}/{@link MsgReader} still extract every attachment into its own temporary file
 * (parse-time task, unchanged) and this pipeline reports each one via {@link
 * DocumentPipelineResult#discoveredAttachments()} instead of routing it recursively through {@link
 * io.opaa.indexing.pipeline.DocumentPipelineRegistry} itself - the generalized attachment path
 * ({@code io.opaa.indexing.source.attachment.AttachmentIndexer}, driven by {@code
 * FileProcessingService}) turns each one into its own {@code Document} row, with its own checksum,
 * quota accounting and correct {@code pipeline_id}/{@code pipeline_version} (the structural fix for
 * #1130 Befund 2). A message whose own text carries no attachment reference of its own reports
 * {@code discoveredAttachments} alongside its {@code chunks}; recursion for Mail-in-Mail (an
 * attachment that is itself an EML/MSG) and the attachment-count/depth limits both live one level
 * up, on the shared attachment path, not in this class' own state.
 */
public class MailDocumentPipeline implements DocumentPipeline {

  private static final Logger log = LoggerFactory.getLogger(MailDocumentPipeline.class);

  static final String ID = "email";

  /**
   * ADR-0022 Entscheidung 9: v1 -&gt; v2 belonged to #1166 (Mail-Kopfdaten context lines), v2 -&gt;
   * v3 to #1164 (mail_date truncated to whole seconds) - both landed before this attachment umbau,
   * so it takes v4, not v2/v3 as the ADR's own first draft assumed.
   */
  static final short VERSION = 4;

  /**
   * Renders {@link ParsedMailMessage#date()} in the leading context line, in {@link #clock}'s own
   * zone (the same choice {@code LibraryIndexingScheduler} makes for cron evaluation) - never a
   * fixed zone: the originating header's own offset does not survive parsing into an {@link
   * java.time.Instant}, but rendering in UTC regardless would put a silently wrong local time into
   * embedding, full-text index and the cited Beleg alike, off by one or two hours for every German
   * sender.
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
   * {@link #bodyChunks}); an attachment no longer produces a chunk of this pipeline's own at all
   * (#1183), so there is no longer a recursively-produced chunk to exclude here.
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
   * Honours {@link DocumentPipelineSource#attachmentIndex()} (#1243) by handing it to the reader:
   * the extraction order is unchanged, but only the requested attachment is written to a temp file
   * and reported - the whole message's worth of temporary disk an "open this attachment" click used
   * to cost collapses to that one file.
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
        return DocumentPipelineResult.noContent();
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read mail document " + source.fileName(), e);
    }

    ParsedMailMessage message;
    try {
      message =
          ".msg".equals(resolveExtension(source))
              ? MsgReader.read(source.file(), properties, source.attachmentIndex())
              : EmlReader.read(source.file(), properties, source.attachmentIndex());
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read mail document " + source.fileName(), e);
    }

    String headerContext = headerContextText(message);
    List<Document> chunks = bodyChunks(message, source.fileName(), headerContext);
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
          ".msg".equals(resolveExtension(source))
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
   * are. {@code headerContext} is prepended to the raw text of only the first non-blank segment,
   * <b>before</b> {@link ChunkingService#chunkDocuments} ever runs on it (see {@link
   * #headerContextText}'s own Javadoc for why prepending here, rather than after chunking, is
   * load-bearing) - never repeated onto a later segment or further-split part.
   *
   * <p><b>A segment too long for one chunk falls back to {@link ChunkingService}'s ordinary token
   * splitter</b> (#1101 review, finding 2): a long newsletter or a forwarded chain with no
   * recognizable quote separator would otherwise become a single, unboundedly large chunk - past
   * the embedding model's own token limit, failing the whole document. {@link
   * ChunkingService#chunkDocuments} already returns a single, unmodified chunk for text under its
   * configured {@code opaa.indexing.chunk-size}, so an ordinary message is unaffected; only a
   * segment that actually exceeds it is further cut, exactly the fallback role token-chunking plays
   * project-wide once structure runs out (ingestion-pipelines.md, Teil 2, "Der Grundsatz").
   *
   * <p><b>A message whose body never produces a chunk, but carries an attachment, still gets a
   * header-only chunk</b> if it has any Kopfdaten (#1130 Befund 1): a blank body next to a PDF
   * attachment - the common "Anbei der Bescheid" mail - would otherwise index the attachment while
   * losing sender and Betreff entirely. This header-only text runs through the very same {@link
   * ChunkingService#chunkDocuments} call as every other header-bearing chunk (review round 3,
   * finding 1) - an unbounded {@code An} line does not get a free pass here just because there is
   * no body text to prepend it to; a round mail with a blank body and hundreds of recipients is cut
   * into {@code Teil j von M} pieces exactly like a long newsletter. A blank body with <em>no</em>
   * attachment is not rescued this way (review round 3, finding 3): with nothing else in the
   * message, its Kopfdaten are template text like a repeating page header, not evidence of content
   * (mirrors {@code DocxDocumentPipeline}'s "header/footer text never rescues this outcome").
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
   * seconds first (#1164) - {@link Instant#toString()} omits the fractional part entirely when it
   * is zero, so two messages a millisecond apart could otherwise produce ISO-8601 strings that do
   * not compare correctly as text (".500Z" sorts before "Z" lexicographically) once a Zeitraum
   * filter compares {@code mail_date} as a string. An EML Date header (RFC 5322) never carries
   * sub-second precision to begin with, but {@link MsgReader} reads an Outlook FILETIME through
   * {@link java.util.Calendar}, which can.
   */
  static String renderMailDate(Instant date) {
    return date.truncatedTo(ChronoUnit.SECONDS).toString();
  }

  /**
   * Renders Von/Betreff/Datum/An as German-labeled context lines, one line per present field, no
   * line at all for an absent one - empty string when the message carries none of the four.
   *
   * <p><b>{@code An} is rendered last, deliberately not in its natural Von/An/Betreff/Datum reading
   * order</b> (review round 3 nit): {@link ParsedMailMessage#to()} is the one unbounded field
   * ({@code EmlReader} renders every recipient), so a round mail to hundreds of recipients pushes
   * it across several chunks once {@link #bodyChunks} runs this block through the token splitter
   * (see below). Leading with it would strand Betreff and Datum - and the body itself - past the
   * recipient list, in a chunk a question like "Mail von Müller zum Bebauungsplan" never reaches.
   * Von/Betreff/Datum are short and stay together in the leading chunk regardless of how large
   * {@code An} grows.
   *
   * <p><b>Prepended once, to the raw text of the first non-blank segment, before {@link
   * ChunkingService#chunkDocuments} runs on it</b> (#1130 Befund 1) - deliberately not appended
   * after chunking: appending it to an already-chunked piece of text would grow that one chunk past
   * the configured {@code opaa.indexing.chunk-size} without bound - exactly the failure mode {@link
   * #bodyChunks}'s own token-splitter fallback exists to prevent. Prepending before chunking
   * instead lets the same splitter account for this block like any other text, cutting a
   * pathologically large header into its own leading chunk(s) rather than growing one chunk
   * unboundedly - the same treatment {@link #bodyChunks}'s header-only branch gives it when there
   * is no body text to attach it to at all. Never repeated onto a later segment or further-split
   * part: {@link #bodyChunks} already duplicates this same information into every chunk's
   * <em>metadata</em> (a quoted-reply thread or a long newsletter can produce many chunks from one
   * message), and doing the same to the chunk text would dilute embedding and full-text ranking
   * with an identical block repeated across the whole document - the same Verwässerungsproblem
   * {@code RepeatingHeaderChunk} avoids for a page header repeating across a document's chunks.
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
   * Reports every attachment {@link EmlReader}/{@link MsgReader} already extracted (both cap the
   * count at {@link MailProperties#maxAttachmentsPerMessage()} themselves, in their own extraction
   * loop) as a {@link DiscoveredAttachment} - unfiltered by format or size here (#1183): the
   * generalized attachment path ({@code AttachmentIndexer#indexLocalFile}) makes that same
   * admission decision itself, exactly once, instead of this class pre-filtering with logic that
   * would only duplicate it.
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
}
