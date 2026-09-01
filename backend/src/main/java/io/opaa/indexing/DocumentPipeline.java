package io.opaa.indexing;

import java.util.Set;

/**
 * One ingestion pipeline (docs/features/ingestion-pipelines.md, Teil 1): reader, splitter, metadata
 * enrichment and chunk size for one class of documents, behind a single call.
 *
 * <p><b>Open-closed is the acceptance criterion, not a style wish.</b> Adding a format means adding
 * an implementation of this interface and registering it as a bean - nothing in {@link
 * DocumentPipelineRegistry}, {@link FileProcessingService} or {@link SupportedDocumentFormats}
 * changes shape for it. {@link SupportedDocumentFormats} stays the single admission decision (what
 * is indexed at all); this interface only decides <em>how</em> an already-admitted document is
 * processed.
 *
 * <p><b>Every chunk carries {@link #id()} and {@link #version()}</b> as metadata (see {@link
 * ChunkPipelineMetadata}), written by {@code FileProcessingService#storeChunks}. {@link #version()}
 * is raised whenever this pipeline's cut or the structure metadata it emits changes - never for a
 * fix without an effect on the produced chunks. That is what makes "re-index every chunk below
 * version N of this pipeline" answerable at all; see {@link PipelineReindexService}.
 */
public interface DocumentPipeline {

  /**
   * Stable identity, persisted on every chunk this pipeline produces - renaming it orphans the
   * existing corpus from its pipeline, so it is part of the persisted contract, not a display name.
   */
  String id();

  /**
   * The version of the cut and structure metadata this pipeline currently produces. Raised on a
   * change to either; never on a behaviour-neutral fix.
   */
  short version();

  /**
   * The canonical {@link SupportedDocumentFormats} extensions this pipeline claims (e.g. {@code
   * ".pdf"}) - the routing key {@link DocumentPipelineRegistry} resolves from a document's
   * <em>detected content</em>, never from its file name alone. Empty for the fallback pipeline,
   * which claims no format and handles everything no other pipeline claimed.
   */
  Set<String> handledFormats();

  /** Parses and splits {@code source} into chunks. */
  DocumentPipelineResult run(DocumentPipelineSource source);
}
