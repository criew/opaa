package io.opaa.indexing;

import java.util.List;
import java.util.Set;
import org.springframework.ai.document.Document;

/**
 * The universal fallback pipeline (docs/features/ingestion-pipelines.md, Teil 1: "Tika bleibt als
 * Fallback-Pipeline"): Tika reader plus the token splitter with the globally configured {@code
 * opaa.indexing.chunk-size}/{@code -overlap}. It claims no format at all and handles everything no
 * specialized pipeline claimed - which today is every admitted format, so the abstraction is
 * verhaltensneutral for the existing bestand by construction.
 *
 * <p>Chunk size: <b>gesetzt, nicht gemessen</b> (ingestion-pipelines.md, "Chunk-Größen"). The
 * 1000/100-token default predates any measurement on a verwaltungs-corpus; it is the value {@code
 * ChunkingService} has always applied, kept unchanged here so this pipeline reproduces the
 * pre-abstraction cut exactly. A measurement would need a corpus per document class, which is the
 * subject of the per-format issues, not of this one.
 */
public class TikaFallbackPipeline implements DocumentPipeline {

  static final String ID = "tika-fallback";

  /**
   * Version 1 is the cut this project has produced since before the abstraction existed - the
   * pre-#1056 bestand is therefore attributed to version {@link
   * ChunkPipelineMetadata#LEGACY_PIPELINE_VERSION}, not to this one, purely because those chunks
   * carry no metadata saying so.
   */
  static final short VERSION = 1;

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
      if (documentService.isTextlessPdf(source.file(), parsed)) {
        return DocumentPipelineResult.noExtractableText();
      }
      if (parsed.isEmpty()) {
        return DocumentPipelineResult.noContent();
      }
    } else {
      parsed = List.of(new Document(source.extractedText()));
    }

    List<Document> chunks = chunkingService.chunkDocuments(source.fileName(), parsed);
    if (chunks.isEmpty()) {
      // Non-blank parsed text can still chunk down to nothing (OCR noise or page footers below
      // ChunkingService's own minChunkLengthToEmbed/minChunkSizeChars, or a non-PDF format the
      // isTextlessPdf guard above never covers) - reported as the same rejection, so no caller can
      // end up INDEXED with zero chunks regardless of why chunking produced none.
      return DocumentPipelineResult.noExtractableText();
    }
    return DocumentPipelineResult.chunked(chunks);
  }
}
