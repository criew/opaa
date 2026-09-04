package io.opaa.indexing.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single choke point for calling {@link DocumentPipeline#run} (ADR-0022, part 2): deletes every
 * temp file the result's {@link DocumentPipelineResult#discoveredAttachments()} carries before
 * returning, so no caller can forget the cleanup a reported-but-unclaimed attachment requires.
 * Cleanup never throws, so it can never turn an otherwise successful result into a failure.
 *
 * <p>{@code MailDocumentPipeline} no longer recurses into a sub-pipeline itself (ADR-0022,
 * Entscheidung 10), so every {@code discoveredAttachments} entry a pipeline reports reaches this
 * class exactly once, from the caller's own top-level call ({@code FileProcessingService}, or
 * {@code PipelineReindexService}'s attachment re-extraction). The {@link #run(DocumentPipeline,
 * DocumentPipelineSource, Consumer)} overload lets that caller process (index) an attachment's
 * bytes before this class deletes its temp file - the handler runs first, so it can read/copy the
 * file; cleanup afterwards is unconditional and idempotent ({@link Files#deleteIfExists}), so a
 * handler that already consumed the file causes no double-delete and a handler that throws or never
 * runs still leaves no temp file behind.
 */
public final class DocumentPipelineRunner {

  private static final Logger log = LoggerFactory.getLogger(DocumentPipelineRunner.class);

  private DocumentPipelineRunner() {}

  public static DocumentPipelineResult run(
      DocumentPipeline pipeline, DocumentPipelineSource source) {
    return run(pipeline, source, result -> {});
  }

  /**
   * Like {@link #run(DocumentPipeline, DocumentPipelineSource)}, plus {@code resultHandler} -
   * invoked with the full result before any {@link DocumentPipelineResult#discoveredAttachments()}
   * temp file is deleted, so the handler can index an attachment's bytes while its temp file still
   * exists, and can apply {@link DocumentPipelineResult#contentByteSizeOverride()} to the parent
   * row <em>before</em> any attachment's own quota check runs against it. {@code resultHandler}
   * must never throw for an individual attachment's own failure (mirrors {@code
   * AttachmentIndexer}'s own "never lets an attachment failure propagate" contract) - a handler
   * failure here would otherwise turn an already-successful parent document result into an
   * exception.
   */
  public static DocumentPipelineResult run(
      DocumentPipeline pipeline,
      DocumentPipelineSource source,
      Consumer<DocumentPipelineResult> resultHandler) {
    // The routed format extension is a source of the Dokumentart (#1263) but no pipeline's own
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
