package io.opaa.indexing.format.stream.confluencestorage;

import io.opaa.indexing.chunk.ChunkingService;
import io.opaa.indexing.format.DocumentFormat;
import io.opaa.indexing.format.DocumentFormatResult;
import io.opaa.indexing.format.DocumentFormatSource;
import io.opaa.indexing.format.shared.HeadingSectionSplitter;
import io.opaa.indexing.format.shared.HeadingSectionSplitter.Event;
import io.opaa.indexing.format.shared.XhtmlEventBuilder;
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
 * ingestion-pipelines.md, Teil 3, Punkt 6). Not a file format: it admits nothing and claims no
 * extension - both defaults of {@code DocumentFormat} - and is invoked by {@code
 * DocumentIngestService#ingest} directly.
 *
 * <p>The XHTML itself is read by the shared {@link XhtmlEventBuilder}; {@link
 * ConfluenceElementRule} adds the macro elements on top, with {@link ConfluenceMacroRules} deciding
 * which macros stay: statically embedded content does, view-time content does not. h1-h3 cut a
 * chunk ({@link #MAX_CUTTING_LEVEL}), and the heading path becomes the chunk's first line and its
 * {@code location}. Space key and hierarchy path come from the caller, declared here as passthrough
 * keys.
 */
public class ConfluenceStorageFormat implements DocumentFormat {

  public static final String ID = "confluence";
  static final short VERSION = 2;

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

  @Override
  public Set<String> passthroughMetadataKeys() {
    return Set.of(
        ChunkingService.LOCATION_METADATA_KEY,
        ChunkingService.SOURCE_CONTAINER_METADATA_KEY,
        ChunkingService.SOURCE_HIERARCHY_METADATA_KEY);
  }

  @Override
  public DocumentFormatResult run(DocumentFormatSource source) {
    String body = bodyOf(source);
    if (body == null || body.isBlank()) {
      return DocumentFormatResult.noContent();
    }
    // The XML parser keeps the namespaced macro elements intact; the HTML parser would not.
    org.jsoup.nodes.Document document = Jsoup.parse(body, "", Parser.xmlParser());
    List<Event> events = new XhtmlEventBuilder(ConfluenceElementRule.INSTANCE).build(document);
    List<Document> chunks = HeadingSectionSplitter.chunk(events, MAX_CUTTING_LEVEL);
    if (chunks.isEmpty()) {
      return DocumentFormatResult.noExtractableText();
    }
    return DocumentFormatResult.chunked(chunks);
  }

  private static String bodyOf(DocumentFormatSource source) {
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
