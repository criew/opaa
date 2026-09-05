package io.opaa.indexing.pipeline;

import java.util.List;
import java.util.OptionalLong;
import org.springframework.ai.document.Document;

/**
 * What a {@link DocumentPipeline} produced for one document: its chunks, plus the reason there are
 * none when a pipeline could not turn the source into anything indexable. The outcome is decided by
 * the format-specific pipeline, not by a caller re-deciding per format.
 *
 * <p>{@link Outcome#PARSE_FAILED} versus {@link Outcome#NO_CONTENT}/{@link
 * Outcome#NO_EXTRACTABLE_TEXT} is "could not be read" versus "was read and is empty", and it is
 * load-bearing: a document being re-indexed keeps its previous chunks on a parse failure and loses
 * them on a legitimately empty new version. A pipeline that cannot tell the two apart reports
 * {@code PARSE_FAILED}.
 *
 * @param chunks never {@code null}; empty for every outcome other than {@link Outcome#CHUNKED}
 * @param discoveredAttachments embedded objects (e.g. mail/archive attachments) this pipeline found
 *     while parsing but did not itself turn into chunks (ADR-0022, part 2) - never {@code null},
 *     empty by default. Reporting one here does not exempt {@code chunks} from the {@link
 *     Outcome#CHUNKED} rule above: a pipeline that finds only attachments and produces no chunks of
 *     its own reports {@link #noExtractableText()} (or {@link #noContent()}), never {@code CHUNKED}
 *     with an empty chunk list - that combination is reserved for the generalized attachment path
 *     (ADR-0022, part 4), not this contract.
 * @param contentByteSizeOverride the byte size to persist as {@code Document#getFileSize()} for
 *     this document, in place of the raw source file's own size, or {@link OptionalLong#empty()} to
 *     keep the caller's default (every pipeline but {@code MailDocumentPipeline}). ADR-0022,
 *     Entscheidung 6: a message file's own bytes include its attachments' (base64-encoded) payload,
 *     which - once an attachment is its own {@code Document} row with its own {@code fileSize} -
 *     would otherwise count twice against a library's storage quota. {@code MailDocumentPipeline}
 *     reports only its Kopfdaten/body text bytes here, excluding every attachment.
 * @param properties the raw metadata sources the format declares (ADR-0024) - never {@code null},
 *     {@link DocumentProperties#EMPTY} by default. Attached via {@link #withProperties} by every
 *     pipeline that has them cheaply at hand; the interpretation into core fields happens
 *     centrally, never here.
 */
public record DocumentPipelineResult(
    Outcome outcome,
    List<Document> chunks,
    List<DiscoveredAttachment> discoveredAttachments,
    OptionalLong contentByteSizeOverride,
    DocumentProperties properties) {

  public enum Outcome {
    /** At least one chunk was produced. */
    CHUNKED,
    /**
     * The reader ran over the whole source and it holds nothing at all - an empty file, a document
     * body without a single element. The source was readable; there is simply nothing in it.
     */
    NO_CONTENT,
    /**
     * The source could not be read at all - a corrupt container, a rejected XXE attempt, a
     * DoS-hardening limit. Distinct from {@link #NO_CONTENT}, since nothing is known about the
     * content, so the caller must not treat it as "the new version is empty". A pipeline reports it
     * by letting its parser's exception out; {@link DocumentPipelineRunner} maps it here for every
     * format alike, so a caller never sees the failure in any other form.
     */
    PARSE_FAILED,
    /**
     * The document parsed, but carries no usable text - a PDF without a text layer, or text that
     * chunked down to nothing. Maps to {@code
     * io.opaa.indexing.DocumentService#NO_EXTRACTABLE_TEXT_MESSAGE}.
     */
    NO_EXTRACTABLE_TEXT
  }

  public DocumentPipelineResult {
    chunks = chunks == null ? List.of() : List.copyOf(chunks);
    discoveredAttachments =
        discoveredAttachments == null ? List.of() : List.copyOf(discoveredAttachments);
    contentByteSizeOverride =
        contentByteSizeOverride == null ? OptionalLong.empty() : contentByteSizeOverride;
    properties = properties == null ? DocumentProperties.EMPTY : properties;
  }

  public static DocumentPipelineResult chunked(List<Document> chunks) {
    return new DocumentPipelineResult(
        Outcome.CHUNKED, chunks, List.of(), OptionalLong.empty(), DocumentProperties.EMPTY);
  }

  /**
   * Like {@link #chunked(List)}, plus embedded objects this pipeline found but did not itself
   * process (ADR-0022, part 2) - {@code chunks} must still be non-empty, see this record's own
   * Javadoc.
   */
  public static DocumentPipelineResult chunked(
      List<Document> chunks, List<DiscoveredAttachment> discoveredAttachments) {
    return new DocumentPipelineResult(
        Outcome.CHUNKED,
        chunks,
        discoveredAttachments,
        OptionalLong.empty(),
        DocumentProperties.EMPTY);
  }

  /**
   * Like {@link #chunked(List, List)}, plus {@code contentByteSizeOverride} (ADR-0022, Entscheidung
   * 6) - used by {@code MailDocumentPipeline}, the sole producer of both extra channels.
   */
  public static DocumentPipelineResult chunked(
      List<Document> chunks,
      List<DiscoveredAttachment> discoveredAttachments,
      long contentByteSizeOverride) {
    return new DocumentPipelineResult(
        Outcome.CHUNKED,
        chunks,
        discoveredAttachments,
        OptionalLong.of(contentByteSizeOverride),
        DocumentProperties.EMPTY);
  }

  public static DocumentPipelineResult noContent() {
    return new DocumentPipelineResult(
        Outcome.NO_CONTENT, List.of(), List.of(), OptionalLong.empty(), DocumentProperties.EMPTY);
  }

  /**
   * See {@link Outcome#PARSE_FAILED}: the source could not be read, so nothing is known about it.
   */
  public static DocumentPipelineResult parseFailed() {
    return new DocumentPipelineResult(
        Outcome.PARSE_FAILED, List.of(), List.of(), OptionalLong.empty(), DocumentProperties.EMPTY);
  }

  public static DocumentPipelineResult noExtractableText() {
    return new DocumentPipelineResult(
        Outcome.NO_EXTRACTABLE_TEXT,
        List.of(),
        List.of(),
        OptionalLong.empty(),
        DocumentProperties.EMPTY);
  }

  /**
   * Like {@link #noExtractableText()}, plus embedded objects found while parsing (ADR-0022, part
   * 2/4) - the case this record's own Javadoc reserves for the generalized attachment path: a
   * message with no chunk-worthy text of its own (no body, no Kopfdaten) but at least one
   * attachment still reports that attachment here, so the caller's attachment path can index it
   * even though the parent document itself carries nothing indexable.
   */
  public static DocumentPipelineResult noExtractableText(
      List<DiscoveredAttachment> discoveredAttachments) {
    return new DocumentPipelineResult(
        Outcome.NO_EXTRACTABLE_TEXT,
        List.of(),
        discoveredAttachments,
        OptionalLong.empty(),
        DocumentProperties.EMPTY);
  }

  /** The same result with {@code properties} attached (ADR-0024). */
  public DocumentPipelineResult withProperties(DocumentProperties properties) {
    return new DocumentPipelineResult(
        outcome, chunks, discoveredAttachments, contentByteSizeOverride, properties);
  }
}
