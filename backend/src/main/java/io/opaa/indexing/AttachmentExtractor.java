package io.opaa.indexing;

import io.opaa.indexing.pipeline.DiscoveredAttachment;
import io.opaa.indexing.pipeline.DocumentPipelineRegistry;
import io.opaa.indexing.pipeline.DocumentPipelineRunner;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Re-extracts a single attachment from its parent document's own original (ADR-0022): runs the
 * parent's routed pipeline solely for its {@code discoveredAttachments} and copies the one at
 * {@code index} - the extraction order encoded in the attachment's synthetic {@code file_path} - to
 * a temp file <b>the caller owns and must delete</b>. The run is restricted to that index, so a
 * message with many attachments costs one temp file, not one per attachment.
 *
 * <p>Attachment bytes are never stored at indexing time, so every read path re-extracts here.
 * Sharing this one implementation is what keeps the extraction order, and with it the meaning of
 * the stored index, identical to the indexing run's.
 */
public class AttachmentExtractor {

  private static final Logger log = LoggerFactory.getLogger(AttachmentExtractor.class);

  private final DocumentPipelineRegistry pipelineRegistry;

  public AttachmentExtractor(DocumentPipelineRegistry pipelineRegistry) {
    this.pipelineRegistry = pipelineRegistry;
  }

  /**
   * The attachment at {@code index} of {@code parentFile}, copied to a temp file of its own before
   * {@link DocumentPipelineRunner} deletes the pipeline's originals, or {@code null} when there is
   * no attachment at that index (the parent changed since the attachment row was created) or the
   * parent could not be parsed at all.
   */
  public Extracted extract(Path parentFile, String parentFileName, int index) {
    Extracted[] extracted = new Extracted[1];
    try {
      DocumentPipelineRegistry.Routed routed =
          pipelineRegistry.routedPipelineFor(parentFile, parentFileName);
      DocumentPipelineRunner.run(
          routed.pipeline(),
          DocumentPipelineSource.ofFile(parentFile, parentFileName, routed.detectedExtension())
              .withAttachmentIndex(index),
          result -> {
            List<DiscoveredAttachment> attachments = result.discoveredAttachments();
            if (attachments.isEmpty()) {
              return;
            }
            // Selective extraction: the source above restricts the run to index, so the pipeline
            // reports that attachment as the only one (DocumentPipelineSource#attachmentIndex).
            DiscoveredAttachment attachment = attachments.getFirst();
            try {
              Path copy = Files.createTempFile("opaa-attachment-", suffixOf(attachment.fileName()));
              Files.copy(attachment.tempFile(), copy, StandardCopyOption.REPLACE_EXISTING);
              extracted[0] = new Extracted(copy, attachment.fileName());
            } catch (IOException e) {
              log.warn("Failed to copy re-extracted attachment {}", attachment.fileName(), e);
            }
          });
    } catch (RuntimeException e) {
      // A corrupt or unreadable parent must cost only this one extraction - some pipelines still
      // throw on a parse failure instead of reporting PARSE_FAILED.
      log.warn("Failed to re-extract attachment {} of {}", index, parentFileName, e);
      return null;
    }
    return extracted[0];
  }

  /**
   * A re-extracted attachment: {@code file} is a temp file owned by the caller, {@code fileName}
   * the name the pipeline reported for it - the value an attachment row's own {@code file_name} was
   * built from, and therefore usable as a plausibility check against that row.
   */
  public record Extracted(Path file, String fileName) {}

  private static String suffixOf(String fileName) {
    if (fileName == null) {
      return ".tmp";
    }
    int dot = fileName.lastIndexOf('.');
    return dot >= 0 ? fileName.substring(dot).toLowerCase(Locale.ROOT) : ".tmp";
  }
}
