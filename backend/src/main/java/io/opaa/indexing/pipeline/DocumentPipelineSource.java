package io.opaa.indexing.pipeline;

import java.nio.file.Path;

/**
 * What a {@link DocumentPipeline} is handed: either a file on disk, or text that was already
 * extracted upstream and never had a file (an RSS entry's main content, see {@code
 * FileProcessingService#processRssEntry}).
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
 *     (e.g. {@link TabularDocumentPipeline}'s XLSX/CSV/ODS) dispatches on this, not on {@code
 *     fileName}'s own suffix - a document is admitted and routed by its <em>detected</em> content
 *     (#404), and a pipeline that re-derived the format from the name alone would silently
 *     reintroduce the name-trusting bug the registry exists to avoid (a genuine XLSX misnamed
 *     {@code .csv} routes here on content, but would mis-parse as CSV if this field were ignored).
 */
public record DocumentPipelineSource(
    String fileName, Path file, String extractedText, String detectedExtension) {

  public DocumentPipelineSource {
    if ((file == null) == (extractedText == null)) {
      throw new IllegalArgumentException(
          "Exactly one of file and extractedText must be set for " + fileName);
    }
  }

  /** Convenience for callers that have no detected extension to hand over (most test code). */
  public static DocumentPipelineSource ofFile(Path file, String fileName) {
    return new DocumentPipelineSource(fileName, file, null, null);
  }

  public static DocumentPipelineSource ofFile(
      Path file, String fileName, String detectedExtension) {
    return new DocumentPipelineSource(fileName, file, null, detectedExtension);
  }

  public static DocumentPipelineSource ofExtractedText(String extractedText, String fileName) {
    return new DocumentPipelineSource(fileName, null, extractedText, null);
  }
}
