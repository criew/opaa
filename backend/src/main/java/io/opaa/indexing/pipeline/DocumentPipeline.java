package io.opaa.indexing.pipeline;

import io.opaa.indexing.ChunkingService;
import java.util.Set;

/**
 * One ingestion pipeline (docs/features/ingestion-pipelines.md, Teil 1): reader, splitter, metadata
 * enrichment and chunk size for one class of documents, behind a single call.
 *
 * <p><b>Open-closed is the acceptance criterion, not a style wish.</b> Adding a format means adding
 * an implementation of this interface and registering it as a bean - nothing in {@link
 * DocumentPipelineRegistry}, {@code FileProcessingService} or {@code SupportedDocumentFormats}
 * changes shape for it. {@code SupportedDocumentFormats} stays the single admission decision (what
 * is indexed at all); this interface only decides <em>how</em> an already-admitted document is
 * processed.
 *
 * <p><b>Every chunk carries {@link #id()} and {@link #version()}</b> as metadata (see {@link
 * ChunkPipelineMetadata}), written by {@code FileProcessingService#storeChunks}. {@link #version()}
 * is raised whenever this pipeline's cut or the structure metadata it emits changes - never for a
 * fix without an effect on the produced chunks. That is what makes "re-index every chunk below
 * version N of this pipeline" answerable at all; see {@code
 * io.opaa.indexing.PipelineReindexService}.
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
   * Parses and splits {@code source} into chunks. Never throws for a parse failure of {@code
   * source} itself - reports it as {@link DocumentPipelineResult.Outcome#PARSE_FAILED} instead (see
   * that outcome's own Javadoc for the exact contract a new implementation must follow).
   *
   * <p>A pipeline that finds embedded objects while parsing (e.g. mail attachments) without itself
   * turning them into chunks reports them via {@link
   * DocumentPipelineResult#discoveredAttachments()} (ADR-0022, part 2) rather than processing them
   * inline. Every caller of this method goes through {@link DocumentPipelineRunner#run}, which owns
   * deleting any temporary file such an attachment carries. Such a pipeline must honour {@link
   * DocumentPipelineSource#attachmentIndex()} (#1243) - see that component's own Javadoc for the
   * exact contract; a pipeline that never reports attachments ignores it.
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
   * Chunk metadata keys this pipeline may set on a produced chunk's own metadata (as opposed to
   * {@link ChunkPipelineMetadata}'s keys, which {@code storeChunks} writes itself on every chunk
   * regardless of any pipeline's declaration here) that {@code FileProcessingService#storeChunks}
   * carries onto the persisted chunk. A key this pipeline never actually set on a given chunk is
   * simply skipped - declaring it here is a ceiling, not a promise every chunk carries it. The
   * bookkeeping keys {@code storeChunks} writes itself on every chunk can never be overridden this
   * way, declared or not - {@code storeChunks} writes those before consulting any pipeline's
   * declaration. This is the sole extension point for adding a passthrough key: overriding this
   * method, nothing in the caller. Never returns {@code null}.
   *
   * <p>{@code storeChunks} is called with one pipeline per document, but filters against the
   * <b>union</b> of every registered pipeline's declaration ({@link
   * DocumentPipelineRegistry#allPassthroughMetadataKeys()}), not just that one pipeline's own - a
   * document's chunks can come from a different, nested pipeline than the one they end up
   * attributed to (an attachment routed recursively by {@code MailDocumentPipeline}), so a key only
   * the nested pipeline declares must still pass through.
   *
   * <p>Defaults to {@link ChunkingService#LOCATION_METADATA_KEY}, the Fundort most pipelines derive
   * via {@link HeadingSectionSplitter} or their own splitting; override to add further structural
   * fields (e.g. {@code io.opaa.indexing.pipeline.mail.ChunkMailMetadata}'s Kopfdaten keys).
   */
  default Set<String> passthroughMetadataKeys() {
    return Set.of(ChunkingService.LOCATION_METADATA_KEY);
  }
}
