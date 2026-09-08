package io.opaa.indexing.format;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One embedded object a {@link DocumentFormat} found while parsing its own document but did not
 * itself turn into chunks - reported via {@link DocumentFormatResult#discoveredAttachments()}
 * instead (ADR-0022, part 2). {@code tempFile} is not yet owned by any attachment path (that is
 * part 3): whoever calls {@link DocumentFormat#run} through {@link DocumentFormatRunner} gets it
 * deleted for them once this result is returned, whether or not it goes on to process the
 * attachment.
 *
 * @param fileName the attachment's own name, as carried by the parsed document - never blank
 * @param tempFile a temporary file holding the attachment's bytes, deleted by {@link
 *     DocumentFormatRunner} - never {@code null}
 * @param detectedMediaType the media type detected for {@code tempFile}, or {@code null} if
 *     detection was not attempted before reporting the attachment
 */
public record DiscoveredAttachment(String fileName, Path tempFile, String detectedMediaType) {

  public DiscoveredAttachment {
    if (fileName == null || fileName.isBlank()) {
      throw new IllegalArgumentException("fileName must not be blank");
    }
    Objects.requireNonNull(tempFile, "tempFile must not be null");
  }
}
