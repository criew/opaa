package io.opaa.format;

import io.opaa.format.chunk.ChunkAnchor;
import io.opaa.format.chunk.ChunkAnchors;
import io.opaa.format.chunk.ChunkMetadataKeys;
import io.opaa.format.shared.IngestTextSanitizer;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

/**
 * The single choke point for calling {@link DocumentFormat#run} (ADR-0022, part 2): deletes every
 * temp file the result's {@link DocumentFormatResult#discoveredAttachments()} carries before
 * returning, so no caller can forget the cleanup a reported-but-unclaimed attachment needs. Cleanup
 * never throws, so it cannot turn a successful result into a failure.
 *
 * <p>It is also where a parse failure becomes an outcome: a {@link RuntimeException} or a
 * recoverable {@link Error} out of {@link DocumentFormat#run} is mapped to {@link
 * DocumentFormatResult#parseFailed()} and logged here, once, for every format alike.
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
    DocumentFormatResult result =
        sanitized(withFormatExtension(runPipeline(pipeline, source), source));
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
   * forms of the same answer. That includes an {@link Error} a parser library raises on one crafted
   * document, such as a {@link StackOverflowError} from a cyclic structure; only the other {@link
   * VirtualMachineError}s ({@link OutOfMemoryError} above all) pass through, since after one the
   * process itself is no longer trustworthy.
   */
  private static DocumentFormatResult runPipeline(
      DocumentFormat pipeline, DocumentFormatSource source) {
    try {
      return pipeline.run(source);
    } catch (RuntimeException | Error e) {
      if (e instanceof VirtualMachineError fatal && !(e instanceof StackOverflowError)) {
        throw fatal;
      }
      log.warn(
          "Could not parse {} with pipeline {}",
          source.logName(),
          pipeline.id(),
          source.loggedName().of(e));
      return DocumentFormatResult.parseFailed();
    }
  }

  /**
   * Chunk texts, their Fundort and the format's title properties cleaned by {@link
   * IngestTextSanitizer} - the one place every format and every connector passes, so index, context
   * prefix, prompt and citation check see the same text. A chunk left blank is dropped; with none
   * left the document has no extractable text. What the cleanup found travels in {@link
   * DocumentFormatResult#sanitizerFindings()}, as counts per class only. Each chunk's anchor takes
   * its quotes from the cleaned text here ({@link ChunkAnchors#completed}), so they match what the
   * citation shows.
   */
  private static DocumentFormatResult sanitized(DocumentFormatResult result) {
    Map<IngestTextSanitizer.Finding, Integer> findings =
        new EnumMap<>(IngestTextSanitizer.Finding.class);
    List<Document> chunks = new ArrayList<>();
    for (Document chunk : result.chunks()) {
      String text = cleaned(chunk.getText(), findings);
      if (text == null || text.isBlank()) {
        continue;
      }
      Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
      if (metadata.get(ChunkMetadataKeys.LOCATION_METADATA_KEY) instanceof String location) {
        metadata.put(ChunkMetadataKeys.LOCATION_METADATA_KEY, cleaned(location, findings));
      }
      if (metadata.get(ChunkMetadataKeys.ANCHOR_LEAD_METADATA_KEY) instanceof String lead) {
        // the lead is part of the chunk text, whose findings are already counted
        metadata.put(ChunkMetadataKeys.ANCHOR_LEAD_METADATA_KEY, cleaned(lead));
      }
      ChunkAnchors.write(metadata, cleanedNames(ChunkAnchors.read(metadata)));
      ChunkAnchors.completed(metadata, text);
      chunks.add(new Document(chunk.getId(), text, metadata));
    }
    DocumentProperties properties = result.properties();
    properties =
        properties
            .withTitle(cleaned(properties.title(), findings))
            .withTitleLine(cleaned(properties.titleLine(), findings))
            .withFirstHeading(cleaned(properties.firstHeading(), findings));
    if (result.outcome() == DocumentFormatResult.Outcome.CHUNKED && chunks.isEmpty()) {
      return DocumentFormatResult.noExtractableText(result.discoveredAttachments())
          .withProperties(properties);
    }
    return new DocumentFormatResult(
        result.outcome(),
        chunks,
        result.discoveredAttachments(),
        result.contentByteSizeOverride(),
        properties,
        findings);
  }

  /**
   * {@code anchor} with its heading path and sheet name cleaned like the Fundort. Both repeat text
   * the chunk or its Fundort already carries, so their findings are not counted again.
   */
  private static ChunkAnchor cleanedNames(ChunkAnchor anchor) {
    ChunkAnchor cleaned = anchor;
    if (anchor.path() != null) {
      List<String> path =
          anchor.path().stream()
              .map(DocumentFormatRunner::cleaned)
              .filter(heading -> heading != null && !heading.isBlank())
              .toList();
      cleaned = cleaned.withPath(path.isEmpty() ? null : path);
    }
    if (anchor.sheet() != null && anchor.rowFrom() != null && anchor.rowTo() != null) {
      String sheet = cleaned(anchor.sheet());
      cleaned =
          cleaned.withRows(
              sheet == null || sheet.isBlank() ? null : sheet, anchor.rowFrom(), anchor.rowTo());
    }
    return cleaned;
  }

  private static String cleaned(String text) {
    return IngestTextSanitizer.sanitize(text).text();
  }

  private static String cleaned(String text, Map<IngestTextSanitizer.Finding, Integer> findings) {
    IngestTextSanitizer.Result cleaned = IngestTextSanitizer.sanitize(text);
    cleaned.findings().forEach((finding, n) -> findings.merge(finding, n, Integer::sum));
    return cleaned.text();
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
