package io.opaa.indexing.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single choke point for calling {@link DocumentPipeline#run} (ADR-0022, part 2): deletes every
 * temp file the result's {@link DocumentPipelineResult#discoveredAttachments()} carries before
 * returning, so no caller can forget the cleanup a reported-but-unclaimed attachment needs. Cleanup
 * never throws, so it cannot turn a successful result into a failure.
 *
 * <p>The {@link #run(DocumentPipeline, DocumentPipelineSource, Consumer)} overload lets the caller
 * index an attachment's bytes first; the cleanup afterwards is unconditional and idempotent, so a
 * handler that consumed the file causes no double delete and one that throws leaves nothing behind.
 */
public final class DocumentPipelineRunner {

  private static final Logger log = LoggerFactory.getLogger(DocumentPipelineRunner.class);

  private DocumentPipelineRunner() {}

  public static DocumentPipelineResult run(
      DocumentPipeline pipeline, DocumentPipelineSource source) {
    return run(pipeline, source, result -> {});
  }

  /**
   * Like {@link #run(DocumentPipeline, DocumentPipelineSource)}, plus {@code resultHandler},
   * invoked with the full result before any discovered attachment's temp file is deleted, so it can
   * index those bytes and apply {@link DocumentPipelineResult#contentByteSizeOverride()} to the
   * parent row <em>before</em> any attachment's quota check. It must never throw for a single
   * attachment's failure, which would turn a successful parent result into an exception.
   */
  public static DocumentPipelineResult run(
      DocumentPipeline pipeline,
      DocumentPipelineSource source,
      Consumer<DocumentPipelineResult> resultHandler) {
    // The routed format extension is a source of the Dokumentart but no pipeline's own
    // finding - attached here, once, so every ingest path carries it without every pipeline copying
    // it.
    DocumentPipelineResult result = withFormatExtension(pipeline.run(source), source);
    try {
      resultHandler.accept(result);
    } finally {
      for (DiscoveredAttachment attachment : result.discoveredAttachments()) {
        try {
          Files.deleteIfExists(attachment.tempFile());
        } catch (IOException | RuntimeException e) {
          log.warn(
              "Failed to delete discovered attachment temp file: {}", attachment.tempFile(), e);
        }
      }
    }
    return result;
  }

  private static DocumentPipelineResult withFormatExtension(
      DocumentPipelineResult result, DocumentPipelineSource source) {
    if (source.detectedExtension() == null) {
      return result;
    }
    return result.withProperties(
        result.properties().withFormatExtension(source.detectedExtension()));
  }
}
