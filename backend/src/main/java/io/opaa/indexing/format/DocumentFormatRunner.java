package io.opaa.indexing.format;

import java.io.IOException;
import java.nio.file.Files;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single choke point for calling {@link DocumentFormat#run} (ADR-0022, part 2): deletes every
 * temp file the result's {@link DocumentFormatResult#discoveredAttachments()} carries before
 * returning, so no caller can forget the cleanup a reported-but-unclaimed attachment needs. Cleanup
 * never throws, so it cannot turn a successful result into a failure.
 *
 * <p>It is also where a parse failure becomes an outcome: a {@link RuntimeException} out of {@link
 * DocumentFormat#run} is mapped to {@link DocumentFormatResult#parseFailed()} and logged here,
 * once, for every format alike.
 *
 * <p>The {@link #run(DocumentFormat, DocumentFormatSource, Consumer)} overload lets the caller
 * index an attachment's bytes first; the cleanup afterwards is unconditional and idempotent, so a
 * handler that consumed the file causes no double delete and one that throws leaves nothing behind.
 */
public final class DocumentFormatRunner {

  private static final Logger log = LoggerFactory.getLogger(DocumentFormatRunner.class);

  private DocumentFormatRunner() {}

  public static DocumentFormatResult run(DocumentFormat pipeline, DocumentFormatSource source) {
    return run(pipeline, source, result -> {});
  }

  /**
   * Like {@link #run(DocumentFormat, DocumentFormatSource)}, plus {@code resultHandler}, invoked
   * with the full result before any discovered attachment's temp file is deleted, so it can index
   * those bytes and apply {@link DocumentFormatResult#contentByteSizeOverride()} to the parent row
   * <em>before</em> any attachment's quota check. It must never throw for a single attachment's
   * failure, which would turn a successful parent result into an exception.
   */
  public static DocumentFormatResult run(
      DocumentFormat pipeline,
      DocumentFormatSource source,
      Consumer<DocumentFormatResult> resultHandler) {
    // The routed format extension is a source of the Dokumentart but no pipeline's own
    // finding - attached here, once, so every ingest path carries it without every pipeline copying
    // it.
    DocumentFormatResult result = withFormatExtension(runPipeline(pipeline, source), source);
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

  /**
   * Turns whatever a pipeline throws while parsing into {@link DocumentFormatResult#parseFailed()}
   * and logs it once, so a pipeline reports a format's own failure by throwing rather than by
   * carrying its own catch-and-map block, and no caller of a pipeline has to defend against both
   * forms of the same answer.
   */
  private static DocumentFormatResult runPipeline(
      DocumentFormat pipeline, DocumentFormatSource source) {
    try {
      return pipeline.run(source);
    } catch (RuntimeException e) {
      log.warn("Could not parse {} with pipeline {}", source.fileName(), pipeline.id(), e);
      return DocumentFormatResult.parseFailed();
    }
  }

  private static DocumentFormatResult withFormatExtension(
      DocumentFormatResult result, DocumentFormatSource source) {
    if (source.detectedExtension() == null) {
      return result;
    }
    return result.withProperties(
        result.properties().withFormatExtension(source.detectedExtension()));
  }
}
