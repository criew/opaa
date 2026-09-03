package io.opaa.indexing.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single choke point for calling {@link DocumentPipeline#run} (ADR-0022, part 2): deletes every
 * temp file the result's {@link DocumentPipelineResult#discoveredAttachments()} carries before
 * returning, so no caller - top-level or a pipeline's own recursive, nested call - can forget the
 * cleanup a reported-but-unclaimed attachment requires. Cleanup never throws, so it can never turn
 * an otherwise successful result into a failure.
 */
public final class DocumentPipelineRunner {

  private static final Logger log = LoggerFactory.getLogger(DocumentPipelineRunner.class);

  private DocumentPipelineRunner() {}

  public static DocumentPipelineResult run(
      DocumentPipeline pipeline, DocumentPipelineSource source) {
    DocumentPipelineResult result = pipeline.run(source);
    for (DiscoveredAttachment attachment : result.discoveredAttachments()) {
      try {
        Files.deleteIfExists(attachment.tempFile());
      } catch (IOException | RuntimeException e) {
        log.warn("Failed to delete discovered attachment temp file: {}", attachment.tempFile(), e);
      }
    }
    return result;
  }
}
