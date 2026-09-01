package io.opaa.indexing;

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
 */
public record DocumentPipelineSource(String fileName, Path file, String extractedText) {

  public DocumentPipelineSource {
    if ((file == null) == (extractedText == null)) {
      throw new IllegalArgumentException(
          "Exactly one of file and extractedText must be set for " + fileName);
    }
  }

  public static DocumentPipelineSource ofFile(Path file, String fileName) {
    return new DocumentPipelineSource(fileName, file, null);
  }

  public static DocumentPipelineSource ofExtractedText(String extractedText, String fileName) {
    return new DocumentPipelineSource(fileName, null, extractedText);
  }
}
