package io.opaa.indexing.pipeline;

import java.nio.file.Path;
import java.util.Locale;

/**
 * What a {@link DocumentPipeline} is handed: either a file on disk, or text that was already
 * extracted upstream and never had a file (an RSS entry's main content, see {@code
 * FileProcessingService#ingest}).
 *
 * <p>Exactly one of {@link #file()} and {@link #extractedText()} is non-{@code null} - enforced in
 * the compact constructor, so a pipeline can branch on {@code file() != null} without having to
 * defend against both or neither being set.
 *
 * @param fileName the document's own name, used for the chunk context title and for the
 *     text-tolerant part of the routing rule (see {@link DocumentPipelineRegistry})
 * @param detectedExtension the extension {@link DocumentPipelineRegistry} actually routed on (see
 *     {@link DocumentPipelineRegistry.Routed#detectedExtension()}), or {@code null} when routing
 *     did not resolve one (detection failed, or this source never went through the registry at all
 *     - the RSS entry body's {@link #ofExtractedText}). A pipeline handling more than one format
 *     (e.g. {@code TabularDocumentPipeline}'s XLSX/CSV/ODS) dispatches on this, not on {@code
 *     fileName}'s own suffix - a document is admitted and routed by its <em>detected</em> content ,
 *     and a pipeline that re-derived the format from the name alone would silently reintroduce the
 *     name-trusting bug the registry exists to avoid (a genuine XLSX misnamed {@code .csv} routes
 *     here on content, but would mis-parse as CSV if this field were ignored).
 * @param attachmentIndex the 0-based extraction position of the single attachment this run is
 *     interested in, or {@code null} for an ordinary run that wants all of them. A pipeline that
 *     reports {@link DocumentPipelineResult#discoveredAttachments()} must honour it: it numbers
 *     attachments exactly as an unfiltered run would - so an attachment that run would not have
 *     reported at all (skipped for its size, or unreadable) consumes no position here either - but
 *     materializes a temporary file for the wanted one alone, and reports it as the result's
 *     <b>only</b> discovered attachment (an empty list when there is none at that position). Bounds
 *     the temporary disk a single re-extraction costs to one attachment instead of a whole
 *     message's worth.
 */
public record DocumentPipelineSource(
    String fileName,
    Path file,
    String extractedText,
    String detectedExtension,
    Integer attachmentIndex) {

  public DocumentPipelineSource {
    if ((file == null) == (extractedText == null)) {
      throw new IllegalArgumentException(
          "Exactly one of file and extractedText must be set for " + fileName);
    }
    if (attachmentIndex != null && attachmentIndex < 0) {
      throw new IllegalArgumentException(
          "attachmentIndex must not be negative for " + fileName + ", got " + attachmentIndex);
    }
  }

  /**
   * This source, restricted to the attachment at {@code index} (see {@link #attachmentIndex()}).
   */
  public DocumentPipelineSource withAttachmentIndex(int index) {
    return new DocumentPipelineSource(fileName, file, extractedText, detectedExtension, index);
  }

  /**
   * The extension a pipeline handling several formats dispatches on: {@link #detectedExtension()}
   * where the registry resolved one, the file name's own lower-cased suffix only where it did not,
   * and {@code null} when there is neither. Trusting the name over the detected content would
   * reintroduce what content-based admission prevents - an XLSX misnamed {@code .csv} parsed as
   * CSV.
   */
  public String effectiveExtension() {
    if (detectedExtension != null) {
      return detectedExtension;
    }
    if (fileName == null) {
      return null;
    }
    int dot = fileName.lastIndexOf('.');
    return dot < 0 ? null : fileName.substring(dot).toLowerCase(Locale.ROOT);
  }

  /** Convenience for callers that have no detected extension to hand over (most test code). */
  public static DocumentPipelineSource ofFile(Path file, String fileName) {
    return new DocumentPipelineSource(fileName, file, null, null, null);
  }

  public static DocumentPipelineSource ofFile(
      Path file, String fileName, String detectedExtension) {
    return new DocumentPipelineSource(fileName, file, null, detectedExtension, null);
  }

  public static DocumentPipelineSource ofExtractedText(String extractedText, String fileName) {
    return new DocumentPipelineSource(fileName, null, extractedText, null, null);
  }
}
