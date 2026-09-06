package io.opaa.indexing.pipeline.confluence;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.HeadingSectionSplitter;
import io.opaa.indexing.pipeline.HeadingSectionSplitter.Event;
import io.opaa.indexing.pipeline.XhtmlEventBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.springframework.ai.document.Document;

/**
 * Turns a Confluence page's storage-format body (XHTML with {@code ac:}/{@code ri:} macro elements,
 * identical for Cloud and Data Center) into heading-section chunks (ADR-0023;
 * ingestion-pipelines.md, Teil 3, Punkt 6). Not a file format: the pipeline claims no extension and
 * is invoked by {@code FileProcessingService#ingest} directly.
 *
 * <p>The XHTML itself is read by the shared {@link XhtmlEventBuilder}; {@link
 * ConfluenceElementRule} adds the macro elements on top, with {@link ConfluenceMacroRules} deciding
 * which macros stay: statically embedded content does, view-time content does not. h1-h3 cut a
 * chunk ({@link #MAX_CUTTING_LEVEL}), and the heading path becomes the chunk's first line and its
 * {@code location}. Space key and hierarchy path come from the caller, declared here as passthrough
 * keys.
 */
public class ConfluenceDocumentPipeline implements DocumentPipeline {

  public static final String ID = "confluence";
  static final short VERSION = 1;

  /** Chunk metadata: the Confluence space key the page belongs to. */
  public static final String SPACE_METADATA_KEY = "source_container_key";

  /**
   * Chunk metadata: the page's ancestors root first, joined with " / " - the same value as the
   * document's own column of that name; the page title is the chunk's {@code file_name}.
   */
  public static final String HIERARCHY_METADATA_KEY = "source_hierarchy_path";

  /** h1-h3 open a new chunk, like the HTML and Markdown pipelines; h4-h6 fold into the text. */
  static final int MAX_CUTTING_LEVEL = 3;

  @Override
  public String id() {
    return ID;
  }

  @Override
  public short version() {
    return VERSION;
  }

  /** No file format - invoked directly by the Confluence run, never routed (see class Javadoc). */
  @Override
  public Set<String> handledFormats() {
    return Set.of();
  }

  @Override
  public Set<String> passthroughMetadataKeys() {
    return Set.of(
        ChunkingService.LOCATION_METADATA_KEY, SPACE_METADATA_KEY, HIERARCHY_METADATA_KEY);
  }

  @Override
  public DocumentPipelineResult run(DocumentPipelineSource source) {
    String body = bodyOf(source);
    if (body == null || body.isBlank()) {
      return DocumentPipelineResult.noContent();
    }
    // The XML parser keeps the namespaced macro elements intact; the HTML parser would not.
    org.jsoup.nodes.Document document = Jsoup.parse(body, "", Parser.xmlParser());
    List<Event> events = new XhtmlEventBuilder(ConfluenceElementRule.INSTANCE).build(document);
    List<Document> chunks = HeadingSectionSplitter.chunk(events, MAX_CUTTING_LEVEL);
    if (chunks.isEmpty()) {
      return DocumentPipelineResult.noExtractableText();
    }
    return DocumentPipelineResult.chunked(chunks);
  }

  private static String bodyOf(DocumentPipelineSource source) {
    if (source.file() == null) {
      return source.extractedText();
    }
    try {
      return Files.readString(source.file(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read Confluence body " + source.fileName(), e);
    }
  }
}
