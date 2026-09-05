package io.opaa.indexing.pipeline;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.DocumentService;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

/**
 * The universal fallback pipeline (docs/features/ingestion-pipelines.md, Teil 1): Tika reader plus
 * the token splitter with the globally configured {@code opaa.indexing.chunk-size}/{@code
 * -overlap}. It claims no format ({@link #handledFormats()} is empty) and handles everything no
 * specialized pipeline claimed.
 *
 * <p>Its chunk size is <b>gesetzt, nicht gemessen</b> (ingestion-pipelines.md, "Chunk-Größen"): the
 * 1000/100-token default {@code ChunkingService} applies globally, not a value measured against a
 * verwaltungs-corpus.
 */
public class TikaFallbackPipeline implements DocumentPipeline {

  private static final Logger log = LoggerFactory.getLogger(TikaFallbackPipeline.class);

  public static final String ID = "tika-fallback";

  /**
   * Chunks carrying no pipeline metadata at all are attributed to {@link
   * ChunkPipelineMetadata#LEGACY_PIPELINE_VERSION}, never to this version.
   */
  public static final short VERSION = 1;

  private final DocumentService documentService;
  private final ChunkingService chunkingService;

  public TikaFallbackPipeline(DocumentService documentService, ChunkingService chunkingService) {
    this.documentService = documentService;
    this.chunkingService = chunkingService;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public short version() {
    return VERSION;
  }

  @Override
  public Set<String> handledFormats() {
    return Set.of();
  }

  @Override
  public DocumentPipelineResult run(DocumentPipelineSource source) {
    List<Document> parsed;
    if (source.file() != null) {
      parsed = documentService.parseDocument(source.file());
      if (parsed.isEmpty()) {
        return DocumentPipelineResult.noContent();
      }
    } else {
      parsed = List.of(new Document(source.extractedText()));
    }

    List<Document> chunks = chunkingService.chunkDocuments(source.fileName(), parsed);
    if (chunks.isEmpty()) {
      // Non-blank parsed text can still chunk down to nothing (OCR noise or page footers below
      // ChunkingService's own minChunkLengthToEmbed/minChunkSizeChars) - reported as a rejection,
      // so no caller can end up INDEXED with zero chunks.
      return DocumentPipelineResult.noExtractableText();
    }
    return DocumentPipelineResult.chunked(chunks)
        .withProperties(DocumentProperties.EMPTY.withTitleLine(titleLine(source, parsed)));
  }

  /**
   * The opening of the extracted text - the only metadata source this pipeline has, since Tika's
   * own document properties are not read here. Parses the whole document for its first 300
   * characters; Tika has no cheaper entry point, and the formats reaching this pipeline are small.
   */
  @Override
  public DocumentProperties readProperties(DocumentPipelineSource source) {
    if (source.file() == null) {
      return DocumentProperties.EMPTY;
    }
    try {
      return DocumentProperties.EMPTY.withTitleLine(
          titleLine(source, documentService.parseDocument(source.file())));
    } catch (RuntimeException e) {
      log.warn("Could not read properties of {} via Tika", source.fileName(), e);
      return DocumentProperties.EMPTY;
    }
  }

  /**
   * {@code null} for a source without a file: that is text extracted upstream. A title line only
   * names a Dokumentart if the document names <em>itself</em> - a press release names the Satzung
   * it reports about, and would inherit its Dokumentart.
   */
  private static String titleLine(DocumentPipelineSource source, List<Document> parsed) {
    if (source.file() == null) {
      return null;
    }
    for (Document document : parsed) {
      String line = DocumentTitleLine.of(document.getText());
      if (line != null) {
        return line;
      }
    }
    return null;
  }
}
