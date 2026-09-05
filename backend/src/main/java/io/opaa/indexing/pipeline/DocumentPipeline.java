package io.opaa.indexing.pipeline;

import io.opaa.indexing.ChunkingService;
import java.util.Set;

/**
 * One ingestion pipeline (ingestion-pipelines.md, Teil 1): reader, splitter, metadata enrichment
 * and chunk size for one class of documents, behind a single call.
 *
 * <p>Open-closed is the acceptance criterion: adding a format means adding an implementation and
 * registering it as a bean, with nothing in {@link DocumentPipelineRegistry}, {@code
 * FileProcessingService} or {@code SupportedDocumentFormats} changing shape. Admission stays with
 * {@code SupportedDocumentFormats}; this interface decides only <em>how</em> an admitted document
 * is processed. Every chunk carries {@link #id()} and {@link #version()}, and {@link #version()} is
 * raised whenever the cut or the emitted structure metadata changes - that is what makes a
 * selective re-index answerable.
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
   * The canonical {@code SupportedDocumentFormats} extensions this pipeline claims (e.g. {@code
   * ".pdf"}) - the routing key {@link DocumentPipelineRegistry} resolves from a document's
   * <em>detected content</em>, never from its file name alone. Empty for the fallback pipeline,
   * which claims no format and handles everything no other pipeline claimed.
   */
  Set<String> handledFormats();

  /**
   * Parses and splits {@code source} into chunks, never throwing for a parse failure of {@code
   * source} itself - that is reported as {@link DocumentPipelineResult.Outcome#PARSE_FAILED}. A
   * pipeline that finds embedded objects without turning them into chunks reports them via {@link
   * DocumentPipelineResult#discoveredAttachments()} (ADR-0022) and must honour {@link
   * DocumentPipelineSource#attachmentIndex()}; {@link DocumentPipelineRunner} owns the temp files.
   */
  DocumentPipelineResult run(DocumentPipelineSource source);

  /**
   * The raw metadata sources of {@code source} alone (ADR-0024) - what {@link #run} would attach as
   * {@link DocumentPipelineResult#properties()}, without chunking. Lets the Bestandslauf re-read a
   * document's core fields from its original file without re-chunking or re-embedding it. Never
   * throws for a parse failure; returns {@link DocumentProperties#EMPTY} instead. Defaults to
   * {@code EMPTY} for a pipeline whose format declares nothing usable.
   */
  default DocumentProperties readProperties(DocumentPipelineSource source) {
    return DocumentProperties.EMPTY;
  }

  /**
   * Chunk metadata keys {@code FileProcessingService#storeChunks} carries onto the persisted chunk
   * - a ceiling, not a promise, and never able to override its own bookkeeping keys. It filters
   * against the union of every registered pipeline's declaration ({@link
   * DocumentPipelineRegistry#allPassthroughMetadataKeys()}), so a key only a nested pipeline
   * declares still passes through. Never {@code null}.
   */
  default Set<String> passthroughMetadataKeys() {
    return Set.of(ChunkingService.LOCATION_METADATA_KEY);
  }
}
