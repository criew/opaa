package io.opaa.indexing.pipeline;

import java.util.List;
import java.util.OptionalLong;
import org.springframework.ai.document.Document;

/**
 * What a {@link DocumentPipeline} produced for one document: its chunks, plus the reason there are
 * none when a pipeline could not turn the source into anything indexable.
 *
 * <p>The three outcomes are the ones {@code FileProcessingService} has always distinguished, moved
 * behind the abstraction so that a format-specific pipeline decides them for its own format instead
 * of the caller re-deciding for every format (the open-closed criterion of
 * docs/features/ingestion-pipelines.md, Teil 1): a scan PDF is {@code NO_EXTRACTABLE_TEXT} because
 * the PDF pipeline says so, not because the caller knows about PDFs.
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
 */
public record DocumentPipelineResult(
    Outcome outcome,
    List<Document> chunks,
    List<DiscoveredAttachment> discoveredAttachments,
    OptionalLong contentByteSizeOverride) {

  public enum Outcome {
    /** At least one chunk was produced. */
    CHUNKED,
    /**
     * The reader returned nothing at all - the document could not be parsed into any text. Maps to
     * the generic failure, not to the "likely a scan" rejection.
     *
     * <p>A pipeline reports this by catching every {@link java.io.IOException}/{@link
     * RuntimeException} its own parser can throw (a corrupt archive, a rejected XXE attempt, a
     * DoS-hardening limit) and returning {@link #noContent()} with a single {@code log.warn} naming
     * the document - never by letting the exception propagate out of {@link DocumentPipeline#run}.
     * PDF, DOCX, PPTX, ODT, ODP and the XLSX/CSV/ODS pipeline (tabular) follow this contract
     * (#1108); HTML, Markdown, mail and the Tika fallback pipeline (the catch-all for every format
     * none of those claim, so a corrupt file is likeliest to reach it) still propagate an unchecked
     * exception on a parse failure instead - a known, pre-existing gap outside this refactor's
     * scope, not a model to copy for a new pipeline.
     */
    NO_CONTENT,
    /**
     * The document parsed, but carries no usable text - a PDF without a text layer (see {@link
     * TikaFallbackPipeline#isTextlessPdf}), or text that chunked down to nothing. Maps to {@code
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
  }

  public static DocumentPipelineResult chunked(List<Document> chunks) {
    return new DocumentPipelineResult(Outcome.CHUNKED, chunks, List.of(), OptionalLong.empty());
  }

  /**
   * Like {@link #chunked(List)}, plus embedded objects this pipeline found but did not itself
   * process (ADR-0022, part 2) - {@code chunks} must still be non-empty, see this record's own
   * Javadoc.
   */
  public static DocumentPipelineResult chunked(
      List<Document> chunks, List<DiscoveredAttachment> discoveredAttachments) {
    return new DocumentPipelineResult(
        Outcome.CHUNKED, chunks, discoveredAttachments, OptionalLong.empty());
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
        Outcome.CHUNKED, chunks, discoveredAttachments, OptionalLong.of(contentByteSizeOverride));
  }

  public static DocumentPipelineResult noContent() {
    return new DocumentPipelineResult(
        Outcome.NO_CONTENT, List.of(), List.of(), OptionalLong.empty());
  }

  public static DocumentPipelineResult noExtractableText() {
    return new DocumentPipelineResult(
        Outcome.NO_EXTRACTABLE_TEXT, List.of(), List.of(), OptionalLong.empty());
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
        Outcome.NO_EXTRACTABLE_TEXT, List.of(), discoveredAttachments, OptionalLong.empty());
  }
}
